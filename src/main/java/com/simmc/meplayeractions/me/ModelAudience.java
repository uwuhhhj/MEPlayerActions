package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.action.DisguiseOptions;
import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Owns a model tracking filter. Publish immutable snapshots on Paper's main thread. */
public final class ModelAudience {
    private final DisguiseOptions options;
    private final Function<UUID, Player> lookup;
    private volatile Set<UUID> allowed = Set.of();
    private final Set<UUID> suppressedPairings = new HashSet<>();
    private TrackedEntity tracked;
    private Predicate<Player> original, filter;
    private boolean addedSelf;

    public ModelAudience(DisguiseOptions options) { this(options, Bukkit::getPlayer); }
    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup) {
        this.options = options; this.lookup = lookup;
    }

    public boolean allows(UUID viewer) { return allowed.contains(viewer); }
    public int otherViewers(UUID owner) { return allowed.size() - (allowed.contains(owner) ? 1 : 0); }

    public boolean update(Player owner, TrackedEntity next) {
        Objects.requireNonNull(next, "ME 玩家跟踪接口不可用");
        // ME copies our predicate and forced pairings when TempTrackedEntity is replaced.
        if (tracked == null) {
            original = next.getPlayerPredicate();
            Predicate<Player> before = original;
            filter = viewer -> allowed.contains(viewer.getUniqueId()) && before.test(viewer);
            next.setPlayerPredicate(filter);
        } else if (next != tracked && next.getPlayerPredicate() != filter) {
            throw new IllegalStateException("ME 更换跟踪接口时未保留模型观众过滤器");
        }
        tracked = next;
        Location origin = owner.getLocation();
        Map<UUID, Player> candidates = new HashMap<>();
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
        for (UUID id : current) candidates.putIfAbsent(id, lookup.apply(id));
        for (UUID id : suppressedPairings) candidates.putIfAbsent(id, lookup.apply(id));
        List<AudienceSelector.Candidate> eligible = new ArrayList<>();
        for (Player viewer : candidates.values()) {
            if (viewer == null || !viewer.isOnline() || viewer.getUniqueId().equals(owner.getUniqueId())
                    || !viewer.getWorld().equals(owner.getWorld()) || !viewer.canSee(owner) || !original.test(viewer)) continue;
            eligible.add(new AudienceSelector.Candidate(viewer.getUniqueId(), origin.distanceSquared(viewer.getLocation())));
        }
        Set<UUID> previous = allowed;
        allowed = AudienceSelector.select(owner.getUniqueId(), options.showSelf(), options.viewDistance(), options.maxViewers(), eligible);
        // ME's forced pairings bypass its player predicate. Temporarily remove only
        // disallowed pairings, retaining them for restoration when a slot opens/cleanup.
        for (UUID id : List.copyOf(suppressedPairings)) if (allowed.contains(id)) {
            next.addForcedPairing(id); suppressedPairings.remove(id);
        }
        for (UUID id : next.getTrackedPlayer()) if (!allowed.contains(id)) {
            next.removeForcedPairing(id);
            if (id.equals(owner.getUniqueId()) && addedSelf) addedSelf = false;
            else suppressedPairings.add(id);
        }
        if (options.showSelf() && !next.getTrackedPlayer().contains(owner.getUniqueId())) {
            next.addForcedPairing(owner.getUniqueId()); addedSelf = true;
        }
        boolean changed = !previous.equals(allowed);
        if (changed) next.markViewersDirty();
        return changed;
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
    }
}
