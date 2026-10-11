package com.simmc.meplayeractions.client.render;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeBodyVisibilityTest {
    @Test void modelEngineHidingTheOriginalPlayerDoesNotHideTheReplacementBody() {
        var body = NativeBodyVisibility.resolve(true, true, true, false, 0);
        assertTrue(body.visible());
        assertFalse(body.outlineOnly());
    }

    @Test void genuineInvisibilityStillHidesTheReplacementEvenWithAnOutdatedVisibleFrame() {
        assertFalse(NativeBodyVisibility.resolve(true, true, false, true, 0).visible());
        assertFalse(NativeBodyVisibility.resolve(true, true, true, true, 0).visible());
    }

    @Test void genuineGlowingInvisibilityKeepsItsNativeOutline() {
        var body = NativeBodyVisibility.resolve(true, true, true, true, 0xff80ff80);
        assertTrue(body.visible());
        assertTrue(body.outlineOnly());
    }

    @Test void modelEngineHideAloneDoesNotTurnTheVisibleReplacementIntoAnOutline() {
        var body = NativeBodyVisibility.resolve(true, true, true, false, 0xff80ff80);
        assertTrue(body.visible());
        assertFalse(body.outlineOnly());
    }

    @Test void thePlayersShowSelfChoiceStillHidesTheReplacement() {
        assertFalse(NativeBodyVisibility.resolve(true, false, true, false, 0).visible());
        assertFalse(NativeBodyVisibility.resolve(true, false, true, true, 0xff80ff80).visible());
    }

    @Test void privateModelsAndOldServersKeepTheNativeInvisibilityRule() {
        assertFalse(NativeBodyVisibility.resolve(true, true, true, null, 0).visible());
        assertTrue(NativeBodyVisibility.resolve(true, true, false, null, 0).visible());
        assertTrue(NativeBodyVisibility.resolve(true, true, true, null, 0xff80ff80).outlineOnly());
    }

    @Test void ordinaryLegacyBbModelVisibilityKeepsItsExistingPolicy() {
        assertTrue(NativeBodyVisibility.resolve(false, true, true, null, 0).visible());
        assertFalse(NativeBodyVisibility.resolve(false, false, false, null, 0).visible());
        assertFalse(NativeBodyVisibility.resolve(false, true, true, null, 0xff80ff80).outlineOnly());
    }

    @Test void aMissingSourceFlagDoesNotGuessTheOriginalPlayersVisibility() {
        assertNull(NativeBodyVisibility.readSourceInvisible(new JsonObject()));
        JsonObject state = new JsonObject();
        state.addProperty("sourceInvisible", false);
        assertEquals(Boolean.FALSE, NativeBodyVisibility.readSourceInvisible(state));
        state.addProperty("sourceInvisible", true);
        assertEquals(Boolean.TRUE, NativeBodyVisibility.readSourceInvisible(state));
    }

    @Test void theOptionalSourceFlagIsStillStrictlyTyped() {
        JsonObject state = new JsonObject();
        state.addProperty("sourceInvisible", "false");
        assertThrows(IllegalArgumentException.class, () -> NativeBodyVisibility.readSourceInvisible(state));
        state.addProperty("sourceInvisible", 0);
        assertThrows(IllegalArgumentException.class, () -> NativeBodyVisibility.readSourceInvisible(state));
    }
}
