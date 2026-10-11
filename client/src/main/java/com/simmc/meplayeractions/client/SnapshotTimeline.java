package com.simmc.meplayeractions.client;

import java.util.*;

/** Animation layers and transforms must refer to the same presentation time. */
public final class SnapshotTimeline<T>
{
    private final NavigableMap<Long, T> frames = new TreeMap<>();

    public void add(long tick, T value)
    {
        if (!this.frames.isEmpty() && tick < this.frames.lastKey())
        {
            return;
        }

        this.frames.put(tick, value);
        while (this.frames.size() > 32)
        {
            this.frames.pollFirstEntry();
        }

    }

    public T sample(double tick)
    {
        if (this.frames.isEmpty())
        {
            throw new IllegalStateException("No snapshots");
        }

        Map.Entry<Long, T> frame = this.frames.floorEntry((long) Math.floor(tick));
        return frame == null ? this.frames.firstEntry().getValue() : frame.getValue();
    }
}
