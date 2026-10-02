package com.simmc.meplayeractions.gameplay;

import com.simmc.meplayeractions.action.StateSelector.Vehicle;
import org.bukkit.entity.Pose;

/** GSit poses take precedence over their invisible vehicle and native swimming flags. */
public final class PostureResolver {
    private PostureResolver() {}
    public record Posture(boolean sitting, boolean crawling, boolean sleeping, Vehicle vehicle) {}

    public static Posture resolve(boolean gsitSeat, boolean gsitCrawl, String gsitPose,
                                  boolean sleeping, Pose pose, boolean inWater,
                                  boolean flying, boolean gliding, Vehicle vehicle) {
        if ("LAY".equals(gsitPose) || "LAY_BACK".equals(gsitPose) || "LEGS_UP".equals(gsitPose))
            return new Posture(false, false, true, Vehicle.NONE);
        if (gsitCrawl || "BELLYFLOP".equals(gsitPose))
            return new Posture(false, true, false, Vehicle.NONE);
        if (sleeping || pose == Pose.SLEEPING) return new Posture(false, false, true, Vehicle.NONE);
        if (gsitSeat) return new Posture(true, false, false, Vehicle.NONE);
        if (vehicle != Vehicle.NONE) return new Posture(false, false, false, vehicle);
        // Paper uses SWIMMING for crawling through a one-block-high gap on land too.
        boolean crawling = pose == Pose.SWIMMING && !inWater && !flying && !gliding;
        return new Posture(false, crawling, false, Vehicle.NONE);
    }
}
