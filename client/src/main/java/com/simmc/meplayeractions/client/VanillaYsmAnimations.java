package com.simmc.meplayeractions.client;

import java.util.*;

/** Vanilla entity snapshots and the built-in YSM hand/vehicle predicates. No client input or MC classes. */
public final class VanillaYsmAnimations {
    public enum Hand { NONE, MAIN, OFF }
    public enum Directive { PLAY, PAUSE, CONTINUE, STOP }
    /** Native adapters retain the tracked stack reference, matching OpenYSM's item comparison lifecycle. */
    public interface TrackedItemComparison { boolean isDamaged(); }
    public record ItemState(String id, Set<String> tags, String kind, String useAction,
                            boolean empty, boolean chargedCrossbow, long revision, boolean damaged, Object comparisonKey) {
        public static final ItemState EMPTY = new ItemState("minecraft:air", Set.of(), "", "none", true, false, 0);
        public ItemState(String id, Set<String> tags, String kind, String useAction,
                         boolean empty, boolean chargedCrossbow, long revision) {
            this(id, tags, kind, useAction, empty, chargedCrossbow, revision, false);
        }
        public ItemState(String id, Set<String> tags, String kind, String useAction,
                         boolean empty, boolean chargedCrossbow, long revision, boolean damaged) {
            this(id, tags, kind, useAction, empty, chargedCrossbow, revision, damaged, Long.valueOf(revision));
        }
        public ItemState {
            if (!resourceId(id) || kind == null || !kind.matches("[a-z0-9_]{0,64}")
                    || useAction == null || !useAction.matches("[a-z0-9_]{1,32}"))
                throw new IllegalArgumentException("Vanilla held item");
            tags = checkedTags(tags);
            Objects.requireNonNull(comparisonKey, "Native tracked item comparison");
        }
        String eventKey() { return id + ":" + revision; }
    }
    public record VanillaState(boolean dead, int hurtTime, boolean riptide, boolean sleeping, boolean swimming,
                               ItemState mainhand, ItemState offhand, Hand usingHand, int useTicks,
                               Hand swingingHand, int swingTicks, boolean fishing, String vehicleId,
                               Set<String> vehicleTags, boolean vehicleAlive, boolean vehicleSaddled,
                               Map<String,ItemState> armor, String passengerId, Set<String> passengerTags, boolean passengerAlive,
                               long swingSequence) {
        public static final VanillaState NONE = new VanillaState(false, 0, false, false, false,
                ItemState.EMPTY, ItemState.EMPTY, Hand.NONE, 0, Hand.NONE, 0, false, "", Set.of(), false, false);
        public VanillaState(boolean dead,int hurtTime,boolean riptide,boolean sleeping,boolean swimming,
                            ItemState mainhand,ItemState offhand,Hand usingHand,int useTicks,Hand swingingHand,
                            int swingTicks,boolean fishing,String vehicleId,Set<String> vehicleTags,boolean vehicleAlive,boolean vehicleSaddled) {
            this(dead,hurtTime,riptide,sleeping,swimming,mainhand,offhand,usingHand,useTicks,swingingHand,swingTicks,
                    fishing,vehicleId,vehicleTags,vehicleAlive,vehicleSaddled,Map.of(),"",Set.of(),false,0);
        }
        public VanillaState(boolean dead,int hurtTime,boolean riptide,boolean sleeping,boolean swimming,
                            ItemState mainhand,ItemState offhand,Hand usingHand,int useTicks,Hand swingingHand,
                            int swingTicks,boolean fishing,String vehicleId,Set<String> vehicleTags,boolean vehicleAlive,boolean vehicleSaddled,
                            Map<String,ItemState> armor,String passengerId,Set<String> passengerTags,boolean passengerAlive) {
            this(dead,hurtTime,riptide,sleeping,swimming,mainhand,offhand,usingHand,useTicks,swingingHand,swingTicks,
                    fishing,vehicleId,vehicleTags,vehicleAlive,vehicleSaddled,armor,passengerId,passengerTags,passengerAlive,0);
        }
        public VanillaState {
            Objects.requireNonNull(mainhand); Objects.requireNonNull(offhand);
            Objects.requireNonNull(usingHand); Objects.requireNonNull(swingingHand);
            if (hurtTime < 0 || hurtTime > 100_000 || useTicks < 0 || swingTicks < 0
                    || vehicleId == null || !vehicleId.isEmpty() && !resourceId(vehicleId))
                throw new IllegalArgumentException("Vanilla entity state");
            vehicleTags = checkedTags(vehicleTags);
            Objects.requireNonNull(armor);armor=Map.copyOf(armor);
            if(!Set.of("head","chest","legs","feet").containsAll(armor.keySet()))throw new IllegalArgumentException("Vanilla armor slots");
            if(passengerId==null||!passengerId.isEmpty()&&!resourceId(passengerId))throw new IllegalArgumentException("Vanilla passenger");
            passengerTags=checkedTags(passengerTags);
            if(swingSequence<0)throw new IllegalArgumentException("Native local swing sequence");
        }
        public ItemState item(Hand hand) { return hand == Hand.OFF ? offhand : mainhand; }
    }
    public record Selection(Directive directive, String animation, String loop, String eventKey) {
        private static final Selection STOP = new Selection(Directive.STOP, "", "LOOP", "");
        private static final Selection PAUSE = new Selection(Directive.PAUSE, "", "LOOP", "");
        private static final Selection CONTINUE = new Selection(Directive.CONTINUE, "", "ONCE", "");
    }
    public record Decision(Map<String, Selection> slots) {
        public Decision { slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots)); }
        public Set<String> pauseSlots() {
            Set<String> result = new LinkedHashSet<>();
            slots.forEach((slot, selection) -> { if (selection.directive == Directive.PAUSE) result.add(slot); });
            return Set.copyOf(result);
        }
    }
    /** Preserve authored order when several item/entity tags match. */
    public static final class Catalog {
        private final Set<String> names;
        private final int formatVersion;
        private final Map<String,String> authoredLoops;
        private final Set<String> primaryAnimations;
        /** Names-only inventories retain the old, non-primary format's predicate overrides. */
        public Catalog(Collection<String> animations) {
            this(animations, 0, Map.of(), Set.of(), false);
        }
        public Catalog(Collection<String> animations, int formatVersion, Map<String,String> authoredLoops,
                       Set<String> primaryAnimations) {
            this(animations, formatVersion, authoredLoops, primaryAnimations, true);
        }
        private Catalog(Collection<String> animations, int formatVersion, Map<String,String> authoredLoops,
                        Set<String> primaryAnimations, boolean metadata) {
            Objects.requireNonNull(animations);
            if (animations.size() > (formatVersion > 0 ? 1024 : 128)) throw new IllegalArgumentException("Animation inventory");
            var copy = new LinkedHashSet<String>();
            for (String name : animations) {
                if (name == null || name.length() > 128 || name.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Animation name");
                copy.add(name);
            }
            names = Collections.unmodifiableSet(copy);
            if (formatVersion < 0 || formatVersion > 65535) throw new IllegalArgumentException("YSM format version");
            Objects.requireNonNull(authoredLoops); Objects.requireNonNull(primaryAnimations);
            if (metadata && !names.equals(authoredLoops.keySet()) || !names.containsAll(primaryAnimations))
                throw new IllegalArgumentException("Animation metadata inventory");
            var loops = new LinkedHashMap<String,String>();
            authoredLoops.forEach((name, loop) -> {
                if (loop == null || !Set.of("LOOP", "ONCE", "HOLD").contains(loop))
                    throw new IllegalArgumentException("Animation metadata loop");
                loops.put(name, loop);
            });
            this.formatVersion = formatVersion;
            this.authoredLoops = Collections.unmodifiableMap(loops);
            this.primaryAnimations = Set.copyOf(primaryAnimations);
        }
        public boolean contains(String animation) { return names.contains(animation); }
        public int formatVersion() { return formatVersion; }
        public boolean fromPrimaryAssembly(String animation) { return primaryAnimations.contains(animation); }
        public String authoredLoop(String animation) { return authoredLoops.getOrDefault(animation, "ONCE"); }
        public String first(String... candidates) {
            for (String name : candidates) if (names.contains(name)) return name;
            return "";
        }
        private String condition(String prefix, ItemState item) {
            if (item.empty) return first(prefix + ":empty");
            String exact = first(prefix + "$" + item.id);
            if (!exact.isEmpty()) return exact;
            for (String name : names) if (name.startsWith(prefix + "#") && item.tags.contains(name.substring(prefix.length() + 1))) return name;
            if (!item.kind.isEmpty()) {
                String category = first(prefix + ":" + item.kind);
                if (!category.isEmpty()) return category;
            }
            return item.useAction.equals("none") ? "" : first(prefix + ":" + item.useAction);
        }
        public String vehicle(VanillaState state) {
            if (!state.vehicleAlive || state.vehicleId.isEmpty()) return "";
            String exact = first("vehicle$" + state.vehicleId);
            if (!exact.isEmpty()) return exact;
            for (String name : names) if (name.startsWith("vehicle#") && state.vehicleTags.contains(name.substring(8))) return name;
            return "";
        }
        public String passenger(VanillaState state) {
            if(!state.passengerAlive||state.passengerId.isEmpty())return "";
            String exact=first("passenger$"+state.passengerId);if(!exact.isEmpty())return exact;
            for(String name:names)if(name.startsWith("passenger#")&&state.passengerTags.contains(name.substring(10)))return name;
            return "";
        }
        private String armor(String slot,ItemState item) {
            if(item.empty)return "";
            String exact=first(slot+"$"+item.id);if(!exact.isEmpty())return exact;
            for(String name:names)if(name.startsWith(slot+"#")&&item.tags.contains(name.substring(slot.length()+1)))return name;
            return first(slot+":default");
        }
    }

    public static Decision select(VanillaState state, Catalog catalog) {
        Objects.requireNonNull(state); Objects.requireNonNull(catalog);
        Map<String, Selection> result = new LinkedHashMap<>();
        result.put("player.hold_offhand", hold(state, catalog, Hand.OFF));
        result.put("player.hold_mainhand", hold(state, catalog, Hand.MAIN));
        // OpenYSM returns CONTINUE after a native swing, allowing its ONCE clip to finish.
        Selection swing = state.sleeping ? Selection.STOP : Selection.CONTINUE, use = Selection.STOP;
        if (!state.sleeping && state.swingingHand != Hand.NONE) {
            String prefix = state.swingingHand == Hand.MAIN ? "swing" : "swing_offhand";
            String name = state.item(state.swingingHand).empty ? "" : catalog.condition(prefix, state.item(state.swingingHand));
            if (name.isEmpty()) name = state.swingingHand == Hand.MAIN ? catalog.first("swing_hand","attack_empty") : catalog.first("swing_offhand");
            swing = playAnimationWithValid(catalog, name, "ONCE", state.swingingHand + ":" + state.item(state.swingingHand).eventKey());
        }
        if (!state.sleeping && state.usingHand != Hand.NONE) {
            String prefix = state.usingHand == Hand.MAIN ? "use_mainhand" : "use_offhand";
            String name = state.item(state.usingHand).empty ? "" : catalog.condition(prefix, state.item(state.usingHand));
            if (name.isEmpty()) name = catalog.first(prefix);
            use = playAnimationWithValid(catalog, name, "LOOP", state.usingHand + ":" + state.item(state.usingHand).eventKey());
        }
        result.put("player.swing", swing); result.put("player.use", use);
        // Armor and passenger predicates have independent native slots and force LOOP in both mature projects.
        for(String slot:List.of("head","chest","legs","feet")) {
            String name=catalog.armor(slot,state.armor.getOrDefault(slot,ItemState.EMPTY));
            result.put("player.armor_"+slot,name.isEmpty()?Selection.STOP:new Selection(Directive.PLAY,name,"LOOP",state.armor.get(slot).eventKey()));
        }
        String passenger=catalog.passenger(state);
        result.put("player.passenger",passenger.isEmpty()?Selection.STOP:new Selection(Directive.PLAY,passenger,"LOOP",state.passengerId));
        return new Decision(result);
    }
    private static Selection hold(VanillaState state, Catalog catalog, Hand hand) {
        if (state.usingHand == hand || state.swingingHand == hand) return Selection.PAUSE;
        String prefix = hand == Hand.MAIN ? "hold_mainhand" : "hold_offhand";
        ItemState item = state.item(hand);
        // These special predicates precede user ID/tag/class conditions in OpenYSM.
        String name = item.chargedCrossbow ? catalog.first(prefix + ":charged_crossbow")
                : hand == Hand.MAIN && state.fishing ? catalog.first(prefix + ":fishing")
                : catalog.condition(prefix, item);
        return playAnimationWithValid(catalog, name, "LOOP", item.eventKey());
    }
    /** Port of IAnimationPredicate.playAnimationWithValid: an accepted format has no loop override. */
    private static Selection playAnimationWithValid(Catalog catalog, String name, String fallbackLoop, String key) {
        if (name.isEmpty()) return Selection.STOP;
        String loop = AnimationFormatValidator.validate(catalog.formatVersion(), catalog.fromPrimaryAssembly(name))
                ? catalog.authoredLoop(name) : fallbackLoop;
        return new Selection(Directive.PLAY, name, loop, key);
    }

    /**
     * Shared body/first-person request clocks adapted from Main/OffHandHoldPredicate
     * and AnimationControllerInstance.setAnimation/stopTransition (OpenYSM 0306e1fa, MIT).
     * PAUSE/CONTINUE callers retain the previous layer and do not reset this clock.
     */
    public static final class HandPlayback {
        private record Request(String animation, String loop, long started, long nativeEvent) { }
        private final Map<String,Request> requests = new HashMap<>();
        private final Map<String,ItemState> heldItems = new HashMap<>();

        /** Source hold predicates compare/record equipment before looking up a matching animation. */
        public void observe(String slot, Selection selection, VanillaState state) {
            if (selection.directive() == Directive.PAUSE || selection.directive() == Directive.CONTINUE) return;
            Hand hand = switch (slot) {
                case "player.hold_mainhand" -> Hand.MAIN;
                case "player.hold_offhand" -> Hand.OFF;
                default -> Hand.NONE;
            };
            if (hand == Hand.NONE) return;
            ItemState item = state.item(hand);
            // These source predicates return before isSameItem/stopTransition.
            boolean special = item.chargedCrossbow() || hand == Hand.MAIN && state.fishing();
            if (!special && !isSameItem(item, heldItems.get(slot))) {
                heldItems.put(slot, item);
                stop(slot);
            }
        }
        public long start(String slot, Selection selection, VanillaState state, long tick) {
            if (selection.directive() != Directive.PLAY) throw new IllegalArgumentException("Hand playback request");
            if (slot.equals("player.swing") || slot.equals("player.use")) {
                if(slot.equals("player.swing")&&state.swingSequence()>0) {
                    Request previous=requests.get(slot);
                    if(previous!=null&&previous.nativeEvent()==state.swingSequence()&&previous.animation().equals(selection.animation())&&previous.loop().equals(selection.loop()))
                        return previous.started();
                    requests.put(slot,new Request(selection.animation(),selection.loop(),tick,state.swingSequence()));
                    return tick;
                }
                long started = slot.equals("player.swing") ? tick - state.swingTicks()
                        : tick - Math.max(0, state.useTicks() - 1L);
                requests.put(slot, new Request(selection.animation(), selection.loop(), started,0));
                return started;
            }
            if (!slot.equals("player.hold_mainhand") && !slot.equals("player.hold_offhand")
                    && !slot.startsWith("player.armor_") && !slot.equals("player.passenger"))
                throw new IllegalArgumentException("Hand controller slot");
            observe(slot, selection, state);
            Request previous = requests.get(slot);
            if (previous != null && previous.animation.equals(selection.animation()) && previous.loop.equals(selection.loop()))
                return previous.started;
            requests.put(slot, new Request(selection.animation(), selection.loop(), tick,0));
            return tick;
        }
        public void stop(String slot) { requests.remove(slot); }
        public void reset() { requests.clear(); heldItems.clear(); }

        /** The source compares only item type when its previously tracked stack is damaged. */
        private static boolean isSameItem(ItemState item, ItemState previous) {
            boolean previousDamaged = previous != null && (previous.comparisonKey() instanceof TrackedItemComparison tracked
                    ? tracked.isDamaged() : previous.damaged());
            return previous != null && item.id().equals(previous.id())
                    && (previousDamaged || Objects.equals(item.comparisonKey(), previous.comparisonKey()));
        }
    }
    private static boolean resourceId(String id) {
        return id != null && id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }
    private static Set<String> checkedTags(Set<String> tags) {
        Objects.requireNonNull(tags);
        if (tags.size() > 256 || tags.stream().anyMatch(tag -> !resourceId(tag))) throw new IllegalArgumentException("Vanilla tags");
        return Set.copyOf(tags);
    }
}
