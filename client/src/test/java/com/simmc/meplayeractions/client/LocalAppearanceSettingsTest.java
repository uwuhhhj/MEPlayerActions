package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalAppearanceSettingsTest {
    @Test void defaultsLeaveIndependentAppearanceDisabled() {
        assertEquals(new LocalAppearanceSettings(false,"ysm_02_jk",1,0,0,0),LocalAppearanceSettings.defaults());
    }

    @Test void acceptsBundledAndSingleLocalModelFiles() {
        for(String model:List.of("ysm_01_jk","ysm_02_jk","local:sample.bbmodel","local:我的模型.bbmodel")) {
            assertTrue(LocalAppearanceSettings.isValidModelId(model),model);
            assertEquals(model,new LocalAppearanceSettings(true,model,1,0,0,0).modelId());
        }
    }

    @Test void localModelSelectionCannotEscapeItsModelDirectory() {
        assertFalse(LocalAppearanceSettings.isValidModelId(null));
        assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,null,1,0,0,0));
        for(String model:List.of("","unknown","local:","local:sample.json","local:../sample.bbmodel",
                "local:dir/sample.bbmodel","local:dir\\sample.bbmodel","local:/sample.bbmodel",
                "local:C:\\sample.bbmodel","local:two..dots.bbmodel","local:bad"+(char)0+"name.bbmodel")) {
            assertFalse(LocalAppearanceSettings.isValidModelId(model),model);
            assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,model,1,0,0,0),model);
        }
    }

    @Test void scaleMustBeFiniteAndWithinTheSupportedRenderingRange() {
        for(float scale:List.of(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0f,-1f,.049f,8.001f,Float.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,"ysm_01_jk",scale,0,0,0),"scale="+scale);
        }
        assertEquals(.05f,new LocalAppearanceSettings(true,"ysm_01_jk",.05f,0,0,0).scale());
        assertEquals(8f,new LocalAppearanceSettings(true,"ysm_01_jk",8f,0,0,0).scale());
    }

    @Test void everyOffsetRejectsNonFiniteAndExcessiveCoordinates() {
        for(double offset:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-32.001,32.001,Double.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,"ysm_02_jk",1,offset,0,0),"x="+offset);
            assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,"ysm_02_jk",1,0,offset,0),"y="+offset);
            assertThrows(IllegalArgumentException.class,()->new LocalAppearanceSettings(true,"ysm_02_jk",1,0,0,offset),"z="+offset);
        }
        assertEquals(-32,new LocalAppearanceSettings(true,"ysm_02_jk",1,-32,32,-32).offsetX());
        assertEquals(32,new LocalAppearanceSettings(true,"ysm_02_jk",1,-32,32,-32).offsetY());
        assertEquals(-32,new LocalAppearanceSettings(true,"ysm_02_jk",1,-32,32,-32).offsetZ());
    }
}
