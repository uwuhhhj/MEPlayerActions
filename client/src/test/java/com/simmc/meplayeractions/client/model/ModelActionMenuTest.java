package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.ui.ModelActionMenu;
import com.simmc.meplayeractions.client.ui.ModelConfigSchema;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelActionMenuTest {
    private YsmModelProfile profile() {
        JsonObject manifest = JsonParser.parseString("""
            {"spec":2,"properties":{
              "extra_animation":{"extra0":"#config","#eyes":"眼睛","extra1":""},
              "extra_animation_buttons":[{"id":"config","name":"头饰","config_forms":[]}],
              "extra_animation_classify":[
                {"id":"eyes","extra_animation":{"blink":"眨眼","#other":"其他","#return":""}},
                {"id":"other","extra_animation":{"#eyes":"回环","#return":""}}
              ]}}
            """).getAsJsonObject();
        return new YsmModelProfile(manifest, new JsonObject(), new JsonObject(), Map.of(), List.of(), List.of(), "", "");
    }
    @Test void preservesAuthorOrderAndSeparatesClipKeyFromConfigLabel() {
        var menu = new ModelActionMenu(profile(), "zh_cn");
        assertEquals(List.of("extra0", "#eyes", "extra1"), menu.entries().stream().map(ModelActionMenu.Entry::id).toList());
        assertEquals("头饰", menu.entries().getFirst().label()); assertEquals("config", menu.entries().getFirst().configGroup());
        assertFalse(menu.entries().getFirst().category()); assertEquals("extra1", menu.entries().get(2).label());
    }
    @Test void categoryNavigationUsesAuthoredMapsAndRejectsCyclesWithoutChangingPath() {
        var menu = new ModelActionMenu(profile(), "zh_cn");
        assertTrue(menu.enter(menu.entries().get(1))); assertEquals("eyes", menu.categoryId());
        assertEquals("blink", menu.entries().getFirst().id()); assertTrue(menu.enter(menu.entries().get(1)));
        assertFalse(menu.enter(menu.entries().getFirst())); assertEquals("other", menu.categoryId());
        assertTrue(menu.enter(menu.entries().get(1))); assertEquals("eyes", menu.categoryId());
        assertTrue(menu.back()); assertEquals(0, menu.depth()); assertFalse(menu.back());
    }
    @Test void nativePropertyPathsLocalizeAuthorGroupsFormsChoicesAndActionDescriptions() {
        JsonObject manifest=JsonParser.parseString("""
            {"spec":2,"properties":{
              "extra_animation":{"extra0":"#cfg"},
              "extra_animation_buttons":[{"id":"cfg","name":"原始组","config_forms":[{
                "type":"radio","title":"原始标题","description":"原始说明","value":"v.mode","labels":{"原始选项":"v.mode=0;"}
              }]}]
            }}
            """).getAsJsonObject();
        JsonObject languages=JsonParser.parseString("""
            {"zh_cn":{"properties.extra_animation.extra0.desc":"动作说明","properties":{"extra_animation":{"extra0":"翻译动作"},
              "extra_animation_buttons":{"cfg":{"name":"翻译组","config_forms":[{
                "title":"翻译标题","description":"翻译说明","labels":["翻译选项"]
              }]}}}},"en_us":{"properties.extra_animation.extra0.desc":"English fallback"}}
            """).getAsJsonObject();
        var profile=new YsmModelProfile(manifest,languages,new JsonObject(),Map.of(),List.of(),List.of(),"","");
        var schema=ModelConfigSchema.from(profile,"zh_cn");var group=schema.groups().getFirst();var form=group.forms().getFirst();
        assertEquals("翻译组",group.name());assertEquals("翻译标题",form.title());assertEquals("翻译说明",form.description());
        assertEquals("翻译选项",form.choices().getFirst().label());
        var entry=new ModelActionMenu(profile,"zh_cn").entries().getFirst();assertEquals("翻译动作",entry.label());
        assertEquals("动作说明",entry.description());
        // A flat full-path entry remains supported alongside nested author translations.
        assertEquals("English fallback",new ModelActionMenu(profile,"de_de").entries().getFirst().description());
    }
    @Test void onlyFunctionsReachableFromAuthorFormsContributeSavedVariables() {
        JsonObject manifest=JsonParser.parseString("""
            {"spec":2,"properties":{"extra_animation_buttons":[{"id":"cfg","config_forms":[{
              "type":"radio","value":"v.mode","labels":{"调用":"fn.configure();"}
            }]}]}}
            """).getAsJsonObject();
        JsonObject functions=JsonParser.parseString("""
            {"configure":"v.eye=1; fn.nested();", "nested":"v.bow=0; fn.configure();", "physics":"v.physics_velocity=2;"}
            """).getAsJsonObject();
        var profile=new YsmModelProfile(manifest,new JsonObject(),new JsonObject(),Map.of(),List.of(),List.of(),"","",Map.of(),Map.of(),functions,new JsonObject());
        var variables=ModelConfigSchema.from(profile,"zh_cn").variables();
        assertTrue(variables.containsAll(java.util.Set.of("variable.mode","variable.eye","variable.bow")));
        assertFalse(variables.contains("variable.physics_velocity"));
    }
}
