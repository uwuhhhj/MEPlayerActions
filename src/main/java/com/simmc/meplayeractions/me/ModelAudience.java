package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.action.DisguiseOptions;
import com.simmc.meplayeractions.config.PerformanceSettings;
import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Owns a model tracking filter. Publish immutable snapshots on Paper's main thread. */
public final class ModelAudience {
    private final DisguiseOptions options;
    private final Function<UUID, Player> lookup;
    private final LongSupplier clock;
    private final PerformanceSettings performance;
    private volatile Set<UUID> allowed = Set.of();
    private volatile Set<UUID> localRenderers = Set.of();
    private final Set<UUID> suppressedPairings = new HashSet<>();
    private TrackedEntity tracked;
    private Predicate<Player> original, filter;
    private boolean addedSelf;
    private boolean pairingsDirty;
    private long nextRefresh = Long.MIN_VALUE, nextValidation = Long.MIN_VALUE;
    private long lastUpdate = Long.MIN_VALUE;

    public ModelAudience(DisguiseOptions options) { this(options, PerformanceSettings.defaults()); }
    public ModelAudience(DisguiseOptions options, PerformanceSettings performance) {
        this(options, Bukkit::getPlayer, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()), performance);
    }
    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup) {
        this(options, lookup, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()), PerformanceSettings.defaults());
    }
    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup, LongSupplier clock,
                  PerformanceSettings performance) {
        this.options = options; this.lookup = lookup; this.clock = clock; this.performance = performance;
    }

    public boolean allows(UUID viewer) { return allowed.contains(viewer); }
    private boolean renders(UUID viewer) { return allowed.contains(viewer) && !localRenderers.contains(viewer); }
    /** A ready client retains its audience slot while only its ME display is suppressed. */
    public boolean localRendering(UUID viewer, boolean enabled) {
        Set<UUID> next = new HashSet<>(localRenderers);
        boolean changed = enabled ? next.add(viewer) : next.remove(viewer);
        if (changed) {
            localRenderers = Set.copyOf(next);
            pairingsDirty = true;
            if (tracked != null) tracked.markViewersDirty();
        }
        return changed;
    }
    public int otherViewers(UUID owner) { return allowed.size() - (allowed.contains(owner) ? 1 : 0); }

    public boolean update(Player owner, TrackedEntity next) {
        Objects.requireNonNull(next, "ME 玩家跟踪接口不可用");
        // ME copies our predicate and forced pairings when TempTrackedEntity is replaced.
        if (tracked == null) {
            original = next.getPlayerPredicate();
            Predicate<Player> before = original;
            filter = viewer -> renders(viewer.getUniqueId()) && before.test(viewer);
            next.setPlayerPredicate(filter);
        } else if (next.getPlayerPredicate() != filter) {
            throw new IllegalStateException("ME 更换跟踪接口时未保留模型观众过滤器");
        }
        if (next != tracked) pairingsDirty = true;
        tracked = next;
        long now = clock.getAsLong();
        if (now < lastUpdate) nextRefresh = nextValidation = Long.MIN_VALUE;
        lastUpdate = now;
        Set<UUID> previous = allowed;
        if (now >= nextRefresh) {
            discover(owner, next);
            nextRefresh = nextDeadline(now, owner.getUniqueId(), performance.audienceRefreshTicks());
            nextValidation = now + performance.validationTicks();
            pairingsDirty = true;
        } else if (now >= nextValidation) {
            validate(owner, next);
            nextValidation = now + performance.validationTicks();
            pairingsDirty = true;
        }
        if (pairingsDirty) {
            reconcile(owner, next);
            pairingsDirty = false;
        }
        boolean changed = !previous.equals(allowed);
        if (changed) next.markViewersDirty();
        return changed;
    }

    private void discover(Player owner, TrackedEntity next) {
        Location origin = owner.getLocation();
        Map<UUID, Player> candidates = new HashMap<>();
        for (UUID id : originalTracking(next)) candidates.putIfAbsent(id, lookup.apply(id));
        for (UUID id : suppressedPairings) candidates.putIfAbsent(id, lookup.apply(id));
        // Departed players need no restoration and must not accumulate for the
        // lifetime of a disguise that sees many different visitors.
        suppressedPairings.removeIf(id -> candidates.get(id) == null || !candidates.get(id).isOnline());
        List<AudienceSelector.Candidate> eligible = new ArrayList<>();
        for (Player viewer : candidates.values()) {
            if (viewer == null || viewer.getUniqueId().equals(owner.getUniqueId()) || !eligible(owner, viewer)) continue;
            eligible.add(new AudienceSelector.Candidate(viewer.getUniqueId(), origin.distanceSquared(viewer.getLocation())));
        }
        allowed = AudienceSelector.select(owner.getUniqueId(), options.showSelf(), options.viewDistance(), options.maxViewers(), eligible);
    }

    private void validate(Player owner, TrackedEntity next) {
        if (allowed.isEmpty()) return;
        // ME's UUID tracking snapshot is still read, but player lookup, vanish,
        // world and distance checks are bounded by already admitted viewers.
        Set<UUID> current = originalTracking(next);
        Set<UUID> retained = new HashSet<>();
        Location origin = owner.getLocation();
        double distance = options.viewDistance() * options.viewDistance();
        for (UUID id : allowed) {
            if (id.equals(owner.getUniqueId())) { if (options.showSelf()) retained.add(id); continue; }
            Player viewer = lookup.apply(id);
            if (viewer != null && (current.contains(id) || suppressedPairings.contains(id)) && eligible(owner, viewer)
                    && origin.distanceSquared(viewer.getLocation()) < distance) retained.add(id);
        }
        allowed = Set.copyOf(retained);
    }

    private boolean eligible(Player owner, Player viewer) {
        return viewer.isOnline() && viewer.getWorld().equals(owner.getWorld()) && viewer.canSee(owner) && original.test(viewer);
    }

    private Set<UUID> originalTracking(TrackedEntity next) {
        // R4.1.1 updates tracking on Paper's main thread. Read a candidate snapshot
        // through the prior predicate, then restore ours before returning; this keeps
        // ME's forced-hidden viewers out of the cap while admitting new entrants.
        Predicate<Player> installed = next.getPlayerPredicate();
        Set<UUID> current;
        try {
            if (installed == filter) next.setPlayerPredicate(original);
            current = next.getTrackedPlayer();
        } finally {
            if (installed == filter) next.setPlayerPredicate(filter);
        }
        return current;
    }

    static long nextDeadline(long now, UUID owner, int interval) {
        long delay = Math.floorMod((long) owner.hashCode() - Math.floorMod(now, interval), interval);
        return now + (delay == 0 ? interval : delay);
    }

    private void reconcile(Player owner, TrackedEntity next) {
        // ME's forced pairings bypass its player predicate. Temporarily remove only
        // disallowed pairings, retaining them for restoration when a slot opens/cleanup.
        for (UUID id : List.copyOf(suppressedPairings)) if (renders(id)) {
            next.addForcedPairing(id); suppressedPairings.remove(id);
        }
        Set<UUID> rendered = next.getTrackedPlayer();
        for (UUID id : rendered) if (!renders(id)) {
            next.removeForcedPairing(id);
            if (id.equals(owner.getUniqueId()) && addedSelf) addedSelf = false;
            else suppressedPairings.add(id);
        }
        if (options.showSelf() && renders(owner.getUniqueId()) && !rendered.contains(owner.getUniqueId())) {
            next.addForcedPairing(owner.getUniqueId()); addedSelf = true;
        }
    }

    /** Use the current data wrapper, which may differ from the initial temporary one. */
    public void close(TrackedEntity current, UUID owner) {
        if (current == null) current = tracked;
        if (current == null) return;
        if (current.getPlayerPredicate() == filter) current.setPlayerPredicate(original);
        if (addedSelf) current.removeForcedPairing(owner);
        for (UUID id : suppressedPairings) current.addForcedPairing(id);
        current.markViewersDirty();
        suppressedPairings.clear(); addedSelf = false;
        localRenderers = Set.of(); allowed = Set.of(); pairingsDirty = false;
    }
}
