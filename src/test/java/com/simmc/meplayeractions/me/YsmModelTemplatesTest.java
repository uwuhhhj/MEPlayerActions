package com.simmc.meplayeractions.me;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YsmModelTemplatesTest {
    private static JsonObject model() {
        return JsonParser.parseString("""
                {"mpa_runtime":true,"animations":[{"name":"idle","animators":{
                  "00000000-0000-0000-0000-000000000001":{"name":"body","type":"bone","keyframes":[
                    {"channel":"scale","time":0.125,"interpolation":"linear","data_points":[
                      {"x":"variable.scale"},{"x":"variable.scale + 1"}]}]}}}]}
                """).getAsJsonObject();
    }

    @Test void repeatedModelDoesOneSourceReadAndParseAndRetainsOnlyImmutableCompiledData() {
        var loads = new AtomicInteger(); var raw = model();
        var cache = new YsmModelTemplates(64, name -> { loads.incrementAndGet(); return raw; });
        var template = cache.get("player").orElseThrow();
        raw.add("animations", JsonParser.parseString("[]"));
        assertSame(template, cache.get("player").orElseThrow()); assertEquals(1, loads.get());
        var clip = template.clips().getFirst(); var bone = clip.bones().getFirst(); var frame = bone.frames().getFirst();
        assertEquals("idle", clip.name()); assertEquals(.125f, frame.time()); assertTrue(frame.discontinuous());
        assertEquals("variable.scale", frame.pre().x()); assertEquals("variable.scale + 1", frame.post().x());
        assertEquals("1", frame.pre().y(), "Scale axis defaults are retained in the immutable descriptor");
        assertThrows(UnsupportedOperationException.class, () -> template.clips().clear());
        assertThrows(UnsupportedOperationException.class, () -> clip.bones().clear());
        assertThrows(UnsupportedOperationException.class, () -> bone.frames().clear());
        assertSame(template.runtime().expression(frame.pre().x()), template.runtime().expression(frame.pre().x()));
    }

    @Test void absentAndOrdinaryModelsAreNegativeCachedAndLeastRecentlyUsedEntriesAreBounded() {
        Map<String,Integer> loads = new HashMap<>();
        var cache = new YsmModelTemplates(2, name -> {
            loads.merge(name, 1, Integer::sum);
            return name.equals("absent") ? null : new JsonObject();
        });
        assertTrue(cache.get("absent").isEmpty()); assertTrue(cache.get("ordinary").isEmpty());
        cache.get("absent"); cache.get("ordinary"); assertEquals(Map.of("absent", 1, "ordinary", 1), loads);
        cache.get("absent"); cache.get("third"); cache.get("ordinary");
        assertEquals(1, loads.get("absent")); assertEquals(2, loads.get("ordinary"));
    }
}
