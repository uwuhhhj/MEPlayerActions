package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class StateSelectorTest
{
    private static final Predicate<SyncFeature> ALL_ENABLED = ignored -> true;

    @Test
    void realPosturesWinWhenNativeFlagsAlsoDescribeFlightOrSwimming()
    {
        // GSit crawling uses the swimming pose, so its session must be authoritative.
        StateSelector.Sample sitting = new StateSelector.Sample(true, true, true, true, true, true, false, false, false,
                true);
        StateSelector.Sample crawlMoving = new StateSelector.Sample(false, true, true, true, true, true, false, false,
                false, true);
        StateSelector.Sample crawlStill = new StateSelector.Sample(false, true, false, false, true, false, true, false,
                false, false);

        assertAll(() -> assertEquals(ActionState.SIT, StateSelector.select(sitting, ALL_ENABLED)),
                () -> assertEquals(ActionState.CRAWL_WALK, StateSelector.select(crawlMoving, ALL_ENABLED)),
                () -> assertEquals(ActionState.CRAWL_IDLE, StateSelector.select(crawlStill, ALL_ENABLED)));
    }

    @Test
    void disablingRealPostureSynchronizationDoesNotFallThroughToSwimming()
    {
        StateSelector.Sample sittingInWater = new StateSelector.Sample(true, false, false, false, true, true, false,
                false, false, true);
        StateSelector.Sample crawlingWithSwimmingPose = new StateSelector.Sample(false, true, false, false, true, false,
                true, false, false, true);

        assertAll(() -> assertNull(StateSelector.select(sittingInWater, except(SyncFeature.SIT))),
                () -> assertNull(StateSelector.select(crawlingWithSwimmingPose, except(SyncFeature.CRAWL))));
    }

    @Test
    void elytraAndActualFlightHaveIndependentSwitchesAndFlightUsesHoverWhenStill()
    {
        StateSelector.Sample gliding = new StateSelector.Sample(false, false, true, true, false, false, false, false,
                false, true);
        StateSelector.Sample flying = new StateSelector.Sample(false, false, false, true, false, false, false, false,
                false, true);
        StateSelector.Sample hovering = new StateSelector.Sample(false, false, false, true, false, false, false, false,
                false, false);

        assertAll(() -> assertEquals(ActionState.ELYTRA, StateSelector.select(gliding, except(SyncFeature.FLIGHT))),
                () -> assertNull(StateSelector.select(gliding, except(SyncFeature.ELYTRA))),
                () -> assertEquals(ActionState.FLY, StateSelector.select(flying, except(SyncFeature.ELYTRA))),
                () -> assertEquals(ActionState.HOVER, StateSelector.select(hovering, ALL_ENABLED)),
                () -> assertNull(StateSelector.select(flying, except(SyncFeature.FLIGHT))));
    }

    @Test
    void disablingSprintKeepsWalkingAndDisablingMovementRemovesThatLayer()
    {
        StateSelector.Sample sprinting = new StateSelector.Sample(false, false, false, false, false, false, true, false,
                true, true);

        assertAll(() -> assertEquals(ActionState.RUN, StateSelector.select(sprinting, ALL_ENABLED)),
                () -> assertEquals(ActionState.WALK, StateSelector.select(sprinting, except(SyncFeature.SPRINT))),
                () -> assertNull(StateSelector.select(sprinting, except(SyncFeature.MOVEMENT))));
    }

    @Test
    void sleepAndVehiclesNeverFallThroughToJumpOrWalkWhenDisabled()
    {
        StateSelector.Sample sleeping = new StateSelector.Sample(true, true, false, false, false, false, false, false,
                false, true, true, StateSelector.Vehicle.OTHER);
        assertEquals(ActionState.SLEEP, StateSelector.select(sleeping, ALL_ENABLED));
        assertNull(StateSelector.select(sleeping, except(SyncFeature.SLEEP)));
        assertEquals("sleep", sleeping.postureKey());
        for (StateSelector.Vehicle vehicle : new StateSelector.Vehicle[]
        { StateSelector.Vehicle.BOAT, StateSelector.Vehicle.MINECART, StateSelector.Vehicle.OTHER })
        {
            StateSelector.Sample riding = new StateSelector.Sample(false, false, false, false, false, false, false,
                    false, true, true, false, vehicle);
            ActionState expected = vehicle == StateSelector.Vehicle.BOAT ? ActionState.BOAT
                    : (vehicle == StateSelector.Vehicle.MINECART ? ActionState.MINECART : ActionState.RIDE);
            assertEquals(expected, StateSelector.select(riding, ALL_ENABLED));
            assertNull(StateSelector.select(riding, except(SyncFeature.RIDE)));
        }
    }

    @Test
    void nativeBedIsDistinctFromGsitSleepAndKeepsSleepSynchronization()
    {
        StateSelector.Sample bed = new StateSelector.Sample(false, false, false, false, false, false, false, false,
                false, false, true, true, StateSelector.Vehicle.NONE);
        StateSelector.Sample lay = new StateSelector.Sample(false, false, false, false, false, false, false, false,
                false, false, true, StateSelector.Vehicle.NONE);
        assertEquals(ActionState.BED_SLEEP, StateSelector.select(bed, ALL_ENABLED, ActionState.JUMP));
        assertEquals("bed-sleep", bed.postureKey());
        assertEquals(ActionState.SLEEP, StateSelector.select(lay, ALL_ENABLED, ActionState.JUMP));
        assertNull(StateSelector.select(bed, except(SyncFeature.SLEEP), ActionState.JUMP));
    }

    @Test
    void horizontalSwimmingAndUprightTreadingUseDifferentAnimations()
    {
        StateSelector.Sample horizontal = new StateSelector.Sample(false, false, false, false, true, true, false, false,
                false, false);
        StateSelector.Sample swimming = new StateSelector.Sample(false, false, false, false, true, true, false, false,
                false, true);
        StateSelector.Sample treading = new StateSelector.Sample(false, false, false, false, false, true, false, false,
                false, true);
        assertEquals(ActionState.SWIM_PRONE_IDLE, StateSelector.select(horizontal, ALL_ENABLED, ActionState.JUMP));
        assertEquals(ActionState.SWIM_WALK, StateSelector.select(swimming, ALL_ENABLED, ActionState.FALL));
        assertEquals(ActionState.SWIM_IDLE, StateSelector.select(treading, ALL_ENABLED, ActionState.FALL));
        assertNotEquals(horizontal.postureKey(), treading.postureKey());
        assertNull(StateSelector.select(horizontal, except(SyncFeature.SWIM), ActionState.JUMP));
    }

    @Test
    void landingTailWinsOverMovementButNotARealPosture()
    {
        StateSelector.Sample grounded = new StateSelector.Sample(false, false, false, false, false, false, true, true,
                true, true);
        assertEquals(ActionState.JUMP, StateSelector.select(grounded, ALL_ENABLED, ActionState.JUMP));
        assertEquals(ActionState.CROUCH_WALK, StateSelector.select(grounded, ALL_ENABLED, null));
        StateSelector.Sample falling = new StateSelector.Sample(false, false, false, false, false, false, false, false,
                false, true);
        assertEquals(ActionState.FALL, StateSelector.select(falling, ALL_ENABLED, ActionState.FALL));
        StateSelector.Sample crawling = new StateSelector.Sample(false, true, false, false, false, false, true, false,
                false, false);
        assertEquals(ActionState.CRAWL_IDLE, StateSelector.select(crawling, ALL_ENABLED, ActionState.JUMP));
    }

    private static Predicate<SyncFeature> except(SyncFeature feature)
    {
        EnumSet<SyncFeature> enabled = EnumSet.allOf(SyncFeature.class);
        enabled.remove(feature);
        return enabled::contains;
    }
}
