package com.simmc.meplayeractions.client;

import java.util.*;

/** Server positions already include disguise delay; this buffer only smooths packet arrivals. */
public final class TransformTimeline {
    public record Transform(long tick, double x, double y, double z, float bodyYaw, float headYaw, float headPitch) {}
    private final Deque<Transform> frames = new ArrayDeque<>();
    public boolean add(Transform frame) {
        Transform last = frames.peekLast();
        if (last != null && frame.tick < last.tick) return false;
        if (last != null && (squaredDistance(last, frame) > 16 || frame.tick - last.tick > 40)) frames.clear();
        if (!frames.isEmpty() && frames.getLast().tick == frame.tick) frames.removeLast();
        frames.add(frame);
        while (frames.size() > 12) frames.removeFirst();
        return true;
    }
    public Transform sample(double tick) {
        if (frames.isEmpty()) throw new IllegalStateException("No transforms");
        Transform left = frames.getFirst();
        if (tick <= left.tick) return left;
        for (Transform right : frames) {
            if (right.tick >= tick && right.tick > left.tick) {
                double alpha = Math.max(0, Math.min(1, (tick - left.tick) / (right.tick - left.tick)));
                return new Transform((long) tick, lerp(left.x,right.x,alpha), lerp(left.y,right.y,alpha), lerp(left.z,right.z,alpha),
                        angle(left.bodyYaw,right.bodyYaw,alpha), angle(left.headYaw,right.headYaw,alpha),
                        (float) lerp(left.headPitch,right.headPitch,alpha));
            }
            left = right;
        }
        return frames.getLast(); // Never extrapolate a model through a wall or off a vehicle.
    }
    private static double squaredDistance(Transform a, Transform b) {
        double x=a.x-b.x,y=a.y-b.y,z=a.z-b.z; return x*x+y*y+z*z;
    }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static float angle(float a,float b,double t) {
        double delta=((b-a+180)%360+360)%360-180; return (float)(a+delta*t);
    }
}
