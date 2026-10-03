package com.simmc.meplayeractions.action;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DisguiseOptionsTest {
    private DisguiseOptions parse(String... args) {
        return DisguiseOptions.parse(args, DisguiseOptions.defaults("ysm_01_jk", 1, true, 2));
    }
    @Test void modelMustPrecedeEveryOverride() {
        assertEquals(DisguiseOptions.defaults("ysm_02_jk", 1, true, 2), parse("disguise", "ysm_02_jk"));
        for (String[] args : List.of(new String[]{"disguise"}, new String[]{"disguise", "scale=0.8"},
                new String[]{"disguise", "--scale", "0.8", "ysm_02_jk"},
                new String[]{"disguise", "ysm_02_jk", "ysm_01_jk"}))
            assertThrows(IllegalArgumentException.class, () -> parse(args));
    }
    @Test void selfIsVisibleByDefaultButCommandCanHideOnlyTheModel() {
        var defaults = parse("disguise", "ysm_02_jk"); assertTrue(defaults.showSelf()); assertTrue(defaults.hideSelf());
        var hidden = parse("disguise", "ysm_02_jk", "show-self=false"); assertFalse(hidden.showSelf()); assertTrue(hidden.hideSelf());
    }
    @Test void combinedOverridesSupportBothFlagFormsAndSeparateModelFromBaseVisibility() {
        var options = parse("disguise", "ysm_02_jk", "--scale", "0.75", "hide-self=false",
                "--delay=4", "effect=slowness:2:60", "--show-self", "on", "view-distance=12", "max-viewers=20");
        assertEquals(0.75, options.scale()); assertFalse(options.hideSelf()); assertEquals(4, options.visualDelay());
        assertTrue(options.showSelf()); assertEquals(12, options.viewDistance()); assertEquals(20, options.maxViewers());
        assertEquals(List.of(new DisguiseOptions.Effect("slowness", 2, 60)), options.effects());
        assertTrue(options.description().contains("自己可见 true"));
        assertTrue(options.description().contains("可视距离 < 12.0"));
    }
    @Test void malformedOrOutOfRangeOptionsCannotSilentlyChangeDefaults() {
        for (String value : List.of("NaN", "Infinity", "0", "-1", "8.01", "oops"))
            assertThrows(IllegalArgumentException.class, () -> parse("disguise", "ysm_02_jk", "scale=" + value));
        for (String option : List.of("delay=21", "delay=-1", "delay=0.5", "hide-self=maybe", "show-self=maybe", "bogus=1",
                "view-distance=NaN", "view-distance=Infinity", "view-distance=0", "view-distance=256.1", "view-distance=abc",
                "max-viewers=-1", "max-viewers=1001", "max-viewers=1.5", "effect=slowness:0", "effect=slowness:257",
                "effect=slowness:1:0", "effect=slowness:1:86401", "effect=slowness::60", "effect=slowness:1:60:extra", "effect=", "--scale"))
            assertThrows(IllegalArgumentException.class, () -> parse("disguise", "ysm_02_jk", option), option);
        assertEquals(0, parse("disguise", "ysm_02_jk", "max-viewers=0").maxViewers());
    }
    @Test void onlySlownessIsAcceptedEvenWhenServerRegistersOtherEffects() {
        for (String id : List.of("speed", "jump_boost", "night_vision", "instant_health", "not_a_real_effect")) {
            assertThrows(IllegalArgumentException.class, () -> parse("disguise", "ysm_02_jk", "effect=" + id));
            assertThrows(IllegalArgumentException.class, () -> new DisguiseOptions("ysm_02_jk", 1, true, 2,
                    false, 8, 10, List.of(new DisguiseOptions.Effect(id, 1, 0))));
        }
        assertDoesNotThrow(() -> parse("disguise", "ysm_02_jk", "effect=slowness").requireEffects(id -> id.equals("slowness")));
        assertThrows(IllegalArgumentException.class, () -> parse("disguise", "ysm_02_jk", "effect=slowness").requireEffects(id -> false));
    }
    @Test void duplicateOverridesAreRejectedBeforeUse() {
        for (String key : List.of("scale", "show-self", "view-distance", "max-viewers", "effect")) {
            String value = switch (key) { case "scale", "view-distance", "max-viewers" -> "1"; case "effect" -> "slowness"; default -> "false"; };
            assertThrows(IllegalArgumentException.class, () -> parse("disguise", "ysm_02_jk", key + "=" + value, "--" + key + "=" + value));
        }
    }
    @Test void completionSuggestsOnlyModelsFirstThenPermittedKeysAndValues() {
        var models = List.of("ysm_01_jk", "ysm_02_jk"); var effects = List.of("slowness", "speed");
        assertEquals(models, DisguiseOptions.suggestions(new String[]{"disguise", ""}, models, effects));
        assertTrue(DisguiseOptions.suggestions(new String[]{"disguise", "scale="}, models, effects).isEmpty());
        assertEquals(List.of("effect=slowness:1"), DisguiseOptions.suggestions(new String[]{"disguise", "ysm_02_jk", "effect="}, models, effects));
        assertTrue(DisguiseOptions.suggestions(new String[]{"disguise", "ysm_02_jk", "effect="}, models, List.of()).isEmpty());
        assertTrue(DisguiseOptions.suggestions(new String[]{"disguise", "ysm_02_jk", "--scale", ""}, models, effects).contains("0.8"));
        assertFalse(DisguiseOptions.suggestions(new String[]{"disguise", "ysm_02_jk", "show-self=false", ""}, models, effects).contains("show-self="));
        assertEquals(List.of("view-distance=8"), DisguiseOptions.suggestions(new String[]{"disguise", "ysm_02_jk", "view-distance=8"}, models, effects));
    }
}
