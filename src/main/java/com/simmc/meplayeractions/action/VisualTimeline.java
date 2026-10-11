package com.simmc.meplayeractions.action;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.Iterator;

/** Position and posture share one delayed frame, avoiding a landed pose on an airborne model. */
public final class VisualTimeline {
    public record Frame(long tick, UUID world, double x, double y, double z, StateSelector.Sample pose, ActionState air,
            long jumpCycle, float bodyYaw, float headYaw, float headPitch) {
        public Frame(long tick, UUID world, double x, double y, double z, StateSelector.Sample pose, ActionState air,
                long jumpCycle) {
            this(tick, world, x, y, z, pose, air, jumpCycle, 0, 0, 0);
        }
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    public Frame sample(Frame current, int delay, double maxDistance, boolean snapPostures) {
        Frame previous = this.frames.peekLast();
        if (previous != null && (!previous.world.equals(current.world) || distance(previous, current) > maxDistance
                || (snapPostures && !previous.pose.postureKey().equals(current.pose.postureKey())
                        && (special(previous.pose) || special(current.pose))))) {
            this.frames.clear();
        }

        if (!this.frames.isEmpty() && this.frames.peekLast().tick == current.tick) {
            this.frames.removeLast();
        }

        this.frames.addLast(current);
        long target = current.tick - delay;
        while (this.frames.size() > 1) {
            Iterator<VisualTimeline.Frame> iterator = this.frames.iterator();
            iterator.next();
            if (iterator.next().tick > target) {
                break;
            }

            this.frames.removeFirst();
        }
        Frame delayed = delay == 0 ? current : this.frames.peekFirst();
        if (distance(current, delayed) > maxDistance) {
            this.frames.clear();
            this.frames.add(current);
            return current;
        }
        return delayed;
    }

    private static boolean special(StateSelector.Sample p) {
        return p.sitting() || p.crawling() || p.sleeping() || p.vehicle() != StateSelector.Vehicle.NONE || p.gliding()
                || p.flying() || p.swimming() || (p.inWater() && !p.grounded());
    }

    private static double distance(Frame a, Frame b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2) + Math.pow(a.z - b.z, 2));
    }

    public void clear() {
        this.frames.clear();
    }
}
