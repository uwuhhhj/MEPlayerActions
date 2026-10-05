package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GalleryPreviewSelectionTest {
    @Test void clickingNewYearPreviewsItWhileTaishoRemainsTheCurrentAppearance() {
        String currentId = BuiltinYsmModels.TAISHO_MAID_ID;
        var selection = new GalleryPreviewSelection(currentId);
        assertEquals(new GalleryPreviewSelection.Target(GalleryPreviewSelection.Source.CURRENT, currentId),
                selection.target(currentId, "taisho", "taisho"));

        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        assertEquals(new GalleryPreviewSelection.Target(GalleryPreviewSelection.Source.SELECTED, BuiltinYsmModels.NEW_YEAR_ID),
                selection.target(currentId, "taisho", "new-year"),
                "The browsing draft must win over the enabled world model");
    }

    @Test void unloadedOrFailedSelectionNeverFallsBackToTheOldEnabledModel() {
        var selection = new GalleryPreviewSelection(BuiltinYsmModels.TAISHO_MAID_ID);
        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        assertEquals(new GalleryPreviewSelection.Target(GalleryPreviewSelection.Source.SELECTED, BuiltinYsmModels.NEW_YEAR_ID),
                selection.target(BuiltinYsmModels.TAISHO_MAID_ID, "taisho", null));
        assertEquals(new GalleryPreviewSelection.Target(GalleryPreviewSelection.Source.SELECTED, BuiltinYsmModels.NEW_YEAR_ID),
                selection.target("", "", null), "A disabled world appearance does not prevent browsing");
    }

    @Test void olderAsyncCompletionCannotRestartANewerOrReselectedPreview() {
        var selection = new GalleryPreviewSelection(BuiltinYsmModels.TAISHO_MAID_ID);
        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        var first = selection.request(BuiltinYsmModels.NEW_YEAR_ID);
        selection.browse(BuiltinYsmModels.ASTRONAUT_ID);
        var second = selection.request(BuiltinYsmModels.ASTRONAUT_ID);
        assertFalse(selection.isSelectedRequest(first));
        assertTrue(selection.isSelectedRequest(second));
        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        assertFalse(selection.isSelectedRequest(first), "Reselecting the same ID is a new preview instance");
        assertFalse(selection.isSelectedRequest(second));
        assertTrue(selection.isSelectedRequest(selection.request(BuiltinYsmModels.NEW_YEAR_ID)));
    }

    @Test void currentLabelRequiresTheAppliedContentHashRatherThanOnlyTheSameModelId() {
        var selection = new GalleryPreviewSelection(BuiltinYsmModels.NEW_YEAR_ID);
        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        assertEquals(GalleryPreviewSelection.Source.SELECTED,
                selection.target(BuiltinYsmModels.NEW_YEAR_ID, "old-content", "edited-content").source());
        assertEquals(GalleryPreviewSelection.Source.CURRENT,
                selection.target(BuiltinYsmModels.NEW_YEAR_ID, "edited-content", "edited-content").source(),
                "Only the world binding's exact loaded asset is labelled current");
    }

    @Test void savedDefaultIsNotDisplayedAsEnabledBeforeAnyCardWasBrowsed() {
        var selection = new GalleryPreviewSelection(BuiltinYsmModels.DEFAULT_ID);
        assertEquals(GalleryPreviewSelection.Source.VANILLA, selection.target("", "", "default").source());
        assertEquals(new GalleryPreviewSelection.Target(GalleryPreviewSelection.Source.CURRENT, BuiltinYsmModels.TAISHO_MAID_ID),
                selection.target(BuiltinYsmModels.TAISHO_MAID_ID, "taisho", "default"));
    }

    @Test void removingTheSelectedModelDiscardsItsAsyncTokenWithoutApplyingTheFallback() {
        var selection = new GalleryPreviewSelection(BuiltinYsmModels.NEW_YEAR_ID);
        selection.browse(BuiltinYsmModels.NEW_YEAR_ID);
        var removed = selection.request(BuiltinYsmModels.NEW_YEAR_ID);
        selection.retain(List.of(BuiltinYsmModels.DEFAULT_ID));
        assertEquals(BuiltinYsmModels.DEFAULT_ID, selection.modelId());
        assertFalse(selection.isSelectedRequest(removed));
        assertEquals(GalleryPreviewSelection.Source.VANILLA, selection.target("", "", "default").source());
        selection.retain(List.of());
        assertEquals("", selection.modelId());
    }
}
