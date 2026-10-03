package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.VanillaYsmAnimations.*;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BbModel.Layer;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions for the original predicate/format/request semantics, including the actual bundled arm clip. */
class OpenYsmHoldMigrationTest {
    private static final String MAIN = "player.hold_mainhand", OFF = "player.hold_offhand";
    private static final LocalMotionPolicy POLICY = new LocalMotionPolicy(Set.of("swing"), Map.of(), 17, 8, .02,
            true, true, false, "", "", "", 0, 0, 0, 0);

    @Test void modernAndPrimaryAnimationsKeepTheirAuthoredLoopWhileOldSecondaryKeepsPredicateFallback() {
        for (String loop : List.of("ONCE", "HOLD", "LOOP")) {
            for (int version : List.of(18, 19, 65535)) for (boolean primary : List.of(false, true)) {
                Catalog catalog = catalog(version, primary, Map.of("hold_mainhand:sword", loop,
                        "hold_offhand:shield", loop, "use_offhand:block", loop, "swing:sword", loop));
                boolean authored = version >= 19 || primary;
                var held = VanillaYsmAnimations.select(state(sword(1), shield(1), Hand.NONE, Hand.NONE), catalog).slots();
                assertEquals(authored ? loop : "LOOP", held.get(MAIN).loop());
                assertEquals(authored ? loop : "LOOP", held.get(OFF).loop());
                var active = VanillaYsmAnimations.select(state(sword(1), shield(1), Hand.OFF, Hand.MAIN), catalog).slots();
                assertEquals(authored ? loop : "LOOP", active.get("player.use").loop());
                assertEquals(authored ? loop : "ONCE", active.get("player.swing").loop());
            }
        }
    }

