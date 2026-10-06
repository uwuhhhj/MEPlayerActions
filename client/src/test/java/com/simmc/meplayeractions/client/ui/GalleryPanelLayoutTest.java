package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GalleryPanelLayoutTest {
    @Test void largeWindowKeepsTheSourceFiveByTwoDeckInsideACompactCenteredPanel() {
        var panel = home(1200,660);
        assertEquals(420, panel.panelWidth());
        assertEquals(135, panel.previewWidth());
        assertEquals(282, panel.rightWidth());
        assertEquals(238, panel.bottom() - panel.top(), "MPA search/pagination adds three pixels to the source content height");
        assertEquals(5, panel.columns()); assertEquals(2, panel.rows());
        assertEquals(panel.left() + 143, panel.right() + 5, "Use the source card X origin");
        assertCentered(panel, 1200, 660);
        assertCardsFit(panel);
    }

    @Test void enlargingTheGameWindowMovesTheWholePanelWithoutExpandingOnlyItsEmptyBackground() {
        var before = home(640,360);
        var after = home(1920,1080);
        assertEquals(before.panelWidth(), after.panelWidth());
        assertEquals(before.frameBottom() - before.frameTop(), after.frameBottom() - after.frameTop());
        assertEquals(before.columns() * before.rows(), after.columns() * after.rows());
        assertEquals(640, after.left() - before.left());
        assertEquals(360, after.titleY() - before.titleY());
        assertEquals(after.titleY() - before.titleY(), after.footerY() - before.footerY());
    }

    @Test void smallGuiWindowFitsSourceHeaderCardsAndFooterWithoutOverlap() {
        var panel = home(320,240);
        assertTrue(panel.compact());
        assertEquals(3, panel.columns()); assertEquals(1, panel.rows());
        assertTrue(panel.left() >= 4 && panel.left() + panel.panelWidth() <= 316);
        assertTrue(panel.frameTop() >= 4 && panel.frameBottom() <= 236);
        int sourceY = panel.top() - 20;
        assertTrue(panel.titleY() + 9 <= sourceY);
        assertTrue(sourceY + 18 < panel.top());
        assertEquals(panel.frameBottom(), panel.footerY() + 20);
        assertCardsFit(panel);
    }

    private static GalleryPanelLayout.Bounds home(int width,int height) {
        return GalleryPanelLayout.create(width,height,GalleryPanelLayout.HOME_HEADER_HEIGHT,GalleryPanelLayout.HOME_COMPACT_HEADER_HEIGHT);
    }

    @Test void standaloneGalleryAndServerViewUseTheirOwnHeaderSpaceInsideThePanel() {
        for (var panel : new GalleryPanelLayout.Bounds[]{
                GalleryPanelLayout.create(640, 360, 38, 29),
                GalleryPanelLayout.create(640, 360, 49, 37),
                GalleryPanelLayout.create(320, 240, 49, 37)}) {
            assertTrue(panel.titleY() + 9 < panel.top());
            assertEquals(panel.frameBottom(), panel.footerY() + 20);
            assertCardsFit(panel);
        }
    }

    private static void assertCentered(GalleryPanelLayout.Bounds panel, int width, int height) {
        assertTrue(Math.abs(2 * panel.left() + panel.panelWidth() - width) <= 1);
        assertTrue(Math.abs(panel.frameTop() + panel.frameBottom() - height) <= 1);
    }

    private static void assertCardsFit(GalleryPanelLayout.Bounds panel) {
        int cardRight = panel.right() + 5 + (panel.columns() - 1) * 55 + 52;
        int cardBottom = panel.top() + 29 + (panel.rows() - 1) * 93 + 90;
        assertTrue(cardRight <= panel.right() + panel.rightWidth() - 5);
        assertTrue(cardBottom <= panel.bottom() - 26, "A 235-pixel container with MPA spacing would wrongly collapse this deck to one row");
    }
}
