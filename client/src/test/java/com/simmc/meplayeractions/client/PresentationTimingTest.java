package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationTimingTest {
    @Test void interpolatesFeetAndWrappedYawWithoutApplyingPoseOffsets() {
        TransformTimeline timeline=new TransformTimeline();
        timeline.add(new TransformTimeline.Transform(10,1,64,2,179,179,10));
        timeline.add(new TransformTimeline.Transform(12,3,64,4,-179,-179,20));
        var midpoint=timeline.sample(11);
        assertEquals(2,midpoint.x());assertEquals(64,midpoint.y());assertEquals(3,midpoint.z());
        assertEquals(180,Math.abs(midpoint.bodyYaw()),0.001);assertEquals(15,midpoint.headPitch(),0.001);
    }
    @Test void ignoresOlderSamplesSnapsTeleportsAndNeverExtrapolates() {
        TransformTimeline timeline=new TransformTimeline();
        timeline.add(new TransformTimeline.Transform(10,0,64,0,0,0,0));
        assertFalse(timeline.add(new TransformTimeline.Transform(9,-1,64,0,0,0,0)));
        timeline.add(new TransformTimeline.Transform(12,100,80,100,90,90,0));
        assertEquals(100,timeline.sample(10).x());assertEquals(100,timeline.sample(500).x());
        timeline.add(new TransformTimeline.Transform(12,101,80,100,90,90,0));
        assertEquals(101,timeline.sample(12).x());
    }
    @Test void clockAdvancesWithoutAnyClientWorldTicksAndTracksSlowerServers() {
        ServerClock clock=new ServerClock();long start=100_000_000_000L;
        clock.observe(100,start);assertEquals(120,clock.estimate(start+1_000_000_000L),0.001);
        clock.observe(105,start+1_000_000_000L);assertEquals(105,clock.estimate(start+1_000_000_000L),0.001);
        clock.reset();clock.observe(500,start);assertEquals(501,clock.estimate(start+50_000_000L),0.001);
    }
    @Test void jumpLayersFinishAtTheSameBufferedTimeAsTheVisualTrajectory() {
        SnapshotTimeline<String> layers=new SnapshotTimeline<>();layers.add(100,"jump");layers.add(117,"idle");
        assertEquals("jump",layers.sample(116.99));assertEquals("idle",layers.sample(117));
        layers.add(118,"crawl");assertEquals("idle",layers.sample(117.99));
    }
    @Test void unsignedServerTickRolloverKeepsThePresentationTimeContinuous() {
        ServerClock clock=new ServerClock();long now=1_000_000_000L;
        clock.observe(0xffff_fffeL,now);assertEquals(0x1_0000_0001L,clock.unwrap(1));
        clock.observe(1,now+150_000_000L);
        assertEquals(0x1_0000_0001L,clock.estimate(now+150_000_000L),0.001);
    }
}
