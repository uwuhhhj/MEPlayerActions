package com.simmc.meplayeractions.client.ui;

/**
 * OpenYSM-Updated 0306e1f PlayerModelScreen's centered 420x235 panel and 5x2 slots.
 * MPA's search/pagination spacing needs three extra content pixels; source
 * headers and the three visibility controls are attached to that same panel.
 */
final class GalleryPanelLayout {
    static final int CARD_WIDTH = 52, CARD_HEIGHT = 90, CARD_GAP = 3;
    static final int HOME_HEADER_HEIGHT = 49, HOME_COMPACT_HEADER_HEIGHT = 37;
    private static final int PANEL_WIDTH = 420, PREVIEW_WIDTH = 135, PANEL_GAP = 3;
    private static final int GRID_TOP = 29, GRID_BOTTOM = 26, FOOTER_HEIGHT = 24;
    private static final int CONTENT_HEIGHT = GRID_TOP + 2 * CARD_HEIGHT + CARD_GAP + GRID_BOTTOM;

    record Bounds(int left, int frameTop, int frameBottom, int top, int bottom,
                  int panelWidth, int previewWidth, int right, int rightWidth,
                  int titleY, int footerY, int columns, int rows, boolean compact) { }

    static Bounds create(int width, int height, int headerHeight, int compactHeaderHeight) {
        int panelWidth = Math.max(220, Math.min(PANEL_WIDTH, width - 16));
        int left = (width - panelWidth) / 2;
        boolean compact = height < headerHeight + CONTENT_HEIGHT + FOOTER_HEIGHT + 8;
        int header = compact ? compactHeaderHeight : headerHeight;
        int contentHeight = Math.max(0, Math.min(CONTENT_HEIGHT, height - 8 - header - FOOTER_HEIGHT));
        int frameHeight = header + contentHeight + FOOTER_HEIGHT;
        int frameTop = (height - frameHeight) / 2;
        int top = frameTop + header, bottom = top + contentHeight;
        int previewWidth = Math.max(88, panelWidth * PREVIEW_WIDTH / PANEL_WIDTH);
        int right = left + previewWidth + PANEL_GAP;
        int rightWidth = panelWidth - previewWidth - PANEL_GAP;
        int columns = Math.max(1, Math.min(5, (rightWidth - 10 + CARD_GAP) / (CARD_WIDTH + CARD_GAP)));
        int rows = Math.max(1, Math.min(2, (contentHeight - GRID_TOP - GRID_BOTTOM + CARD_GAP) / (CARD_HEIGHT + CARD_GAP)));
        return new Bounds(left, frameTop, frameTop + frameHeight, top, bottom,
                panelWidth, previewWidth, right, rightWidth, frameTop + 7, bottom + 4,
                columns, rows, compact);
    }

    private GalleryPanelLayout() { }
}
