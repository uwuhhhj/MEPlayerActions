package com.simmc.meplayeractions.client;

import java.util.*;

/** Animation layers and transforms must refer to the same presentation time. */
public final class SnapshotTimeline<T> {
    private final NavigableMap<Long,T> frames=new TreeMap<>();
    public void add(long tick,T value) {
        if(!frames.isEmpty() && tick<frames.lastKey())return;
        frames.put(tick,value);
        while(frames.size()>32)frames.pollFirstEntry();
    }
    public T sample(double tick) {
        if(frames.isEmpty())throw new IllegalStateException("No snapshots");
        Map.Entry<Long,T> frame=frames.floorEntry((long)Math.floor(tick));
        return frame==null?frames.firstEntry().getValue():frame.getValue();
    }
}