    @Test void rawFolderUsesInternalVersion65535AndActualDefaultSwordRetainsItsAuthoredHold() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        assertEquals(65535, model.animationFormatVersion());
        assertTrue(model.animationFromPrimaryAssembly("hold_mainhand:sword"));
        assertEquals("HOLD", model.animationLoop("hold_mainhand:sword"));
        assertEquals("HOLD", model.animationLoop("hold_offhand:sword"));
        Decision selected = VanillaYsmAnimations.select(state(sword(1), sword(2), Hand.NONE, Hand.NONE), model.animationCatalog());
        assertEquals(model.animationLoop("hold_mainhand:sword"), selected.slots().get(MAIN).loop());
        assertEquals(model.animationLoop("hold_offhand:sword"), selected.slots().get(OFF).loop());
    }

    @Test void realBundledSwordKeepsAuthoredFinalPosePastMultipleLengthsWithoutRestarting() throws Exception {
        BbModel model = BbModel.parse(YsmFolderModel.bundledDefault());
        var motion = new EntityAnimationController(); var player = new AnimationPlayer(model);
        Matrix4f finalArm = null; long started = -1;
        int finalTick = 100 + (int) Math.ceil(model.animationLengthTicks("hold_mainhand:sword")) + 5;
        for (int tick = 100; tick <= finalTick + 40; tick++) {
            motion.update(tick, sample(state(sword(1), ItemState.EMPTY, Hand.NONE, Hand.NONE)), POLICY, List.of(), model.animationCatalog());
            Layer held = hold(motion, MAIN);
            if (tick == 100) started = held.startedAtTick();
            assertEquals(started, held.startedAtTick()); assertEquals("HOLD", held.loop());
            player.sample(tick, motion.layers());
            // Parent-relative arm matrices isolate the actual hold from unrelated body/physics controllers.
            Matrix4f arm = new Matrix4f(player.boneTransform("Arm").orElseThrow()).invert()
                    .mul(player.boneTransform("RightArm").orElseThrow());
            if (tick == finalTick) finalArm = arm;
            if (tick > finalTick) assertTrue(finalArm.equals(arm, 1e-4f), "Authored last arm pose rewound at tick " + tick);
        }
    }

    @Test void sameItemRequestsAndPauseKeepTheStartButAnActualWeaponSwitchRestartsExactlyOnce() {
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD", "hold_mainhand:empty", "LOOP"));
        var motion = new EntityAnimationController();
        update(motion, 100, state(sword(1), ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        Layer first = hold(motion, MAIN);
        for (int tick = 101; tick < 130; tick++) {
            update(motion, tick, state(sword(1), ItemState.EMPTY, Hand.NONE, tick == 104 ? Hand.MAIN : Hand.NONE), catalog);
            assertEquals(first, hold(motion, MAIN));
        }
        update(motion, 130, state(ItemState.EMPTY, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        assertEquals("hold_mainhand:empty", hold(motion, MAIN).animation());
        update(motion, 131, state(sword(2), ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        Layer switched = hold(motion, MAIN); assertEquals(131, switched.startedAtTick());
        for (int tick = 132; tick < 155; tick++) {
            update(motion, tick, state(sword(2), ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
            assertEquals(switched, hold(motion, MAIN));
        }
    }

    @Test void damagedTrackedWeaponsIgnoreComponentChangesButDifferentItemTypesStillRestart() {
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD"));
        var motion = new EntityAnimationController();
        ItemState damaged = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 1, true, "damage-1");
        update(motion, 100, state(damaged, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        Layer first = hold(motion, MAIN);
        for (int tick = 101; tick < 130; tick++) {
            ItemState wear = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, tick, true, "damage-" + tick);
            update(motion, tick, state(wear, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
            assertEquals(first, hold(motion, MAIN));
        }
        ItemState changed = new ItemState("minecraft:iron_sword", Set.of(), "sword", "none", false, false, 1, true, "damage-1");
        update(motion, 130, state(changed, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        assertEquals(130, hold(motion, MAIN).startedAtTick());
    }

    @Test void intactComponentAndCountChangesRestartEvenWhenTheirDiagnosticHashesCollide() {
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD"));
        var motion = new EntityAnimationController();
        for (int tick = 100; tick <= 102; tick++) {
            // The production key compares native stacks; this equal-hash fixture protects that boundary.
            ItemState item = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 7, false,
                    tick == 100 ? List.of("normal", 1) : List.of("enchanted", 2));
            update(motion, tick, state(item, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
            assertEquals(tick == 100 ? 100 : 101, hold(motion, MAIN).startedAtTick());
        }
    }

    @Test void firstDurabilityChangeReadsThePreviouslyTrackedNativeStackDamageWithoutRestarting() {
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD"));
        var motion = new EntityAnimationController();
        final class NativeStackKey implements TrackedItemComparison {
            final String snapshot; final boolean[] liveDamage;
            NativeStackKey(String snapshot, boolean[] liveDamage) { this.snapshot = snapshot; this.liveDamage = liveDamage; }
            public boolean isDamaged() { return liveDamage[0]; }
            @Override public boolean equals(Object other) { return other instanceof NativeStackKey key && snapshot.equals(key.snapshot); }
            @Override public int hashCode() { return snapshot.hashCode(); }
        }
        boolean[] liveDamage = { false };
        ItemState fresh = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 1, false,
                new NativeStackKey("damage-0", liveDamage));
        update(motion, 100, state(fresh, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        Layer original = hold(motion, MAIN);
        liveDamage[0] = true; // The source's saved ItemStack reference observes its first in-place durability change.
        ItemState firstWear = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 2, true,
                new NativeStackKey("damage-1", liveDamage));
        update(motion, 101, state(firstWear, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        assertEquals(original, hold(motion, MAIN));
        // A separate intact tracked stack still uses its full native state for future comparisons.
        update(motion, 102, state(ItemState.EMPTY, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        ItemState replacement = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 3, false,
                new NativeStackKey("replacement", new boolean[] { false }));
        update(motion, 103, state(replacement, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        assertEquals(103, hold(motion, MAIN).startedAtTick());
        ItemState enchanted = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 4, false,
                new NativeStackKey("replacement-enchanted", new boolean[] { false }));
        update(motion, 104, state(enchanted, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog);
        assertEquals(104, hold(motion, MAIN).startedAtTick());
    }

    @Test void inPlaceTrackedStackChangesDoNotRestartButReplacementStacksWithDifferentComponentsDo() {
        final class NativeStack {
            String components; int count;
            NativeStack(String components, int count) { this.components = components; this.count = count; }
        }
        final class Comparison implements TrackedItemComparison {
            final NativeStack tracked;
            Comparison(NativeStack tracked) { this.tracked = tracked; }
            public boolean isDamaged() { return false; }
            @Override public boolean equals(Object other) {
                return other instanceof Comparison key && tracked.count == key.tracked.count && tracked.components.equals(key.tracked.components);
            }
            @Override public int hashCode() { return Objects.hash(tracked.components, tracked.count); }
        }
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD"));
        var motion = new EntityAnimationController(); NativeStack tracked = new NativeStack("normal", 1);
        ItemState initial = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 1, false, new Comparison(tracked));
        update(motion, 100, state(initial, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog); Layer original = hold(motion, MAIN);
        tracked.components = "enchanted"; tracked.count = 2;
        ItemState inPlace = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 2, false, new Comparison(tracked));
        update(motion, 101, state(inPlace, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog); assertEquals(original, hold(motion, MAIN));
        ItemState replacement = new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, 2, false,
                new Comparison(new NativeStack("different-enchantment", 1)));
        update(motion, 102, state(replacement, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog); assertEquals(102, hold(motion, MAIN).startedAtTick());
        update(motion, 103, state(replacement, ItemState.EMPTY, Hand.NONE, Hand.NONE), catalog); assertEquals(102, hold(motion, MAIN).startedAtTick());
    }

    @Test void authoredOnceDoesNotRestartAfterFinishingAndChangedRequestedLoopStartsANewRequest() throws Exception {
        // Exercise the actual converted skeleton/track with an authored ONCE variant and an observable start timeline.
        JsonObject raw = JsonParser.parseString(new String(YsmFolderModel.bundledDefault(), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement element : raw.getAsJsonArray("animations")) {
            JsonObject clip = element.getAsJsonObject();
            if (clip.get("name").getAsString().equals("hold_mainhand:sword")) {
                clip.addProperty("loop", "ONCE");
                clip.getAsJsonObject("animators").add("hold-start-proof", JsonParser.parseString("""
                        {"type":"effect","keyframes":[{"channel":"timeline","time":0,
                        "data_points":[{"script":"v.hold_request_starts+=1;"}]}]}
                        """));
            }
        }
        BbModel model = BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8));
        var motion = new EntityAnimationController(); var player = new AnimationPlayer(model);
        Catalog once = model.animationCatalog();
        for (int tick = 100; tick < 160; tick++) {
            update(motion, tick, state(sword(1), ItemState.EMPTY, Hand.NONE, Hand.NONE), once);
            assertEquals(100, hold(motion, MAIN).startedAtTick()); assertEquals("ONCE", hold(motion, MAIN).loop());
            player.sample(tick, motion.layers());
        }
        assertEquals(1d, player.expressionVariables().get("variable.hold_request_starts"));
        update(motion, 160, state(sword(1), ItemState.EMPTY, Hand.NONE, Hand.NONE),
                catalog(65535, true, Map.of("hold_mainhand:sword", "HOLD")));
        assertEquals(160, hold(motion, MAIN).startedAtTick());
    }

    @Test void chargedAndFishingSpecialPredicatesDoNotResetForItemComponentsAndBothHandsUseTheSharedClock() {
        Catalog catalog = catalog(65535, true, Map.of("hold_mainhand:charged_crossbow", "HOLD",
                "hold_offhand:charged_crossbow", "ONCE", "hold_mainhand:fishing", "LOOP"));
        var body = new HandPlayback(); var firstPerson = new HandPlayback();
        for (int tick = 100; tick < 130; tick++) {
            ItemState crossbow = new ItemState("minecraft:crossbow", Set.of(), "crossbow", "crossbow", false, true, tick);
            VanillaState state = state(crossbow, crossbow, Hand.NONE, Hand.NONE);
            for (var entry : VanillaYsmAnimations.select(state, catalog).slots().entrySet())
                if (entry.getValue().directive() == Directive.PLAY) {
                    assertEquals(100, body.start(entry.getKey(), entry.getValue(), state, tick));
                    assertEquals(100, firstPerson.start(entry.getKey(), entry.getValue(), state, tick));
                }
        }
        for (int tick = 130; tick < 140; tick++) {
            ItemState rod = new ItemState("minecraft:fishing_rod", Set.of(), "fishing_rod", "none", false, false, tick);
            VanillaState state = new VanillaState(false, 0, false, false, false, rod, ItemState.EMPTY,
                    Hand.NONE, 0, Hand.NONE, 0, true, "", Set.of(), false, false);
            Selection selected = VanillaYsmAnimations.select(state, catalog).slots().get(MAIN);
            assertEquals(130, body.start(MAIN, selected, state, tick));
            assertEquals(130, firstPerson.start(MAIN, selected, state, tick));
        }
    }

    @Test void metadataInventoriesRejectUnknownOriginsMissingLoopsAndInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> new Catalog(List.of("a"), 65536, Map.of("a", "HOLD"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Catalog(List.of("a"), 19, Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Catalog(List.of("a"), 19, Map.of("a", "HOLD"), Set.of("b")));
        assertThrows(IllegalArgumentException.class, () -> new Catalog(List.of("a"), 19, Map.of("a", "BAD"), Set.of()));
        assertEquals("LOOP", VanillaYsmAnimations.select(state(sword(1), ItemState.EMPTY, Hand.NONE, Hand.NONE),
                new Catalog(List.of("hold_mainhand:sword"))).slots().get(MAIN).loop());
    }

    private static Catalog catalog(int version, boolean primary, Map<String,String> loops) {
        return new Catalog(loops.keySet(), version, loops, primary ? loops.keySet() : Set.of());
    }
    private static ItemState sword(long revision) {
        return new ItemState("minecraft:diamond_sword", Set.of(), "sword", "none", false, false, revision);
    }
    private static ItemState shield(long revision) {
        return new ItemState("minecraft:shield", Set.of(), "shield", "block", false, false, revision);
    }
    private static VanillaState state(ItemState main, ItemState off, Hand using, Hand swinging) {
        return new VanillaState(false, 0, false, false, false, main, off, using, using == Hand.NONE ? 0 : 1,
                swinging, 0, false, "", Set.of(), false, false);
    }
    private static EntityAnimationController.Sample sample(VanillaState state) {
        return new EntityAnimationController.Sample(0, 64, 0, true, false, false, false, false, false, false, false,
                "", false, 0, false, false, true, false, state);
    }
    private static Layer hold(EntityAnimationController controller, String slot) {
        return controller.layers().stream().filter(layer -> layer.layer().equals(slot)).findFirst().orElseThrow();
    }
    private static void update(EntityAnimationController controller, long tick, VanillaState state, Catalog catalog) {
        controller.update(tick, sample(state), POLICY, List.of(), catalog);
    }
}
