package com.simmc.meplayeractions.me;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalRenderingDiagnosisTest {
    @Test void oneOwnedConnectedModelWithAudienceMayRequestLocalRendering() {
        var diagnosis=ModelEngineBridge.diagnoseLocalRendering(true,true,true,1);
        assertTrue(diagnosis.allowed());assertEquals(1,diagnosis.modelCount());
        assertTrue(diagnosis.reason().contains("客户端确认"),"Eligibility must not claim a render_ready lease already exists");
    }
    @Test void attachedForeignModelsRetainOwnershipProtectionEvenWhenSingleAndVisible() {
        var diagnosis=ModelEngineBridge.diagnoseLocalRendering(true,false,true,1);
        assertFalse(diagnosis.allowed());assertTrue(diagnosis.attached());assertFalse(diagnosis.owned());
        assertTrue(diagnosis.reason().contains("原生 ME/其他插件"));
    }
    @Test void missingConnectionOrAudienceHasAnExplicitFailureReason() {
        var disconnected=ModelEngineBridge.diagnoseLocalRendering(false,true,true,1);
        assertFalse(disconnected.allowed());assertTrue(disconnected.reason().contains("失效"));
        var noAudience=ModelEngineBridge.diagnoseLocalRendering(true,true,false,1);
        assertFalse(noAudience.allowed());assertTrue(noAudience.reason().contains("观众过滤器"));
    }
    @Test void zeroOrMultipleModelsFailTheExactlyOneGateWithoutHidingForeignModels() {
        for(int count:new int[]{0,2,3}) {
            var diagnosis=ModelEngineBridge.diagnoseLocalRendering(true,true,true,count);
            assertFalse(diagnosis.allowed());assertEquals(count,diagnosis.modelCount());
            assertTrue(diagnosis.reason().contains("恰好有 1 个"));assertTrue(diagnosis.reason().contains("当前 "+count));
        }
    }
}
