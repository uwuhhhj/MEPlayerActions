package com.simmc.meplayeractions.expression;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YsmRuntimeTemplateTest {
    private static JsonObject model() {
        return JsonParser.parseString("""
                {"animations":[
                  {"name":"parallel1","animators":{"init":{"name":"events","type":"effect","keyframes":[
                    {"time":0,"data_points":[{"script":"variable.spring = 0; variable.event = 0;"},
                                               {"script":"variable.spring = 0; variable.event = 0;"}]}]}}},
                  {"name":"parallel2","animators":{"physics":{"name":"events","type":"effect","keyframes":[
                    {"time":0,"data_points":[{"script":"variable.spring += query.ground_speed;"}]}]}}},
                  {"name":"idle","animators":{
                    "event":{"name":"events","type":"effect","keyframes":[
                      {"time":0.5,"data_points":[{"script":"variable.event += 1;"}]}]},
                    "00000000-0000-0000-0000-000000000001":{"name":"body","type":"bone","keyframes":[
                      {"time":0,"channel":"rotation","interpolation":"linear","data_points":[
                        {"x":"variable.spring + variable.event"},{"x":"variable.spring + variable.event"}]}]}}}
                ]}
                """).getAsJsonObject();
    }

    @Test void eachDistinctScriptAndAxisIsCompiledOnceAndInstancesReuseTheImmutableTemplate() {
        var source = model(); var compiles = new AtomicInteger();
        var template = YsmRuntime.Template.compile(source, text -> { compiles.incrementAndGet(); return Molang.compile(text); });
        assertEquals(4, compiles.get(), "Repeated initialization and pre/post axes share Programs");
        var program = template.expression("variable.spring + variable.event");
        assertSame(program, template.expression("variable.spring + variable.event"));
        source.add("animations", JsonParser.parseString("[]"));
        var first = new YsmRuntime(template, 1); var second = new YsmRuntime(template, 2);
        first.update(0, Map.of(), Map.of("idle", .6)); second.update(0, Map.of(), Map.of("idle", 0d));
        assertEquals(1d, first.snapshot().variables().get("variable.event"));
        assertEquals(0d, second.snapshot().variables().get("variable.event"));
        assertEquals(4, compiles.get(), "Creating/evaluating instances must not compile or reread mutated JSON");
        assertThrows(UnsupportedOperationException.class, () -> program.references().add("variable.external"));
    }

    @Test void physicsAccumulatorVariablesEventTimesAndResetAreIndependentBetweenInstances() {
        var template = YsmRuntime.Template.compile(model());
        var first = new YsmRuntime(template, 1); var second = new YsmRuntime(template, 2);
        first.update(0, Map.of("query.ground_speed", 1d), Map.of("idle", 0d));
        second.update(0, Map.of("query.ground_speed", 2d), Map.of("idle", 0d));
        first.update(1, Map.of("query.ground_speed", 1d), Map.of("idle", .6));
        second.update(1, Map.of("query.ground_speed", 2d), Map.of("idle", .2));
        assertEquals(5d, first.snapshot().variables().get("variable.spring"));
        assertEquals(10d, second.snapshot().variables().get("variable.spring"));
        assertEquals(1d, first.snapshot().variables().get("variable.event"));
        assertEquals(0d, second.snapshot().variables().get("variable.event"));
        first.update(2, Map.of("query.ground_speed", 1d), Map.of("idle", .7));
        second.update(2, Map.of("query.ground_speed", 2d), Map.of("idle", .7));
        assertEquals(10d, first.snapshot().variables().get("variable.spring"));
        assertEquals(20d, second.snapshot().variables().get("variable.spring"));
        assertEquals(1d, second.snapshot().variables().get("variable.event"));
        first.reset(); first.update(2, Map.of(), Map.of("idle", 0d));
        second.update(3, Map.of("query.ground_speed", 2d), Map.of("idle", .8));
        assertEquals(0d, first.snapshot().variables().get("variable.spring"));
        assertEquals(0d, first.snapshot().variables().get("variable.event"));
        assertEquals(30d, second.snapshot().variables().get("variable.spring"));
        assertEquals(1d, second.snapshot().variables().get("variable.event"));
    }
}
