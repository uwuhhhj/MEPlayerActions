package com.simmc.meplayeractions.client;

import java.util.*;

/** Vanilla entity snapshots and the built-in YSM hand/vehicle predicates. No client input or MC classes. */
public final class VanillaYsmAnimations {
    public enum Hand { NONE, MAIN, OFF }
    public enum Directive { PLAY, PAUSE, CONTINUE, STOP }
    public record ItemState(String id, Set<String> tags, String kind, String useAction,
                            boolean empty, boolean chargedCrossbow, long revision) {
        public static final ItemState EMPTY = new ItemState("minecraft:air", Set.of(), "", "none", true, false, 0);
        public ItemState {
            if (!resourceId(id) || kind == null || !kind.matches("[a-z0-9_]{0,64}")
                    || useAction == null || !useAction.matches("[a-z0-9_]{1,32}"))
                throw new IllegalArgumentException("Vanilla held item");
            tags = checkedTags(tags);
        }
        String eventKey() { return id + ":" + revision; }
    }
    public record VanillaState(boolean dead, int hurtTime, boolean riptide, boolean sleeping, boolean swimming,
                               ItemState mainhand, ItemState offhand, Hand usingHand, int useTicks,
                               Hand swingingHand, int swingTicks, boolean fishing, String vehicleId,
                               Set<String> vehicleTags, boolean vehicleAlive, boolean vehicleSaddled) {
        public static final VanillaState NONE = new VanillaState(false, 0, false, false, false,
                ItemState.EMPTY, ItemState.EMPTY, Hand.NONE, 0, Hand.NONE, 0, false, "", Set.of(), false, false);
        public VanillaState {
            Objects.requireNonNull(mainhand); Objects.requireNonNull(offhand);
            Objects.requireNonNull(usingHand); Objects.requireNonNull(swingingHand);
            if (hurtTime < 0 || hurtTime > 100_000 || useTicks < 0 || swingTicks < 0
                    || vehicleId == null || !vehicleId.isEmpty() && !resourceId(vehicleId))
                throw new IllegalArgumentException("Vanilla entity state");
            vehicleTags = checkedTags(vehicleTags);
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
        public Catalog(Collection<String> animations) {
            Objects.requireNonNull(animations);
            if (animations.size() > 128) throw new IllegalArgumentException("Animation inventory");
            var copy = new LinkedHashSet<String>();
            for (String name : animations) {
                if (name == null || name.length() > 128 || name.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Animation name");
                copy.add(name);
            }
            names = Collections.unmodifiableSet(copy);
        }
        public boolean contains(String animation) { return names.contains(animation); }
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
            if (name.isEmpty()) name = catalog.first(state.swingingHand == Hand.MAIN ? "swing_hand" : "swing_offhand");
            swing = play(name, "ONCE", state.swingingHand + ":" + state.item(state.swingingHand).eventKey());
        }
        if (!state.sleeping && state.usingHand != Hand.NONE) {
            String prefix = state.usingHand == Hand.MAIN ? "use_mainhand" : "use_offhand";
            String name = state.item(state.usingHand).empty ? "" : catalog.condition(prefix, state.item(state.usingHand));
            if (name.isEmpty()) name = catalog.first(prefix);
            use = play(name, "LOOP", state.usingHand + ":" + state.item(state.usingHand).eventKey());
        }
        result.put("player.swing", swing); result.put("player.use", use);
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
        return play(name, "LOOP", item.eventKey());
    }
    private static Selection play(String name, String loop, String key) {
        return name.isEmpty() ? Selection.STOP : new Selection(Directive.PLAY, name, loop, key);
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
