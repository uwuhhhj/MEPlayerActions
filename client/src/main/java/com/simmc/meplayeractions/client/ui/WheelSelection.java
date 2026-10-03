package com.simmc.meplayeractions.client.ui;

import java.util.ArrayList;
import java.util.List;

/** Pure radial hit testing and paging; slot zero is centred at twelve o'clock. */
public final class WheelSelection {
    private WheelSelection() {}
    public static int slotAt(double x, double y, double inner, double outer, int slots) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(inner) || !Double.isFinite(outer)
                || inner < 0 || outer <= inner || slots < 1) return -1;
        double squared = x*x + y*y;
        if (squared < inner*inner || squared >= outer*outer) return -1;
        double slice = Math.PI * 2 / slots;
        double angle = Math.atan2(y, x) + Math.PI / 2 + slice / 2;
        angle = (angle % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
        return Math.min(slots - 1, (int)(angle / slice));
    }
    public static int pageCount(int count, int slots) {
        if (slots < 1) throw new IllegalArgumentException("slots must be positive");
        return count <= 0 ? 1 : (count - 1) / slots + 1;
    }
    public static int actionIndex(int page, int slot, int count, int slots) {
        if (page < 0 || slot < 0 || slot >= slots || slots < 1 || count < 1) return -1;
        long index = (long)page * slots + slot;
        return index < count ? (int)index : -1;
    }
    public record Run(int slot, int x1, int x2, int y) {}
    /** Cached one-pixel scan lines keep the native GUI fan curved without texture assets. */
    public static List<Run> ring(int inner, int outer, int slots) {
        List<Run> runs = new ArrayList<>();
        for (int y=-outer; y<outer; y++) {
            int start=-outer, previous=-1;
            for (int x=-outer; x<=outer; x++) {
                int slot = x==outer ? -1 : slotAt(x+.5,y+.5,inner,outer,slots);
                if (slot >= 0) {
                    double angle=Math.atan2(y+.5,x+.5)+Math.PI/2;
                    double slice=Math.PI*2/slots;
                    double fromEdge=(angle+slice/2)%slice;
                    if (fromEdge<0) fromEdge+=slice;
                    if (Math.min(fromEdge,slice-fromEdge)<.015) slot=-1;
                }
                if (slot != previous) {
                    if (previous >= 0) runs.add(new Run(previous,start,x,y));
                    previous=slot; start=x;
                }
            }
        }
        return List.copyOf(runs);
    }
}
