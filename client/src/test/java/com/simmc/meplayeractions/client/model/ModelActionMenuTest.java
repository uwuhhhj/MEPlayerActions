package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.ui.ModelActionMenu;
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
}
