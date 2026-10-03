package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelSelectionTest {
    @Test void classicPolygonPositionsFollowTheUpstreamPositiveXAxisOrder() {
        for(int slot=0;slot<8;slot++) {
            var action=WheelSelection.labelPoint(slot,65);
            var gear=WheelSelection.labelPoint(slot,35);
            assertEquals(slot,WheelSelection.classicSlotAt(action.x(),action.y(),50,100,8));
            assertEquals(slot,WheelSelection.classicSlotAt(gear.x(),gear.y(),20,50,8));
            assertEquals(-1,WheelSelection.classicSlotAt(gear.x(),gear.y(),50,100,8));
        }
        var first=WheelSelection.labelPoint(0,65);
        assertTrue(first.x()>0 && first.y()>0);
        assertEquals(Math.PI/8,Math.atan2(first.y(),first.x()),1e-6);
    }
    @Test void classicPartialPageDrawsAndAcceptsOnlyItsActualEntries() {
        assertEquals(0,WheelSelection.visibleSlots(0,0));assertEquals(3,WheelSelection.visibleSlots(0,3));
        assertEquals(8,WheelSelection.visibleSlots(0,10));assertEquals(2,WheelSelection.visibleSlots(1,10));
        assertEquals(0,WheelSelection.visibleSlots(Integer.MAX_VALUE,10));
        for(int slot=0;slot<8;slot++) {
            var action=WheelSelection.labelPoint(slot,65);
            assertEquals(slot<2?slot:-1,WheelSelection.classicSlotAt(action.x(),action.y(),50,100,2));
        }
        assertEquals(-1,WheelSelection.classicSlotAt(65,20,50,100,0));
    }
    @Test void classicGapsAndInnerConfigurationRegionCannotDispatchNeighborActions() {
        for(int edge=0;edge<8;edge++) {
            double angle=edge*Math.PI/4;
            for(double offset:new double[]{-.02,0,.02}) {
                assertEquals(-1,WheelSelection.classicSlotAt(65*Math.cos(angle+offset),65*Math.sin(angle+offset),50,100,8));
            }
        }
        assertEquals(-1,WheelSelection.classicSlotAt(0,0,20,50,8));
        assertEquals(-1,WheelSelection.classicSlotAt(Double.NaN,30,20,50,8));
        var middle=WheelSelection.labelPoint(1,40);
        assertEquals(1,WheelSelection.classicSlotAt(middle.x(),middle.y(),20,50,8));
        assertEquals(-1,WheelSelection.classicSlotAt(middle.x(),middle.y(),50,100,8));
    }
    @Test void classicSlicesContainFourStraightEdgesWithTheAuthoredGapAndVertexOrder() {
        for(int slot=0;slot<8;slot++) {
            var quad=WheelSelection.polygon(slot,25,105);assertEquals(4,quad.size());
            assertEquals(105,Math.hypot(quad.get(0).x(),quad.get(0).y()),1e-4);
            assertEquals(25,Math.hypot(quad.get(1).x(),quad.get(1).y()),1e-4);
            assertEquals(25,Math.hypot(quad.get(2).x(),quad.get(2).y()),1e-4);
            assertEquals(105,Math.hypot(quad.get(3).x(),quad.get(3).y()),1e-4);
            double expectedStart=slot*Math.PI/4+Math.PI/90;
            assertEquals(Math.cos(expectedStart)*105,quad.get(0).x(),1e-4);
            assertEquals(Math.sin(expectedStart)*105,quad.get(0).y(),1e-4);
            // A polygon has a straight outer chord. A circle/scanline fan would incorrectly reach radius 105 here.
            double chordRadius=Math.hypot((quad.get(0).x()+quad.get(3).x())/2d,(quad.get(0).y()+quad.get(3).y())/2d);
            assertEquals(105*Math.cos(Math.PI/8-Math.PI/90),chordRadius,1e-4);
            assertTrue(chordRadius<100);
            double signedArea=0;
            for(int i=0;i<4;i++) {
                var a=quad.get(i);var b=quad.get((i+1)%4);signedArea+=a.x()*b.y()-a.y()*b.x();
            }
            assertTrue(signedArea<0,"Upstream GUI quad winding must remain consistent");
        }
        assertThrows(IllegalArgumentException.class,()->WheelSelection.polygon(8,25,105));
        assertThrows(IllegalArgumentException.class,()->WheelSelection.polygon(0,105,25));
    }
    @Test void classicWheelAndAuthorPanelFitTogetherInCompactAndWideViewports() {
        for(int[] size:new int[][]{{320,240},{426,240},{640,360},{960,540}}) {
            var layout=WheelSelection.layout(size[0],size[1]);
            assertTrue(layout.centerX()-115*layout.scale()>=10);
            assertTrue(layout.x(270)<=size[0]-10);
            assertTrue(layout.centerY()-115*layout.scale()>=10);
            assertTrue(layout.centerY()+115*layout.scale()<=size[1]-10);
            assertTrue(layout.panelX()>layout.centerX()+105*layout.scale());
            assertTrue(layout.viewportY()>=0 && layout.viewportY()+layout.viewportHeight()<=size[1]-15);
            for(int slot=0;slot<8;slot++) {
                var point=WheelSelection.labelPoint(slot,65*layout.scale());
                assertEquals(slot,WheelSelection.classicSlotAt(point.x(),point.y(),50*layout.scale(),100*layout.scale(),8));
            }
        }
    }
    @Test void authorPanelScrollStopsAtBothEndsAndClampsAfterAGroupChange() {
        assertEquals(0,WheelSelection.clampScroll(-20,280,156));
        assertEquals(124,WheelSelection.clampScroll(1000,280,156));
        assertEquals(80,WheelSelection.clampScroll(80,280,156));
        assertEquals(0,WheelSelection.clampScroll(124,40,156));
    }
    @Test void cardinalAndDiagonalDirectionsFollowTheVisibleClockwiseNumbers() {
        assertEquals(0,WheelSelection.slotAt(0,-60,20,90,8));
        assertEquals(1,WheelSelection.slotAt(40,-40,20,90,8));
        assertEquals(2,WheelSelection.slotAt(60,0,20,90,8));
        assertEquals(3,WheelSelection.slotAt(40,40,20,90,8));
        assertEquals(4,WheelSelection.slotAt(0,60,20,90,8));
        assertEquals(5,WheelSelection.slotAt(-40,40,20,90,8));
        assertEquals(6,WheelSelection.slotAt(-60,0,20,90,8));
        assertEquals(7,WheelSelection.slotAt(-40,-40,20,90,8));
    }
    @Test void centerStopAndOutsideMouseNeverSelectAnAction() {
        assertEquals(-1,WheelSelection.slotAt(0,0,20,90,8));
        assertEquals(-1,WheelSelection.slotAt(0,-19.9,20,90,8));
        assertEquals(-1,WheelSelection.slotAt(0,-90,20,90,8));
        assertEquals(-1,WheelSelection.slotAt(Double.NaN,30,20,90,8));
    }
    @Test void angleWrapAndBoundaryHaveOneUnambiguousSlot() {
        double angle=Math.toRadians(-67.5);
        assertEquals(0,WheelSelection.slotAt(60*Math.cos(angle-.0001),60*Math.sin(angle-.0001),20,90,8));
        assertEquals(1,WheelSelection.slotAt(60*Math.cos(angle+.0001),60*Math.sin(angle+.0001),20,90,8));
        assertEquals(0,WheelSelection.slotAt(-.001,-60,20,90,8));
        assertEquals(0,WheelSelection.slotAt(.001,-60,20,90,8));
    }
    @Test void partialAndEmptyPagesNeverReturnAStaleOrForeignAction() {
        assertEquals(1,WheelSelection.pageCount(0,8));assertEquals(2,WheelSelection.pageCount(9,8));
        assertEquals(8,WheelSelection.actionIndex(1,0,9,8));
        assertEquals(-1,WheelSelection.actionIndex(1,1,9,8));
        assertEquals(-1,WheelSelection.actionIndex(0,-1,9,8));
        assertEquals(-1,WheelSelection.actionIndex(0,0,0,8));
        assertEquals(-1,WheelSelection.actionIndex(Integer.MAX_VALUE,7,9,8));
    }
    @Test void cachedRingPixelsUseTheSameHitTestingAsTheMouse() {
        var runs=WheelSelection.ring(20,80,8);assertFalse(runs.isEmpty());
        for(var run:runs)for(int x=run.x1();x<run.x2();x++)
            assertEquals(run.slot(),WheelSelection.slotAt(x+.5,run.y()+.5,20,80,8));
    }
    @Test void savedPageIsClampedToChangedActualActionsIncludingAnEmptyServerList() {
        assertEquals(2,WheelSelection.clampPage(2,24,8));
        assertEquals(1,WheelSelection.clampPage(2,9,8));
        assertEquals(0,WheelSelection.clampPage(2,8,8));
        assertEquals(0,WheelSelection.clampPage(2,0,8));
        assertEquals(0,WheelSelection.clampPage(-1,32,8));
    }
    @Test void compactWheelStaysClearOfTitleSettingsAndOnlyReservesPagingWhenNeeded() {
        for(int[] viewport:new int[][]{{320,240},{426,240},{640,360},{960,540}}) {
            int width=viewport[0],height=viewport[1];
            var single=WheelSelection.layout(width,height,false);
            var paged=WheelSelection.layout(width,height,true);
            for(var layout:new WheelSelection.Layout[]{single,paged}) {
                assertEquals(width/2,layout.centerX());
                assertTrue(layout.centerX()-layout.outer()>=12);assertTrue(layout.centerX()+layout.outer()<=width-12);
                assertTrue(layout.centerY()-layout.outer()>=34);assertTrue(layout.centerY()+layout.outer()<=height-34);
                assertTrue(layout.inner()>0&&layout.inner()<layout.outer());
                for(int slot=0;slot<8;slot++) {
                    double angle=-Math.PI/2+slot*Math.PI/4,radius=(layout.inner()+layout.outer())/2d;
                    assertEquals(slot,WheelSelection.slotAt(Math.cos(angle)*radius,Math.sin(angle)*radius,
                            layout.inner(),layout.outer(),8));
                }
            }
            assertTrue(single.outer()>=paged.outer());
            assertTrue(paged.centerY()+paged.outer()<=height-50);
        }
    }
}
