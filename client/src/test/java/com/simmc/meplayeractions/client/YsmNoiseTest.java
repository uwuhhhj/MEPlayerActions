package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YsmNoiseTest {
    @Test void matchesSeededStbV05ReferenceVectorsIncludingNegativeCoordinates() {
        assertEquals(-.5f,YsmNoise.sample(0,.5,.5,.5),1e-7f);
        assertEquals(.25f,YsmNoise.sample(0,.5,0,0),1e-7f);
        assertEquals(.3477816581726074f,YsmNoise.sample(17,.25,.5,.75),1e-7f);
        assertEquals(-.18262386322021484f,YsmNoise.sample(-1,-.25,.5,-.75),1e-7f);
        assertEquals(.4411521553993225f,YsmNoise.sample(42,1.125,-2.25,3.5),1e-7f);
    }
    @Test void usesLowestSeedByteAndStb256CoordinatePeriod() {
        float sample=YsmNoise.sample(17,.25,.5,.75);
        assertEquals(sample,YsmNoise.sample(273,.25,.5,.75));
        assertEquals(sample,YsmNoise.sample(17,256.25,-255.5,512.75));
        assertNotEquals(sample,YsmNoise.sample(18,.25,.5,.75));
    }
    @Test void integerLatticeIsZeroAndCellBoundariesRemainContinuous() {
        for(int seed:new int[]{0,1,17,-1})for(int x=-3;x<=3;x++)assertEquals(0,YsmNoise.sample(seed,x,-2,7));
        assertEquals(YsmNoise.sample(42,-1.00001,.2,.3),YsmNoise.sample(42,-.99999,.2,.3),1e-4);
    }
    @Test void rejectsNonFiniteAndUnsafeCoordinateRange() {
        assertThrows(IllegalArgumentException.class,()->YsmNoise.sample(0,Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->YsmNoise.sample(0,0,Double.POSITIVE_INFINITY,0));
        assertThrows(IllegalArgumentException.class,()->YsmNoise.sample(0,0,0,1_000_000_001d));
    }
}
