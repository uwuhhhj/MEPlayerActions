package com.simmc.meplayeractions.me;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.expression.YsmRuntime;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YsmAccessoriesTest {
    @Test void sourceAccessoryEventsRemainAvailableToNewViewersWithoutExportingSpringState() throws Exception {
        for(String model:List.of("ysm_01_jk","ysm_02_jk")) {
            var raw=JsonParser.parseString(Files.readString(Path.of("examples/models/"+model+".bbmodel"))).getAsJsonObject();
            var runtime=new YsmRuntime(raw,0);runtime.update(0,Map.of(),Map.of("idle",0d));
            assertEquals(Map.of("a",0d,"b",0d),YsmAnimations.accessories(runtime.snapshot()));
            // The original two separate timeline events commit the toggle at 1.2083 / 1.25 seconds.
            runtime.update(26,Map.of("query.vertical_speed",1d),Map.of("idle",1.3,"extra0",1.3));
            var toggled=YsmAnimations.accessories(runtime.snapshot());
            assertEquals(Map.of("a",1d,"b",1d),toggled);
            assertTrue(runtime.snapshot().variables().size()>50,"Original physics state stays private");
            runtime.update(80,Map.of(),Map.of("idle",4d));
            assertEquals(toggled,YsmAnimations.accessories(runtime.snapshot()),"A later viewer receives the completed action's state");
            var fresh=new YsmRuntime(raw,1);fresh.update(0,Map.of(),Map.of("idle",0d));
            assertEquals(Map.of("a",0d,"b",0d),YsmAnimations.accessories(fresh.snapshot()),"Model switches create independent accessory state");
            assertThrows(UnsupportedOperationException.class,()->toggled.put("a",0d));
            assertEquals(Map.of("a",1d,"b",1d),YsmAnimations.accessories(runtime.snapshot()),"Reading another instance cannot change this one");
        }
        assertEquals(Map.of(),YsmAnimations.accessories(null),"Ordinary numeric models have no authoritative accessories");
    }
}
