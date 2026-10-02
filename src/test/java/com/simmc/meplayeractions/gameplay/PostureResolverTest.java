package com.simmc.meplayeractions.gameplay;

import com.simmc.meplayeractions.action.StateSelector.Vehicle;
import org.bukkit.entity.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostureResolverTest {
    @Test void sleepingAndGSitLayWinOverInvisibleSeatAndSwimming() {
        for (String type : new String[]{"LAY", "LAY_BACK", "LEGS_UP"}) {
            var p = PostureResolver.resolve(true, false, type, false, Pose.STANDING, true, false, false, Vehicle.OTHER);
            assertTrue(p.sleeping()); assertFalse(p.sitting()); assertEquals(Vehicle.NONE, p.vehicle());
        }
        assertTrue(PostureResolver.resolve(false, false, "", true, Pose.STANDING, false, false, false, Vehicle.NONE).sleeping());
        assertTrue(PostureResolver.resolve(false, false, "", false, Pose.SLEEPING, false, false, false, Vehicle.NONE).sleeping());
    }

    @Test void GSitBellyflopAndCrawlWinOverSeatAndNativeWaterFlags() {
        var belly = PostureResolver.resolve(true, false, "BELLYFLOP", false, Pose.STANDING, false, false, false, Vehicle.OTHER);
        assertTrue(belly.crawling()); assertFalse(belly.sitting()); assertEquals(Vehicle.NONE, belly.vehicle());
        assertTrue(PostureResolver.resolve(true, true, "", false, Pose.SWIMMING, true, false, false, Vehicle.OTHER).crawling());
    }

    @Test void vanillaLowCeilingIsCrawlOnLandAndSwimmingInWater() {
        assertTrue(PostureResolver.resolve(false, false, "", false, Pose.SWIMMING, false, false, false, Vehicle.NONE).crawling());
        assertFalse(PostureResolver.resolve(false, false, "", false, Pose.SWIMMING, true, false, false, Vehicle.NONE).crawling());
        assertFalse(PostureResolver.resolve(false, false, "", false, Pose.SWIMMING, false, false, true, Vehicle.NONE).crawling());
    }

    @Test void vehiclesAndSeatsRemainDistinctEvenWithStaleNativePose() {
        for (Vehicle vehicle : new Vehicle[]{Vehicle.BOAT, Vehicle.MINECART, Vehicle.OTHER}) {
            var p = PostureResolver.resolve(false, false, "", false, Pose.SWIMMING, false, false, false, vehicle);
            assertEquals(vehicle, p.vehicle()); assertFalse(p.crawling());
        }
        assertTrue(PostureResolver.resolve(true, false, "", false, Pose.STANDING, false, false, false, Vehicle.OTHER).sitting());
    }

    @Test void explicitGSitPronePostureWinsOverStaleSleepingFlags() {
        var belly = PostureResolver.resolve(true, false, "BELLYFLOP", true, Pose.SLEEPING,
                false, false, false, Vehicle.OTHER);
        assertTrue(belly.crawling()); assertFalse(belly.sleeping()); assertFalse(belly.sitting());
        var crawl = PostureResolver.resolve(true, true, "", true, Pose.SLEEPING,
                false, false, false, Vehicle.OTHER);
        assertTrue(crawl.crawling()); assertEquals(Vehicle.NONE, crawl.vehicle());
    }
}
