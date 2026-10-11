package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GalleryModelUseStateTest {
    @Test
    void sendingACommandRemainsPendingUntilTheAuthoritativeBindingChanges() {
        GalleryModelUseState.State sent = GalleryModelUseState.server("new-model", "old-model", "new-model", false);
        assertTrue(sent.waiting());
        assertFalse(sent.current());
        assertFalse(sent.canUse());
        assertEquals("等待服务器确认", sent.buttonLabel());

        GalleryModelUseState.State confirmed = GalleryModelUseState.server("new-model", "new-model", "", true);
        assertTrue(confirmed.current());
        assertFalse(confirmed.canUse());
        assertEquals("当前使用", confirmed.buttonLabel());
        assertTrue(GalleryModelUseState.server("old-model", "new-model", "", true).canUse(),
                "Changing the binding must make the previous card applicable without reopening the screen");
    }

    @Test
    void aServerBindingIsCurrentWithoutPreviewAssetsOrALocalRenderingLease() {
        GalleryModelUseState.State state = GalleryModelUseState.server("bound-model", "bound-model", "", false);
        assertTrue(state.current());
        assertFalse(state.canUse());
    }

    @Test
    void failedOrExpiredRequestsDoNotLeaveAnUnconfirmedCurrentModel() {
        assertTrue(GalleryModelUseState.server("requested", "", "requested", false).waiting());
        GalleryModelUseState.State expired = GalleryModelUseState.server("requested", "", "", true);
        assertFalse(expired.current());
        assertFalse(expired.waiting());
        assertTrue(expired.canUse());
    }

    @Test
    void savedServerSettingsRequireExplicitApplicationAndAnotherBindingConfirmation() {
        GalleryModelUseState.State draft = GalleryModelUseState.server("bound", "bound", "", true, true);
        assertTrue(draft.current());
        assertTrue(draft.canUse());
        assertEquals("应用设置", draft.buttonLabel());
        GalleryModelUseState.State pending = GalleryModelUseState.server("bound", "bound", "bound", false, true);
        assertTrue(pending.waiting());
        assertFalse(pending.canUse());
        GalleryModelUseState.State confirmed = GalleryModelUseState.server("bound", "bound", "", true, false);
        assertTrue(confirmed.current());
        assertFalse(confirmed.canUse());
    }

    @Test
    void loadingPrivateContentBecomesCurrentOnlyAfterTheExactAssetIsApplied() {
        GalleryModelUseState.State loading = GalleryModelUseState.local("private", "content", "", "", "private", true,
                true);
        assertTrue(loading.waiting());
        assertFalse(loading.current());
        assertFalse(loading.canUse());
        GalleryModelUseState.State applied = GalleryModelUseState.local("private", "content", "private", "content", "",
                true, true);
        assertTrue(applied.current());
        assertFalse(applied.canUse());
        GalleryModelUseState.State edited = GalleryModelUseState.local("private", "edited", "private", "content", "",
                true, true);
        assertFalse(edited.current());
        assertTrue(edited.canUse(), "An edited file with the same ID can be applied");
        GalleryModelUseState.State replacing = GalleryModelUseState.local("private", "content", "private", "content",
                "private", true, true);
        assertTrue(replacing.waiting());
        assertFalse(replacing.canUse(), "Reloading the same selection still waits for application");
    }

    @Test
    void aServerDisguiseBlocksPrivateApplicationAndSavedDefaultsAreNotCurrent() {
        GalleryModelUseState.State blocked = GalleryModelUseState.local("private", "content", "private", "content", "",
                false, true);
        assertFalse(blocked.current());
        assertFalse(blocked.canUse());
        GalleryModelUseState.State unbound = GalleryModelUseState.local("private", "content", "", "", "", true, true);
        assertFalse(unbound.current());
        assertTrue(unbound.canUse(),
                "Removing the server disguise permits an explicit application, never automatic private restoration");
    }
}
