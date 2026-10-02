package com.simmc.meplayeractions.action;

import java.util.ArrayDeque;
import java.util.UUID;

/** Position and posture share one delayed frame, avoiding a landed pose on an airborne model. */
public final class VisualTimeline {
    public record Frame(long tick, UUID world, double x, double y, double z,
                        StateSelector.Sample pose, ActionState air, long jumpCycle,
                        float bodyYaw, float headYaw, float headPitch) {
        public Frame(long tick, UUID world, double x, double y, double z,
                     StateSelector.Sample pose, ActionState air, long jumpCycle) {
            this(tick, world, x, y, z, pose, air, jumpCycle, 0, 0, 0);
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    public Frame sample(Frame current, int delay, double maxDistance, boolean snapPostures) {
        Frame previous = frames.peekLast();
        if (previous != null && (!previous.world.equals(current.world) || distance(previous, current) > maxDistance
                || (snapPostures && !previous.pose.postureKey().equals(current.pose.postureKey())
                && (special(previous.pose) || special(current.pose))))) frames.clear();
        if (!frames.isEmpty() && frames.peekLast().tick == current.tick) frames.removeLast();
        frames.addLast(current);
        long target = current.tick - delay;
        while (frames.size() > 1) {
            var iterator = frames.iterator(); iterator.next();
            if (iterator.next().tick > target) break;
            frames.removeFirst();
        }
        Frame delayed = delay == 0 ? current : frames.peekFirst();
        if (distance(current, delayed) > maxDistance) { frames.clear(); frames.add(current); return current; }
        return delayed;
    }

    private static boolean special(StateSelector.Sample p) {
        return p.sitting() || p.crawling() || p.sleeping() || p.vehicle() != StateSelector.Vehicle.NONE
                || p.gliding() || p.flying() || p.swimming() || (p.inWater() && !p.grounded());
    }
    private static double distance(Frame a, Frame b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2) + Math.pow(a.z - b.z, 2));
    }
    public void clear() { frames.clear(); }
}
