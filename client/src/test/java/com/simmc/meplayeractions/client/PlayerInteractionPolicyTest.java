package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerInteractionPolicyTest {
    private static final UUID SELF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REMOTE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test void independentClientAppearanceDoesNotNeedAWorldOrServerBridge() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(false, "", false);
        assertEquals(PlayerInteractionPolicy.Scope.CLIENT, policy.preferredScope());
        assertTrue(policy.canEditLocalAppearance()); assertTrue(policy.canActivateLocalAppearance());
        assertTrue(policy.canUseLocalAppearance()); assertTrue(policy.canUseLocalActions());
        assertFalse(policy.canUseServerActions()); assertFalse(policy.selectSource(false));
        assertEquals("", policy.suspensionReason());
        assertFalse(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(REMOTE), false));
        assertFalse(PlayerInteractionPolicy.isServerDisguised(null, null, List.of(), false));
    }

    @Test void aServerDisguiseOwnsTheOnlyAppearanceBeforeAssetsOrHandshakeAreReady() {
        var policy = new PlayerInteractionPolicy();
        assertTrue(policy.isCurrentLocalAppearance(true, true));
        policy.observe(true, "first", false);
        assertTrue(policy.serverOwnModelPresent()); assertFalse(policy.interactionLocalMode());
        assertEquals(PlayerInteractionPolicy.Scope.SERVER, policy.preferredScope());
        assertTrue(policy.canEditLocalAppearance(), "Browsing/configuring a private model is independent of applying it");
        assertFalse(policy.canActivateLocalAppearance()); assertFalse(policy.canUseLocalAppearance());
        assertFalse(policy.canUseLocalActions()); assertFalse(policy.canUseServerActions());
        assertFalse(policy.isCurrentLocalAppearance(true, true)); assertFalse(policy.suspensionReason().isEmpty());
        policy.observe(true, "first", true);
        assertTrue(policy.canUseServerActions(), "Server actions do not require a client GPU lease");
        assertFalse(policy.canUseLocalAppearance());
    }

    @Test void neitherSourceSelectionNorAnActivationAttemptCanOverrideTheServer() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        assertFalse(policy.allowsScope(PlayerInteractionPolicy.Scope.CLIENT));
        assertTrue(policy.allowsScope(PlayerInteractionPolicy.Scope.SERVER));
        assertFalse(policy.selectSource(true)); assertFalse(policy.activateLocalAppearance());
        assertFalse(policy.interactionLocalMode()); assertFalse(policy.canUseLocalActions());
        assertTrue(policy.selectSource(false)); assertTrue(policy.canUseServerActions());
        assertTrue(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(SELF, REMOTE), false));
        assertTrue(PlayerInteractionPolicy.isServerDisguised(REMOTE, SELF, List.of(REMOTE), false));
        assertFalse(PlayerInteractionPolicy.isServerDisguised(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                SELF, List.of(SELF, REMOTE), true));
    }

    @Test void sameInstanceSnapshotsReloadsAndTemporaryBindingLossPreserveServerPriority() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        policy.observe(true, "first", false); assertFalse(policy.canUseLocalAppearance());
        policy.observe(true, "", false); assertEquals("first", policy.serverInstance());
        assertFalse(policy.selectSource(true)); assertFalse(policy.activateLocalAppearance());
        assertTrue(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(), true),
                "A failed lease does not prove that the server fallback disguise was removed");
        policy.observe(true, "replacement", true);
        assertEquals("replacement", policy.serverInstance()); assertFalse(policy.canUseLocalAppearance());
    }

    @Test void authoritativeRemovalKeepsPrivateRenderingPausedUntilExplicitModelUse() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        policy.observe(false, "", true);
        assertTrue(policy.interactionLocalMode()); assertEquals("", policy.serverInstance());
        assertFalse(policy.canUseServerActions()); assertTrue(policy.canActivateLocalAppearance());
        assertTrue(policy.localSelectionPaused()); assertFalse(policy.canUseLocalAppearance());
        assertFalse(policy.isCurrentLocalAppearance(true, true), "A saved enabled draft is not a currently used model");
        assertTrue(policy.selectSource(true));
        assertFalse(policy.canUseLocalAppearance(), "Changing a browser/wheel source does not apply a model");
        assertTrue(policy.activateLocalAppearance()); assertTrue(policy.canUseLocalAppearance());
        assertTrue(policy.isCurrentLocalAppearance(true, true));
        policy.observe(true, "replacement", true); assertFalse(policy.canUseLocalAppearance());
    }

    @Test void onlyAuthoritativeRemovalAllowsALaterExplicitPrivateActivationAfterFailedTakeover() {
        var visibility = withServerDisguise("first");
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        visibility.serverUnbound("first", "render_failed");
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertFalse(policy.activateLocalAppearance());
        visibility.serverUnbound("older", "command");
        policy.observe(visibility.hasServerAppearance(), "", true); assertFalse(policy.activateLocalAppearance());
        visibility.beginSnapshot(2); visibility.endSnapshot(1);
        policy.observe(visibility.hasServerAppearance(), "", true); assertFalse(policy.activateLocalAppearance());
        visibility.endSnapshot(2); policy.observe(visibility.hasServerAppearance(), "", true);
        assertTrue(policy.interactionLocalMode()); assertFalse(policy.canUseLocalAppearance());
        assertTrue(policy.activateLocalAppearance()); assertTrue(policy.canUseLocalAppearance());
        assertTrue(visibility.canRender(true, true, false));
    }

    @Test void aWorldOrSessionResetCannotImplicitlyResumeASelectionPausedByAServer() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true); policy.reset();
        assertTrue(policy.interactionLocalMode()); assertFalse(policy.serverOwnModelPresent());
        assertFalse(policy.serverBridgeReady()); assertEquals("", policy.serverInstance());
        assertFalse(policy.canUseLocalAppearance());
        policy.observe(false, "", true); assertFalse(policy.canUseLocalAppearance());
        assertTrue(policy.activateLocalAppearance()); assertTrue(policy.canUseLocalAppearance());
        policy.reset(); assertTrue(policy.canUseLocalAppearance(), "An independent active selection retains its ordinary world reset behavior");
    }

    @Test void enabledDraftsAndUnpreparedAssetsCannotBeReportedAsCurrentlyUsedPrivateModels() {
        var policy = new PlayerInteractionPolicy();
        assertFalse(policy.isCurrentLocalAppearance(true, false));
        assertFalse(policy.isCurrentLocalAppearance(false, true));
        assertTrue(policy.isCurrentLocalAppearance(true, true));
        policy.observe(true, "server", true); assertFalse(policy.isCurrentLocalAppearance(true, true));
        policy.observe(false, "", true); assertFalse(policy.isCurrentLocalAppearance(true, true));
        policy.activateLocalAppearance(); assertTrue(policy.isCurrentLocalAppearance(true, true));
    }

    @Test void restoringTheSavedSuspensionDoesNotActivateAnEnabledDraftOrPermitAServerOverride() {
        var policy = new PlayerInteractionPolicy();
        policy.pauseLocalAppearance(); policy.observe(false,"",false);
        assertTrue(policy.canEditLocalAppearance()); assertFalse(policy.isCurrentLocalAppearance(true,true));
        assertTrue(policy.selectSource(true)); assertFalse(policy.canUseLocalAppearance());
        policy.observe(true,"server",true); assertFalse(policy.activateLocalAppearance());
        policy.observe(false,"",true); assertFalse(policy.canUseLocalAppearance());
        policy.activateLocalAppearance(); assertTrue(policy.isCurrentLocalAppearance(true,true));
    }

    private static LocalAppearanceVisibility withServerDisguise(String instance) {
        var visibility = new LocalAppearanceVisibility();
        visibility.serverSessionStarted(); visibility.beginSnapshot(1);
        visibility.serverOwnState(instance); visibility.endSnapshot(1); return visibility;
    }
}
