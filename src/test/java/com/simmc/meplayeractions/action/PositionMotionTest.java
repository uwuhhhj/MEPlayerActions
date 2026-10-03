package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PositionMotionTest {
    @Test void actualMovementNeedsNoVelocityAndIrregularSamplesKeepPhysicalUnits() {
        UUID world=UUID.randomUUID();var everyTick=new PositionMotion();var delayed=new PositionMotion();
        assertTrue(everyTick.sample(world,0,64,0,179,100).discontinuity());
        delayed.sample(world,0,64,0,179,100);
        var immediate=everyTick.sample(world,.2,64.1,.15,-179,101);
        var interval=delayed.sample(world,.8,64.4,.6,-173,104);
        assertEquals(.2,immediate.x(),1e-9);assertEquals(.1,immediate.y(),1e-9);
        assertEquals(5,immediate.groundSpeed(),1e-9);assertEquals(2,immediate.verticalSpeed(),1e-9);
        assertEquals(40,immediate.yawSpeed(),1e-9);
        assertEquals(immediate.x(),interval.x(),1e-9);assertEquals(immediate.y(),interval.y(),1e-9);
        assertEquals(immediate.z(),interval.z(),1e-9);assertEquals(immediate.groundSpeed(),interval.groundSpeed(),1e-9);
        assertEquals(immediate.verticalSpeed(),interval.verticalSpeed(),1e-9);assertEquals(immediate.yawSpeed(),interval.yawSpeed(),1e-9);
    }
    @Test void repeatedSameTickCannotEraseMotionOrChangeNextSampleBaseline() {
        UUID world=UUID.randomUUID();var motion=new PositionMotion();motion.sample(world,0,64,0,0,100);
        var moved=motion.sample(world,.2,64.1,0,0,101);
        assertSame(moved,motion.sample(world,.3,64.2,0,0,101));
        assertEquals(.2,motion.sample(world,.4,64.1,0,0,102).x(),1e-9);
    }
    @Test void teleportsWorldChangesClockResetsAndLongPausesCannotBecomeSpringImpulses() {
        UUID world=UUID.randomUUID();var motion=new PositionMotion();motion.sample(world,0,64,0,0,100);
        assertReset(motion.sample(world,4.01,64,0,180,101));
        assertEquals(.1,motion.sample(world,4.11,64,0,180,102).x(),1e-9);
        UUID nextWorld=UUID.randomUUID();assertReset(motion.sample(nextWorld,4.21,64,0,0,103));
        assertReset(motion.sample(nextWorld,4.31,64,0,0,2));
        assertReset(motion.sample(nextWorld,4.41,64,0,0,103));
        assertEquals(.1,motion.sample(nextWorld,4.51,64,0,0,104).x(),1e-9);
        motion.reset();assertReset(motion.sample(nextWorld,5,64,0,0,105));
    }
    private static void assertReset(PositionMotion.Sample sample) {
        assertTrue(sample.discontinuity());assertEquals(0,sample.groundSpeed());assertEquals(0,sample.verticalSpeed());
        assertEquals(0,sample.yawSpeed());assertEquals(0,sample.x());assertEquals(0,sample.y());assertEquals(0,sample.z());
    }
}
