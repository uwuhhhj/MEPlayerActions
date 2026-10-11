package com.simmc.meplayeractions.config;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AnimationLabelsTest {
    private YamlConfiguration config() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }
    @Test void everyBundledPlayerAndNpcAnimationHasChineseFallbackWithoutConfigEdits() throws Exception {
        var c = config(); c.set("menu", null); var settings = Settings.load(c);
        for (String model : java.util.List.of("ysm_02_jk", "ysm_01_jk")) {
            var root = JsonParser.parseString(Files.readString(Path.of("examples/blueprints/" + model + ".bbmodel"))).getAsJsonObject();
            for (var element : root.getAsJsonArray("animations")) {
                String id = element.getAsJsonObject().get("name").getAsString();
                String label = settings.animationLabel(id);
                assertNotEquals("自定义动作", label, id);
                assertTrue(label.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN), id);
            }
        }
    }
    @Test void configuredLabelsAndCustomActionLabelsRetainTheirOwnPrecedence() {
        var c = config(); c.set("menu.animation-labels.idle", "安静站立"); c.set("menu.animation-labels.custom_clip", "自定义舞蹈");
        c.set("custom-actions.greeting.animation", "idle"); c.set("custom-actions.wave.label", "招手问好");
        var settings = Settings.load(c);
        assertEquals("安静站立", settings.animationLabel("idle")); assertEquals("安静站立", settings.actionLabel("greeting"));
        assertEquals("招手问好", settings.actionLabel("wave")); assertEquals("自定义舞蹈", settings.animationLabel("custom_clip"));
        assertEquals("unknown_clip", settings.animationLabel("unknown_clip"));
        c.set("menu.animation-labels.idle", " "); assertThrows(IllegalArgumentException.class, () -> Settings.load(c));
    }
}
