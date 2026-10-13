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
    /** Shared admission is independent of ME rendering and applies before publication. */
    public interface Guard {
        boolean discover();

        Set<UUID> admit(UUID owner, Collection<UUID> requested);

        void release(UUID owner);

        default void worked(long nanos) {
        }
    }

    private static final Guard UNLIMITED = new Guard() {
        public boolean discover() {
            return true;
        }

        public Set<UUID> admit(UUID owner, Collection<UUID> requested) {
            return Set.copyOf(requested);
        }

        public void release(UUID owner) {
        }
    };
    private final DisguiseOptions options;
    private final Function<UUID, Player> lookup;
    private final LongSupplier clock;
    private final PerformanceSettings performance;
    private final Guard guard;
    private volatile Set<UUID> allowed = Set.of();
    private volatile Set<UUID> localRenderers = Set.of();
    private final Set<UUID> suppressedPairings = new HashSet<>();
    private TrackedEntity tracked;
    private Predicate<Player> original, filter;
    private boolean addedSelf;
    private boolean pairingsDirty;
    private long nextRefresh = Long.MIN_VALUE, nextValidation = Long.MIN_VALUE;
    private long lastUpdate = Long.MIN_VALUE;

    public ModelAudience(DisguiseOptions options) {
        this(options, PerformanceSettings.defaults());
    }

    public ModelAudience(DisguiseOptions options, PerformanceSettings performance) {
        this(options, Bukkit::getPlayer, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()), performance);
    }

    public ModelAudience(DisguiseOptions options, PerformanceSettings performance, Guard guard) {
        this(options, Bukkit::getPlayer, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()), performance, guard);
    }

    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup) {
        this(options, lookup, () -> Integer.toUnsignedLong(Bukkit.getCurrentTick()), PerformanceSettings.defaults());
    }

    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup, LongSupplier clock,
            PerformanceSettings performance) {
        this(options, lookup, clock, performance, UNLIMITED);
    }

    ModelAudience(DisguiseOptions options, Function<UUID, Player> lookup, LongSupplier clock,
            PerformanceSettings performance, Guard guard) {
        this.options = options;
        this.lookup = lookup;
        this.clock = clock;
        this.performance = performance;
        this.guard = Objects.requireNonNull(guard);
    }

    public boolean allows(UUID viewer) {
        return this.allowed.contains(viewer);
    }

    Set<UUID> viewerIds() {
        return this.allowed;
    }

    private boolean renders(UUID viewer) {
        return this.allowed.contains(viewer) && !this.localRenderers.contains(viewer);
    }

    /** A ready client retains its audience slot while only its ME display is suppressed. */
    public boolean localRendering(UUID viewer, boolean enabled) {
        Set<UUID> next = new HashSet<>(this.localRenderers);
        boolean changed = enabled ? next.add(viewer) : next.remove(viewer);
        if (changed) {
            this.localRenderers = Set.copyOf(next);
            this.pairingsDirty = true;
            if (this.tracked != null) {
                this.tracked.markViewersDirty();
            }

        }
        return changed;
    }

    public int otherViewers(UUID owner) {
        return this.allowed.size() - (this.allowed.contains(owner) ? 1 : 0);
    }

    public int viewers() {
        return this.allowed.size();
    }

    public boolean update(Player owner, TrackedEntity next) {
        long started = System.nanoTime();
        try {
            return updateTracked(owner, next);
        } finally {
            this.guard.worked(System.nanoTime() - started);
        }
    }

    private boolean updateTracked(Player owner, TrackedEntity next) {
        Objects.requireNonNull(next, "ME 玩家跟踪接口不可用");
        // ME copies our predicate and forced pairings when TempTrackedEntity is replaced.
        if (this.tracked == null) {
            this.original = next.getPlayerPredicate();
            Predicate<Player> before = this.original;
            this.filter = viewer -> renders(viewer.getUniqueId()) && before.test(viewer);
            next.setPlayerPredicate(this.filter);
        } else if (next.getPlayerPredicate() != this.filter) {
            throw new IllegalStateException("ME 更换跟踪接口时未保留模型观众过滤器");
        }
        if (next != this.tracked) {
            this.pairingsDirty = true;
        }

        this.tracked = next;
        long now = this.clock.getAsLong();
        if (now < this.lastUpdate) {
            this.nextRefresh = this.nextValidation = Long.MIN_VALUE;
        }

        this.lastUpdate = now;
        Set<UUID> previous = this.allowed;
        if (now >= this.nextRefresh && this.guard.discover()) {
            discover(owner, next);
            this.nextRefresh = nextDeadline(now, owner.getUniqueId(), this.performance.audienceRefreshTicks());
            this.nextValidation = now + this.performance.validationTicks();
            this.pairingsDirty = true;
        } else if (now >= this.nextValidation) {
            validate(owner, next);
            this.nextValidation = now + this.performance.validationTicks();
            this.pairingsDirty = true;
        }
        if (this.pairingsDirty) {
            reconcile(owner, next);
            this.pairingsDirty = false;
        }
        boolean changed = !previous.equals(this.allowed);
        if (changed) {
            next.markViewersDirty();
        }

        return changed;
    }

    private void discover(Player owner, TrackedEntity next) {
        Location origin = owner.getLocation();
        Map<UUID, Player> candidates = new HashMap<>();
        for (UUID id : originalTracking(next)) {
            candidates.putIfAbsent(id, this.lookup.apply(id));
        }

        for (UUID id : this.suppressedPairings) {
            candidates.putIfAbsent(id, this.lookup.apply(id));
        }

        // Departed players need no restoration and must not accumulate for the
        // lifetime of a disguise that sees many different visitors.
        this.suppressedPairings.removeIf(id -> candidates.get(id) == null || !candidates.get(id).isOnline());
        List<AudienceSelector.Candidate> eligible = new ArrayList<>();
        for (Player viewer : candidates.values()) {
            if (viewer == null || viewer.getUniqueId().equals(owner.getUniqueId()) || !eligible(owner, viewer)) {
                continue;
            }

            eligible.add(
                    new AudienceSelector.Candidate(viewer.getUniqueId(), origin.distanceSquared(viewer.getLocation())));
        }
        Set<UUID> selected = AudienceSelector.select(owner.getUniqueId(), this.options.showSelf(),
                this.options.viewDistance(), this.options.maxViewers(), eligible);
        // Retain existing eligible slots first; admit new viewers nearest-first so
        // another model reaching a global cap cannot evict healthy relationships.
        List<UUID> requested = new ArrayList<>();
        for (UUID id : this.allowed) {
            if (selected.contains(id)) {
                requested.add(id);
            }

        }

        if (selected.contains(owner.getUniqueId()) && !requested.contains(owner.getUniqueId())) {
            requested.add(owner.getUniqueId());
        }

        Set<UUID> requestedIds = new HashSet<>(requested);
        eligible.stream().filter(candidate -> selected.contains(candidate.id()))
                .sorted(Comparator.comparingDouble(AudienceSelector.Candidate::distanceSquared)
                        .thenComparing(AudienceSelector.Candidate::id))
                .map(AudienceSelector.Candidate::id).filter(requestedIds::add).forEach(requested::add);
        this.allowed = Set.copyOf(this.guard.admit(owner.getUniqueId(), requested));
    }

    private void validate(Player owner, TrackedEntity next) {
        if (this.allowed.isEmpty()) {
            this.guard.admit(owner.getUniqueId(), List.of());
            return;
        }
        // ME's UUID tracking snapshot is still read, but player lookup, vanish,
        // world and distance checks are bounded by already admitted viewers.
        Set<UUID> current = originalTracking(next);
        Set<UUID> retained = new HashSet<>();
        Location origin = owner.getLocation();
        double distance = this.options.viewDistance() * this.options.viewDistance();
        for (UUID id : this.allowed) {
            if (id.equals(owner.getUniqueId())) {
                if (this.options.showSelf()) {
                    retained.add(id);
                }
                continue;
            }
            Player viewer = this.lookup.apply(id);
            if (viewer != null && (current.contains(id) || this.suppressedPairings.contains(id))
                    && eligible(owner, viewer) && origin.distanceSquared(viewer.getLocation()) < distance) {
                retained.add(id);
            }

        }
        this.allowed = Set.copyOf(this.guard.admit(owner.getUniqueId(), retained));
    }

    private boolean eligible(Player owner, Player viewer) {
        return viewer.isOnline() && viewer.getWorld().equals(owner.getWorld()) && viewer.canSee(owner)
                && this.original.test(viewer);
    }

    private Set<UUID> originalTracking(TrackedEntity next) {
        // R4.1.1 updates tracking on Paper's main thread. Read a candidate snapshot
        // through the prior predicate, then restore ours before returning; this keeps
        // ME's forced-hidden viewers out of the cap while admitting new entrants.
        Predicate<Player> installed = next.getPlayerPredicate();
        Set<UUID> current;
        try {
            if (installed == this.filter) {
                next.setPlayerPredicate(this.original);
            }

            current = next.getTrackedPlayer();
        } finally {
            if (installed == this.filter) {
                next.setPlayerPredicate(this.filter);
            }

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
        for (UUID id : List.copyOf(this.suppressedPairings)) {
            if (renders(id)) {
                next.addForcedPairing(id);
                this.suppressedPairings.remove(id);
            }
        }

        Set<UUID> rendered = next.getTrackedPlayer();
        for (UUID id : rendered) {
            if (!renders(id)) {
                next.removeForcedPairing(id);
                if (id.equals(owner.getUniqueId()) && this.addedSelf) {
                    this.addedSelf = false;
                }

                else {
                    this.suppressedPairings.add(id);
                }

            }
        }

        if (this.options.showSelf() && renders(owner.getUniqueId()) && !rendered.contains(owner.getUniqueId())) {
            next.addForcedPairing(owner.getUniqueId());
            this.addedSelf = true;
        }
    }

    /** Use the current data wrapper, which may differ from the initial temporary one. */
    public void close(TrackedEntity current, UUID owner) {
        if (current == null) {
            current = this.tracked;
        }

        if (current == null) {
            this.guard.release(owner);
            return;
        }
        if (current.getPlayerPredicate() == this.filter) {
            current.setPlayerPredicate(this.original);
        }

        if (this.addedSelf) {
            current.removeForcedPairing(owner);
        }

        for (UUID id : this.suppressedPairings) {
            current.addForcedPairing(id);
        }

        current.markViewersDirty();
        this.suppressedPairings.clear();
        this.addedSelf = false;
        this.localRenderers = Set.of();
        this.allowed = Set.of();
        this.pairingsDirty = false;
        this.guard.release(owner);
    }
}
