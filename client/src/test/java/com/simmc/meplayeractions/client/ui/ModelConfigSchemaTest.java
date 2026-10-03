package com.simmc.meplayeractions.client.ui;

import com.google.gson.JsonParser;
import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigSchemaTest {
    private static final String FORMS = """
        [{"id":"eye","name":"眼睛","config_forms":[
          {"type":"checkbox","title":"头饰","value":"v.roaming.bow"},
          {"type":"range","title":"尺寸","value":"v.player_size","min":0.2,"max":3,"step":0.05},
          {"type":"radio","title":"眼睛","value":"v.player_eye","labels":{
            "默认":"v.player_eye=2;v.player_eye_x=-40;v.player_eye_y=20;",
            "重置":"v.player_eye=0;v.player_eye_x=0;v.player_eye_y=0;"}}
        ]}]
        """;
    @Test void checkboxAndRangeReadInitializedValuesAndUseAuthoredTargets() {
        var forms = ModelConfigSchema.parse(JsonParser.parseString(FORMS).getAsJsonArray()).groups().getFirst().forms();
        assertEquals(1, forms.get(0).read(Map.of("variable.roaming.bow", 1d)));
        assertEquals(2.5, forms.get(1).read(Map.of("variable.player_size", 2.5d)));
        var context = new Molang.Context();
        Molang.compile(forms.get(0).checkboxScript(false)).evaluate(context);
        Molang.compile(forms.get(1).rangeScript(2.67)).evaluate(context);
        assertEquals(0, context.get("v.roaming.bow")); assertEquals(2.65, context.get("v.player_size"), .00001);
        assertEquals(3, forms.get(1).snap(20));
    }
    @Test void radioLabelScriptExecutesMultipleOrdinaryAssignmentsRatherThanIndexAssignment() {
        var schema = ModelConfigSchema.parse(JsonParser.parseString(FORMS).getAsJsonArray());
        var radio = schema.groups().getFirst().forms().get(2);
        assertEquals("默认", radio.choices().getFirst().label());
        var context = new Molang.Context(); Molang.compile(radio.radioScript(0)).evaluate(context);
        assertEquals(2, context.get("v.player_eye")); assertEquals(-40, context.get("v.player_eye_x"));
        assertEquals(20, context.get("v.player_eye_y"));
        assertTrue(schema.variables().containsAll(java.util.Set.of("variable.player_eye", "variable.player_eye_x", "variable.player_eye_y", "variable.player_size")));
    }
    @Test void zeroAnchoredStepAndNegativeRangesMatchAuthorControls() {
        var config = JsonParser.parseString("[{\"id\":\"position\",\"config_forms\":[{\"type\":\"range\",\"value\":\"v.x\",\"min\":-10,\"max\":10,\"step\":3}]}]").getAsJsonArray();
        var form = ModelConfigSchema.parse(config).groups().getFirst().forms().getFirst();
        assertEquals(-3, form.snap(-4)); assertEquals(9, form.snap(10));
        assertThrows(IllegalArgumentException.class, () -> form.snap(Double.NaN));
    }
    @Test void malformedMetadataRejectsReadonlyTargetsDuplicateIdsAndInvalidRanges() {
        for (String bad : java.util.List.of(
                "[{\"id\":\"x\",\"config_forms\":[{\"type\":\"checkbox\",\"value\":\"query.x\"}]}]",
                "[{\"id\":\"x\"},{\"id\":\"x\"}]",
                "[{\"id\":\"x\",\"config_forms\":[{\"type\":\"range\",\"value\":\"v.x\",\"min\":2,\"max\":1}]}]",
                "[{\"id\":\"x\",\"config_forms\":[{\"type\":\"radio\",\"value\":\"v.x\",\"labels\":{}}]}]"))
            assertThrows(IllegalArgumentException.class, () -> ModelConfigSchema.parse(JsonParser.parseString(bad).getAsJsonArray()));
    }
}
