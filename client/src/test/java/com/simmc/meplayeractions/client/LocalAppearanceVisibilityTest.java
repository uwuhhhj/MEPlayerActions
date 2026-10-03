package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalAppearanceVisibilityTest {
    @Test void independentWorldsCanRenderWhileAnAvailableBridgeWaitsForACompleteSnapshot() {
        var visibility=new LocalAppearanceVisibility();
        assertTrue(visibility.canRender(false,false,false));
        assertFalse(visibility.canRender(true,false,false));
        visibility.serverSessionStarted();
        assertFalse(visibility.canRender(true,true,false));
        visibility.beginSnapshot(10);visibility.endSnapshot(10);
        assertFalse(visibility.hasServerAppearance());assertTrue(visibility.canRender(true,true,false));
    }

    @Test void knownServerDisguiseNeedsItsOwnTakeoverAndSurvivesFallbackUnbinds() {
        for(String reason:List.of("render_failed","model-change","instance_or_asset_changed","state_payload_limit","render_lease_expired","not_visible_or_active")) {
            var visibility=withServerDisguise("first");
            assertTrue(visibility.canRender(true,true,true));
            assertFalse(visibility.canRender(true,true,false),"ME fallback must not overlap the private model");
            visibility.serverUnbound("first",reason);
            assertTrue(visibility.hasServerAppearance(),reason);
            assertFalse(visibility.canRender(true,true,false),reason);
            assertFalse(visibility.canRender(false,false,false),"Loss of bridge does not prove a known server disguise ended");
        }
    }

    @Test void explicitMatchingDisguiseRemovalAllowsIndependentRenderingAgain() {
        for(String reason:List.of("command","external-undisguise","plugin-close","plugin_stopping","sync_disabled",
                "registration-failed","death","quit","offline","session_ended")) {
            var visibility=withServerDisguise("first");visibility.serverUnbound("first",reason);
            assertFalse(visibility.hasServerAppearance(),reason);
            assertTrue(visibility.canRender(false,false,false),reason);
        }
    }

    @Test void anOldInstanceCannotRemoveTheReplacementDisguise() {
        var visibility=withServerDisguise("first");
        visibility.serverOwnState("replacement");visibility.serverUnbound("first","command");
        assertTrue(visibility.hasServerAppearance());assertFalse(visibility.canRender(true,true,false));
        visibility.serverUnbound("replacement","command");assertFalse(visibility.hasServerAppearance());
    }

    @Test void onlyTheMatchingCompleteSnapshotCanProveThereIsNoOwnServerDisguise() {
        var visibility=withServerDisguise("first");
        visibility.endSnapshot(20);assertTrue(visibility.hasServerAppearance());
        visibility.beginSnapshot(21);visibility.endSnapshot(20);assertTrue(visibility.hasServerAppearance());
        visibility.endSnapshot(21);assertFalse(visibility.hasServerAppearance());
        assertTrue(visibility.canRender(true,true,false));
        visibility.serverOwnState("replacement");
        visibility.endSnapshot(21);assertTrue(visibility.hasServerAppearance(),"A duplicate old end cannot erase a later disguise");
    }

    @Test void anOwnStateInsideTheSnapshotKeepsTheDisguiseKnown() {
        var visibility=withServerDisguise("first");
        visibility.beginSnapshot(30);visibility.serverOwnState("replacement");visibility.endSnapshot(30);
        assertTrue(visibility.hasServerAppearance());assertFalse(visibility.canRender(true,true,false));
        assertTrue(visibility.canRender(true,true,true));
    }

    @Test void aFailedOwnStateDeliveryDuringSnapshotDoesNotProveTheDisguiseEnded() {
        var visibility=withServerDisguise("first");
        visibility.beginSnapshot(35);visibility.serverUnbound("first","state_payload_limit");visibility.endSnapshot(35);
        assertTrue(visibility.hasServerAppearance());assertFalse(visibility.canRender(true,true,false));
        visibility.beginSnapshot(36);visibility.endSnapshot(36);
        assertFalse(visibility.hasServerAppearance());assertTrue(visibility.canRender(true,true,false));
    }

    @Test void restartingTheBridgeKeepsFallbackSuppressionUntilNewSnapshotAndWorldResetClearsIt() {
        var visibility=withServerDisguise("first");visibility.serverSessionStarted();
        assertTrue(visibility.hasServerAppearance());assertFalse(visibility.canRender(true,true,true));
        visibility.beginSnapshot(40);visibility.endSnapshot(40);
        assertFalse(visibility.hasServerAppearance());assertTrue(visibility.canRender(true,true,false));
        visibility.serverOwnState("replacement");visibility.reset();
        assertFalse(visibility.hasServerAppearance());assertTrue(visibility.canRender(false,false,false));
        assertFalse(visibility.canRender(true,true,false),"The next supported world must obtain its own snapshot");
    }

    private static LocalAppearanceVisibility withServerDisguise(String instance) {
        var visibility=new LocalAppearanceVisibility();visibility.serverSessionStarted();
        visibility.beginSnapshot(1);visibility.serverOwnState(instance);visibility.endSnapshot(1);
        return visibility;
    }
}
