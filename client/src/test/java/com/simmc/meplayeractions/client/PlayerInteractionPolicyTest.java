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
        assertTrue(policy.canEditLocalAppearance());
        assertTrue(policy.canUseLocalAppearance());
        assertTrue(policy.canUseLocalActions());
        assertFalse(policy.canUseServerActions());
        assertFalse(policy.selectSource(false));
        assertEquals("", policy.suspensionReason());
        assertFalse(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(REMOTE), false));
        assertFalse(PlayerInteractionPolicy.isServerDisguised(null, null, List.of(), false));
    }

    @Test void newServerDisguiseDefaultsToServerEvenBeforeAssetsOrHandshakeAreReady() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", false);
        assertTrue(policy.serverOwnModelPresent());
        assertFalse(policy.interactionLocalMode());
        assertEquals(PlayerInteractionPolicy.Scope.SERVER, policy.preferredScope());
        assertFalse(policy.canEditLocalAppearance());
        assertFalse(policy.canUseLocalAppearance());
        assertFalse(policy.canUseLocalActions());
        assertFalse(policy.canUseServerActions());
        assertFalse(policy.suspensionReason().isEmpty());
        policy.observe(true, "first", true);
        assertTrue(policy.canUseServerActions(), "Server actions do not require a client GPU lease");
        assertFalse(policy.canUseLocalAppearance());
    }

    @Test void anExplicitClientChoiceKeepsServerOwnershipAndEquipmentRules() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        assertTrue(policy.allowsScope(PlayerInteractionPolicy.Scope.CLIENT));
        assertTrue(policy.selectSource(true));
        assertTrue(policy.interactionLocalMode());
        assertTrue(policy.canEditLocalAppearance());
        assertTrue(policy.canUseLocalActions());
        assertTrue(policy.serverOwnModelPresent());
        assertTrue(policy.canUseServerActions());
        assertEquals("", policy.suspensionReason());
        assertTrue(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(SELF, REMOTE), false));
        assertTrue(PlayerInteractionPolicy.isServerDisguised(REMOTE, SELF, List.of(REMOTE), false));
        assertFalse(PlayerInteractionPolicy.isServerDisguised(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                SELF, List.of(SELF, REMOTE), true));
        assertFalse(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(), false),
                "An independent private appearance does not own remote or vanilla equipment rules");
    }

    @Test void manualChoiceSurvivesSameInstanceSnapshotsReloadsAndTemporaryBindingLoss() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        policy.selectSource(true);
        policy.observe(true, "first", false);
        assertTrue(policy.interactionLocalMode());
        policy.observe(true, "", false);
        assertTrue(policy.interactionLocalMode());
        assertEquals("first", policy.serverInstance());
        assertTrue(PlayerInteractionPolicy.isServerDisguised(SELF, SELF, List.of(), true),
                "A failed lease does not prove that the server fallback disguise was removed");
        policy.observe(true, "first", true);
        assertTrue(policy.interactionLocalMode());
    }

    @Test void switchingBackStopsThePrivateScopeAndTheNextInstanceAgainDefaultsToServer() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        policy.selectSource(true);
        assertTrue(policy.selectSource(false));
        assertFalse(policy.canUseLocalActions());
        policy.selectSource(true);
        policy.observe(true, "replacement", true);
        assertFalse(policy.interactionLocalMode());
        assertEquals("replacement", policy.serverInstance());
        policy.selectSource(true);
        policy.observe(false, "", true);
        assertTrue(policy.interactionLocalMode());
        assertEquals("", policy.serverInstance());
        assertFalse(policy.canUseServerActions());
        policy.observe(true, "replacement", true);
        assertFalse(policy.interactionLocalMode(), "Even a reused identity is a new disguise after confirmed removal");
    }

    @Test void explicitClientSelectionCannotBypassTheExistingFallbackVisibilityGuard() {
        var visibility = withServerDisguise("first");
        var policy = new PlayerInteractionPolicy();
        policy.observe(visibility.hasServerAppearance(), "first", true);
        policy.selectSource(true);
        assertTrue(policy.canUseLocalAppearance());
        assertFalse(visibility.canRender(true, true, false), "ME fallback must remain the only visible mesh");
        assertTrue(visibility.canRender(true, true, true), "A confirmed takeover allows the selected private mesh");
        visibility.serverUnbound("first", "render_failed");
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertTrue(policy.interactionLocalMode());
        assertFalse(visibility.canRender(true, true, false));
        visibility.serverSessionStarted();
        assertFalse(visibility.canRender(true, true, true), "A new handshake still requires its complete snapshot");
    }

    @Test void onlyAnAuthoritativeRemovalResumesClientModeAfterFailedServerTakeover() {
        var visibility = withServerDisguise("first");
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        visibility.serverUnbound("first", "render_failed");
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertFalse(policy.interactionLocalMode());
        visibility.serverUnbound("older", "command");
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertFalse(policy.interactionLocalMode());
        visibility.beginSnapshot(2);
        visibility.endSnapshot(1);
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertFalse(policy.interactionLocalMode());
        visibility.endSnapshot(2);
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertTrue(policy.interactionLocalMode());
        assertTrue(visibility.canRender(true, true, false));
        visibility.serverOwnState("next");
        policy.observe(visibility.hasServerAppearance(), "next", true);
        visibility.serverUnbound("next", "command");
        policy.observe(visibility.hasServerAppearance(), "", true);
        assertTrue(policy.interactionLocalMode());
    }

    @Test void worldResetDoesNotCarryAnOverrideIntoTheNextServerDisguise() {
        var policy = new PlayerInteractionPolicy();
        policy.observe(true, "first", true);
        policy.selectSource(true);
        policy.reset();
        assertTrue(policy.interactionLocalMode());
        assertFalse(policy.serverOwnModelPresent());
        assertFalse(policy.serverBridgeReady());
        assertEquals("", policy.serverInstance());
        policy.observe(true, "first", true);
        assertFalse(policy.interactionLocalMode());
    }

    private static LocalAppearanceVisibility withServerDisguise(String instance) {
        var visibility = new LocalAppearanceVisibility();
        visibility.serverSessionStarted();
        visibility.beginSnapshot(1);
        visibility.serverOwnState(instance);
        visibility.endSnapshot(1);
        return visibility;
    }
}
