package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisualTimelineTest {
    private final UUID world = UUID.randomUUID();
    private VisualTimeline.Frame frame(long tick, double x, double y, boolean ground, boolean crawl, ActionState air) {
        var pose = new StateSelector.Sample(false, crawl, false, false, false, false, ground, false, false, true);
        return new VisualTimeline.Frame(tick, world, x, y, 0, pose, air, 1);
    }
    @Test void positionAndAirPoseUseTheSameDelayedFrame() {
        var t = new VisualTimeline(); t.sample(frame(0, 0, 0, true, false, null), 2, 2, true);
        t.sample(frame(1, 0.1, 0.4, false, false, ActionState.JUMP), 2, 2, true);
        t.sample(frame(2, 0.2, 0.7, false, false, ActionState.JUMP), 2, 2, true);
        var shown = t.sample(frame(3, 0.3, 0, true, false, null), 2, 2, true);
        assertEquals(0.4, shown.y()); assertEquals(ActionState.JUMP, shown.air()); assertFalse(shown.pose().grounded());
    }
    @Test void crawlingAndTeleportSnapImmediatelyAndZeroDisablesDelay() {
        var t = new VisualTimeline(); t.sample(frame(0, 0, 0, true, false, null), 4, 2, true);
        assertTrue(t.sample(frame(1, 0.1, 0, true, true, null), 4, 2, true).pose().crawling());
        assertEquals(100, t.sample(frame(2, 100, 0, true, false, null), 4, 2, true).x());
        assertEquals(100.2, t.sample(frame(3, 100.2, 0, true, false, null), 0, 2, true).x());
    }
    @Test void worldChangesAndTooMuchAccumulatedLagClearHistory() {
        var t = new VisualTimeline(); t.sample(frame(0, 0, 0, true, false, null), 20, 1, true);
        t.sample(frame(1, 0.6, 0, true, false, null), 20, 1, true);
        assertEquals(1.2, t.sample(frame(2, 1.2, 0, true, false, null), 20, 1, true).x());
        var f = frame(3, 0, 0, true, false, null);
        var other = new VisualTimeline.Frame(3, UUID.randomUUID(), 0, 0, 0, f.pose(), null, 0);
        assertEquals(other, t.sample(other, 20, 1, true));
    }
    @Test void leavingABedRetainsItsSampledSurfaceAndDirectionUntilTheDelayedPoseChanges() {
        var bedPose = new StateSelector.Sample(false, false, false, false, false, false,
                false, false, false, false, true, true, StateSelector.Vehicle.NONE);
        var bed = new VisualTimeline.Frame(0, world, 3, -59.4375, 2.5, bedPose, null, 0, -90, -90, 0);
        var awakePose = new StateSelector.Sample(false, false, false, false, false, false, true, false, false, false);
        var t = new VisualTimeline(); t.sample(bed, 2, 8, false);
        var awake = new VisualTimeline.Frame(1, world, 2.5, -60, 2.5, awakePose, null, 0, 35, 40, 20);
        var shown = t.sample(awake, 2, 8, false);
        assertTrue(shown.pose().bedSleeping()); assertEquals(-59.4375, shown.y());
        assertEquals(3, shown.x()); assertEquals(-90, shown.bodyYaw()); assertEquals(shown.bodyYaw(), shown.headYaw());
        assertEquals(0, shown.headPitch());
        assertEquals(awake, t.sample(new VisualTimeline.Frame(3, world, 2.5, -60, 2.5,
                awakePose, null, 0, 35, 40, 20), 2, 8, false));
    }
}
