package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class StateSelectorTest {
    private static final Predicate<SyncFeature> ALL_ENABLED = ignored -> true;

    @Test
    void realPosturesWinWhenNativeFlagsAlsoDescribeFlightOrSwimming() {
        // GSit crawling uses the swimming pose, so its session must be authoritative.
        var sitting = new StateSelector.Sample(true, true, true, true, true,
                true, false, false, false, true);
        var crawlMoving = new StateSelector.Sample(false, true, true, true, true,
                true, false, false, false, true);
        var crawlStill = new StateSelector.Sample(false, true, false, false, true,
                false, true, false, false, false);

        assertAll(
                () -> assertEquals(ActionState.SIT, StateSelector.select(sitting, ALL_ENABLED)),
                () -> assertEquals(ActionState.CRAWL_WALK, StateSelector.select(crawlMoving, ALL_ENABLED)),
                () -> assertEquals(ActionState.CRAWL_IDLE, StateSelector.select(crawlStill, ALL_ENABLED))
        );
    }

    @Test
    void disablingRealPostureSynchronizationDoesNotFallThroughToSwimming() {
        var sittingInWater = new StateSelector.Sample(true, false, false, false, true,
                true, false, false, false, true);
        var crawlingWithSwimmingPose = new StateSelector.Sample(false, true, false, false, true,
                false, true, false, false, true);

        assertAll(
                () -> assertNull(StateSelector.select(sittingInWater, except(SyncFeature.SIT))),
                () -> assertNull(StateSelector.select(crawlingWithSwimmingPose, except(SyncFeature.CRAWL)))
        );
    }

    @Test
    void elytraAndActualFlightHaveIndependentSwitchesAndFlightUsesHoverWhenStill() {
        var gliding = new StateSelector.Sample(false, false, true, true, false,
                false, false, false, false, true);
        var flying = new StateSelector.Sample(false, false, false, true, false,
                false, false, false, false, true);
        var hovering = new StateSelector.Sample(false, false, false, true, false,
                false, false, false, false, false);

        assertAll(
                () -> assertEquals(ActionState.ELYTRA, StateSelector.select(gliding, except(SyncFeature.FLIGHT))),
                () -> assertNull(StateSelector.select(gliding, except(SyncFeature.ELYTRA))),
                () -> assertEquals(ActionState.FLY, StateSelector.select(flying, except(SyncFeature.ELYTRA))),
                () -> assertEquals(ActionState.HOVER, StateSelector.select(hovering, ALL_ENABLED)),
                () -> assertNull(StateSelector.select(flying, except(SyncFeature.FLIGHT)))
        );
    }

    @Test
    void disablingSprintKeepsWalkingAndDisablingMovementRemovesThatLayer() {
        var sprinting = new StateSelector.Sample(false, false, false, false, false,
                false, true, false, true, true);

        assertAll(
                () -> assertEquals(ActionState.RUN, StateSelector.select(sprinting, ALL_ENABLED)),
                () -> assertEquals(ActionState.WALK, StateSelector.select(sprinting, except(SyncFeature.SPRINT))),
                () -> assertNull(StateSelector.select(sprinting, except(SyncFeature.MOVEMENT)))
        );
    }

    @Test void sleepAndVehiclesNeverFallThroughToJumpOrWalkWhenDisabled() {
        var sleeping = new StateSelector.Sample(true, true, false, false, false,
                false, false, false, false, true, true, StateSelector.Vehicle.OTHER);
        assertEquals(ActionState.SLEEP, StateSelector.select(sleeping, ALL_ENABLED));
        assertNull(StateSelector.select(sleeping, except(SyncFeature.SLEEP)));
        assertEquals("sleep", sleeping.postureKey());
        for (var vehicle : new StateSelector.Vehicle[]{StateSelector.Vehicle.BOAT, StateSelector.Vehicle.MINECART, StateSelector.Vehicle.OTHER}) {
            var riding = new StateSelector.Sample(false, false, false, false, false,
                    false, false, false, true, true, false, vehicle);
            var expected = vehicle == StateSelector.Vehicle.BOAT ? ActionState.BOAT
                    : vehicle == StateSelector.Vehicle.MINECART ? ActionState.MINECART : ActionState.RIDE;
            assertEquals(expected, StateSelector.select(riding, ALL_ENABLED));
            assertNull(StateSelector.select(riding, except(SyncFeature.RIDE)));
        }
    }
    @Test void nativeBedIsDistinctFromGsitSleepAndKeepsSleepSynchronization() {
        var bed = new StateSelector.Sample(false, false, false, false, false,
                false, false, false, false, false, true, true, StateSelector.Vehicle.NONE);
        var lay = new StateSelector.Sample(false, false, false, false, false,
                false, false, false, false, false, true, StateSelector.Vehicle.NONE);
        assertEquals(ActionState.BED_SLEEP, StateSelector.select(bed, ALL_ENABLED, ActionState.JUMP));
        assertEquals("bed-sleep", bed.postureKey());
        assertEquals(ActionState.SLEEP, StateSelector.select(lay, ALL_ENABLED, ActionState.JUMP));
        assertNull(StateSelector.select(bed, except(SyncFeature.SLEEP), ActionState.JUMP));
    }

    @Test void horizontalSwimmingAndUprightTreadingUseDifferentAnimations() {
        var horizontal = new StateSelector.Sample(false, false, false, false, true,
                true, false, false, false, false);
        var swimming = new StateSelector.Sample(false, false, false, false, true,
                true, false, false, false, true);
        var treading = new StateSelector.Sample(false, false, false, false, false,
                true, false, false, false, true);
        assertEquals(ActionState.SWIM_PRONE_IDLE, StateSelector.select(horizontal, ALL_ENABLED, ActionState.JUMP));
        assertEquals(ActionState.SWIM_WALK, StateSelector.select(swimming, ALL_ENABLED, ActionState.FALL));
        assertEquals(ActionState.SWIM_IDLE, StateSelector.select(treading, ALL_ENABLED, ActionState.FALL));
        assertNotEquals(horizontal.postureKey(), treading.postureKey());
        assertNull(StateSelector.select(horizontal, except(SyncFeature.SWIM), ActionState.JUMP));
    }

    @Test void landingTailWinsOverMovementButNotARealPosture() {
        var grounded = new StateSelector.Sample(false, false, false, false, false,
                false, true, true, true, true);
        assertEquals(ActionState.JUMP, StateSelector.select(grounded, ALL_ENABLED, ActionState.JUMP));
        assertEquals(ActionState.CROUCH_WALK, StateSelector.select(grounded, ALL_ENABLED, null));
        var falling = new StateSelector.Sample(false, false, false, false, false,
                false, false, false, false, true);
        assertEquals(ActionState.FALL, StateSelector.select(falling, ALL_ENABLED, ActionState.FALL));
        var crawling = new StateSelector.Sample(false, true, false, false, false,
                false, true, false, false, false);
        assertEquals(ActionState.CRAWL_IDLE, StateSelector.select(crawling, ALL_ENABLED, ActionState.JUMP));
    }

    private static Predicate<SyncFeature> except(SyncFeature feature) {
        EnumSet<SyncFeature> enabled = EnumSet.allOf(SyncFeature.class);
        enabled.remove(feature);
        return enabled::contains;
    }
}
