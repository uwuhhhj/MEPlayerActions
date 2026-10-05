package com.simmc.meplayeractions.client.ui;

import java.util.List;

/**
 * Classic OpenYSM roulette geometry, adapted from AnimationRouletteScreen at 0306e1f.
 * The original uses eight 45 degree positions, two degree gaps and only occupied positions.
 * OpenYSM is MIT licensed; see THIRD_PARTY_NOTICES.md.
 */
public final class WheelSelection {
    public static final int PAGE_SIZE = 8;
    public static final double SLICE_ANGLE = Math.PI / 4, GAP_ANGLE = Math.PI / 90;
    private WheelSelection() { }

    /** Radius limits and angle gaps intentionally match the upstream classic hover rules. */
    public static int classicSlotAt(double x, double y, double inner, double outer, int visibleSlots) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(inner) || !Double.isFinite(outer)
                || inner < 0 || outer <= inner || visibleSlots < 1 || visibleSlots > PAGE_SIZE) return -1;
        double radius = Math.hypot(x, y);
        if (radius <= inner || radius >= outer) return -1;
        double angle = Math.atan2(y, x);
        if (angle < 0) angle += Math.PI * 2;
        int slot = (int) (angle / SLICE_ANGLE);
        return slot < visibleSlots && angle > slot * SLICE_ANGLE + GAP_ANGLE
                && angle < (slot + 1) * SLICE_ANGLE - GAP_ANGLE ? slot : -1;
    }

    public record Point(float x, float y) { }
    public static Point labelPoint(int slot, double radius) {
        checkSlot(slot);
        double angle = (slot + .5) * SLICE_ANGLE;
        return new Point((float) (Math.cos(angle) * radius), (float) (Math.sin(angle) * radius));
    }

    /** Four vertices, in upstream order: outer start, inner start, inner end, outer end. */
    public static List<Point> polygon(int slot, double inner, double outer) {
        checkSlot(slot);
        if (!Double.isFinite(inner) || !Double.isFinite(outer) || inner < 0 || outer <= inner)
            throw new IllegalArgumentException("Invalid roulette radii");
        double start = slot * SLICE_ANGLE + GAP_ANGLE, end = (slot + 1) * SLICE_ANGLE - GAP_ANGLE;
        return List.of(point(start, outer), point(start, inner), point(end, inner), point(end, outer));
    }
    private static Point point(double angle, double radius) {
        return new Point((float) (Math.cos(angle) * radius), (float) (Math.sin(angle) * radius));
    }
    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) throw new IllegalArgumentException("Invalid roulette position");
    }

    public static int pageCount(int count, int slots) {
        if (slots < 1) throw new IllegalArgumentException("slots must be positive");
        return count <= 0 ? 1 : (count - 1) / slots + 1;
    }
    public static int actionIndex(int page, int slot, int count, int slots) {
        if (page < 0 || slot < 0 || slot >= slots || slots < 1 || count < 1) return -1;
        long index = (long) page * slots + slot;
        return index < count ? (int) index : -1;
    }
    public static int clampPage(int page, int count, int slots) {
        return Math.max(0, Math.min(page, pageCount(count, slots) - 1));
    }
    public static int visibleSlots(int page, int count) {
        long remaining = (long) count - (long) page * PAGE_SIZE;
        return page < 0 ? 0 : (int) Math.max(0, Math.min(PAGE_SIZE, remaining));
    }

    public record RouletteLayout(int centerX, int centerY, double scale, int panelX, int panelWidth,
                         int viewportY, int viewportHeight) {
        public int scaled(double value) { return (int) Math.round(value * scale); }
        public int x(double offset) { return centerX + scaled(offset); }
        public int y(double offset) { return centerY + scaled(offset); }
        public int panelRight() { return panelX + panelWidth; }
        /** MPA source controls mirror the author panel without shifting the classic wheel. */
        public int sourcePanelX() { return 2 * centerX - panelRight(); }
        public int navigationWidth() { return Math.max(1, Math.min(panelWidth / 3, Math.max(20, scaled(30)))); }
        public int navigationY() { return y(-102); }
        public int navigationHeight() { return Math.max(26, scaled(30)); }
        public int backY() { return navigationY() + navigationHeight() + Math.max(1, scaled(2)); }
        public int backHeight() { return Math.max(18, scaled(22)); }
        public int scrollbarWidth() { return Math.max(1, Math.min(panelWidth / 3, Math.max(16, scaled(28)))); }
        public int formWidth() { return Math.max(1, panelWidth - scrollbarWidth() - Math.max(1, scaled(2))); }
        public int formRight() { return panelX + formWidth(); }
    }

    /** Keep the wheel at the unlocked mouse's screen centre; fit the source author panel on its right. */
    public static RouletteLayout layout(int width, int height) {
        int w = Math.max(1, width), h = Math.max(1, height);
        int centerX = w / 2, centerY = h / 2;
        // 270 is the source panel's right edge; 115 includes the hovered polygon expansion.
        // Reserve space for the title/settings above and the scroll hint/status below.
        double scale = Math.min(1, Math.min(Math.max(1, w - centerX - 12) / 270d,
                Math.max(1, Math.min(centerY - 34, h - centerY - 38)) / 115d));
        int gap = Math.max(1, (int) Math.round(2 * scale));
        int viewportY = centerY - (int) Math.round(102 * scale) + Math.max(26, (int) Math.round(30 * scale))
                + Math.max(18, (int) Math.round(22 * scale)) + 2 * gap;
        int viewportBottom = centerY + (int) Math.round(110 * scale);
        return new RouletteLayout(centerX, centerY, scale, centerX + (int) Math.round(125 * scale),
                Math.max(1, (int) Math.round(145 * scale)), viewportY,
                Math.max(1, viewportBottom - viewportY));
    }
    public static int clampScroll(int requested, int contentHeight, int viewportHeight) {
        return Math.max(0, Math.min(requested, Math.max(0, contentHeight - viewportHeight)));
    }

    /** Legacy circular geometry is retained for earlier callers and historical regression tests only. */
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
    public record Layout(int centerX, int centerY, int inner, int outer) { }
    public static Layout layout(int width, int height, boolean pagination) {
        int w=Math.max(1,width),h=Math.max(1,height);
        int top=Math.min(34,h/4),bottom=Math.min(pagination?50:34,h/3);
        int available=Math.max(2,h-top-bottom);
        int outer=Math.max(1,Math.min(Math.max(2,w-24)/2,available/2));
        int inner=Math.max(0,Math.min(outer-1,Math.max(12,outer/3)));
        return new Layout(w/2,top+available/2,inner,outer);
    }
    public record Run(int slot, int x1, int x2, int y) { }
    public static List<Run> ring(int inner, int outer, int slots) {
        java.util.ArrayList<Run> runs = new java.util.ArrayList<>();
        for (int y=-outer; y<outer; y++) {
            int start=-outer, previous=-1;
            for (int x=-outer; x<=outer; x++) {
                int slot=x==outer?-1:slotAt(x+.5,y+.5,inner,outer,slots);
                if(slot>=0) {
                    double angle=Math.atan2(y+.5,x+.5)+Math.PI/2,slice=Math.PI*2/slots;
                    double fromEdge=(angle+slice/2)%slice;
                    if(fromEdge<0)fromEdge+=slice;
                    if(Math.min(fromEdge,slice-fromEdge)<.015)slot=-1;
                }
                if(slot!=previous) {
                    if(previous>=0)runs.add(new Run(previous,start,x,y));
                    previous=slot;start=x;
                }
            }
        }
        return List.copyOf(runs);
    }
}
