package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelPreferencesTest {
    @Test void oldConstructorAndDefaultsKeepTheWheelClosedAfterChoosingAnAction() {
        assertEquals(new WheelPreferences(WheelPreferences.Source.CLIENT, 0, 0, false), WheelPreferences.defaults());
        assertFalse(new WheelPreferences(WheelPreferences.Source.SERVER, 3, 7).keepOpen());
    }

    @Test void switchingSourcesRetainsSeparatePagesAndTheKeepOpenChoice() {
        var client = WheelPreferences.defaults().withPage(WheelPreferences.Source.CLIENT, 3).withKeepOpen(true);
        var server = client.withSource(WheelPreferences.Source.SERVER).withPage(WheelPreferences.Source.SERVER, 7);
        assertEquals(WheelPreferences.Source.SERVER, server.source());
        assertEquals(3, server.page(WheelPreferences.Source.CLIENT));
        assertEquals(7, server.page(WheelPreferences.Source.SERVER));
        var restored = server.withSource(WheelPreferences.Source.CLIENT);
        assertEquals(client.clientPage(), restored.clientPage());
        assertEquals(7, restored.serverPage()); assertTrue(restored.keepOpen());
        assertEquals(restored.withKeepOpen(false).withKeepOpen(true), restored);
    }

    @Test void aSmallerCatalogClampsOnlyTheDisplayedPageAndRetainsTheSavedMemory() {
        var preferences = new WheelPreferences(WheelPreferences.Source.CLIENT, 11, 127, true);
        assertEquals(2, preferences.page(WheelPreferences.Source.CLIENT, 3));
        assertEquals(0, preferences.page(WheelPreferences.Source.SERVER, 1));
        assertEquals(11, preferences.page(WheelPreferences.Source.CLIENT, 12));
        assertEquals(127, preferences.page(WheelPreferences.Source.SERVER, 128));
        assertEquals(11, preferences.clientPage()); assertEquals(127, preferences.serverPage());
    }

    @Test void invalidPageMemoryAndNullSourcesAreRejectedBeforeSaving() {
        for (int invalid : new int[]{-1, 128, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new WheelPreferences(WheelPreferences.Source.CLIENT, invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new WheelPreferences(WheelPreferences.Source.CLIENT, 0, invalid));
            assertThrows(IllegalArgumentException.class, () -> WheelPreferences.defaults().withPage(WheelPreferences.Source.CLIENT, invalid));
        }
        assertThrows(NullPointerException.class, () -> new WheelPreferences(null, 0, 0));
        assertThrows(NullPointerException.class, () -> WheelPreferences.defaults().withSource(null));
        assertThrows(NullPointerException.class, () -> WheelPreferences.defaults().page(null));
        assertThrows(IllegalArgumentException.class, () -> WheelPreferences.defaults().page(WheelPreferences.Source.CLIENT, 0));
    }
}
