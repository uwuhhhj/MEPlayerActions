package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelSelectionTest {
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
}
