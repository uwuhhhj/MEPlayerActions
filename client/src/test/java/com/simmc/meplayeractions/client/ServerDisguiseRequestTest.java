package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerDisguiseRequestTest {
    private static final ServerDisguisePreferences DEFAULT = ServerDisguisePreferences.defaults();
    private static final ServerDisguisePreferences SCALED = new ServerDisguisePreferences(1.5,null,null,null,null,null,null);
    private static final ServerDisguisePreferences SMALL = new ServerDisguisePreferences(.8,null,null,null,null,null,null);
    private static final ServerDisguisePreferences ACTUAL = new ServerDisguisePreferences(1.5,true,true,8d,10,0,"");

    @Test void sendingDoesNotConfirmAndOldOrDifferentBindingStatesCannotCompleteTheRequest() {
        var request = new ServerDisguiseRequest();
        request.sent("ysm_01_jk", "old", SCALED, 10);
        assertEquals("ysm_01_jk", request.modelId()); assertTrue(request.status().contains("等待服务器确认"));
        assertFalse(request.confirmed("ysm_01_jk", "old"));
        assertFalse(request.confirmed("ysm_02_jk", "new"));
        assertFalse(request.confirmed("ysm_01_jk", ""));
        assertEquals("ysm_01_jk", request.modelId());
        assertTrue(request.preferencesNeedApply("ysm_01_jk", "old", SCALED));
        assertTrue(request.confirmed("ysm_01_jk", "new"));
        assertEquals("", request.modelId()); assertFalse(request.preferencesNeedApply("ysm_01_jk", "new", SCALED));
    }

    @Test void newlySavedSettingsRemainDirtyUntilANewInstanceConfirmsTheMatchingRequestSnapshot() {
        var request = new ServerDisguiseRequest();
        request.sent("model", "", DEFAULT, 0); request.confirmed("model", "first");
        assertFalse(request.preferencesNeedApply("model", "first", DEFAULT));
        assertTrue(request.preferencesNeedApply("model", "first", SCALED));
        request.sent("model", "first", SCALED, 1);
        assertTrue(request.preferencesNeedApply("model", "first", SCALED), "Sending cannot mark parameters as applied");
        assertFalse(request.confirmed("model", "first"));
        assertTrue(request.confirmed("model", "second"));
        assertFalse(request.preferencesNeedApply("model", "second", SCALED));
        assertTrue(request.preferencesNeedApply("model", "second", SMALL), "Editing while waiting does not rewrite the sent parameter snapshot");
        assertTrue(request.preferencesNeedApply("model", "second", DEFAULT), "Inherited defaults can be reapplied after explicit overrides");
    }

    @Test void externalInstancesAndDifferentModelsCannotInheritThePreviousParametersConfirmation() {
        var request = new ServerDisguiseRequest();
        assertFalse(request.preferencesNeedApply("model", "external", DEFAULT));
        assertTrue(request.preferencesNeedApply("model", "external", SCALED));
        request.sent("model", "external", SCALED, 0); request.confirmed("model", "confirmed");
        assertFalse(request.preferencesNeedApply("model", "confirmed", SCALED));
        assertTrue(request.preferencesNeedApply("model", "replacement", SCALED));
        assertTrue(request.preferencesNeedApply("other", "confirmed", SCALED));
    }

    @Test void expiryClearsPendingSelectionAndProvidesAnActionableStatusAtTheExactBound() {
        var request = new ServerDisguiseRequest();
        request.sent("model", "", DEFAULT, 100);
        assertFalse(request.expire(100 + ServerDisguiseRequest.TIMEOUT - 1));
        assertEquals("model", request.modelId());
        assertTrue(request.expire(100 + ServerDisguiseRequest.TIMEOUT));
        assertEquals("", request.modelId()); assertTrue(request.status().contains("未确认"));
        assertFalse(request.expire(100 + 2 * ServerDisguiseRequest.TIMEOUT));
        assertFalse(request.confirmed("model", "late"), "A later unrelated state cannot confirm an expired command");
    }

    @Test void failureDoesNotErasePreviousAppliedParametersOrConfirmTheFailedNewOnes() {
        var request = new ServerDisguiseRequest();
        request.sent("model", "", SCALED, 0); request.confirmed("model", "current");
        request.sent("model", "current", SMALL, 1); request.failed("权限已撤销");
        assertEquals("", request.modelId()); assertEquals("权限已撤销", request.status());
        assertFalse(request.preferencesNeedApply("model", "current", SCALED));
        assertTrue(request.preferencesNeedApply("model", "current", SMALL));
    }

    @Test void sessionResetDropsPendingAndPreviousServerConfirmations() {
        var request = new ServerDisguiseRequest();
        request.sent("model", "", SCALED, 0); request.confirmed("model", "current");
        request.sent("other", "current", DEFAULT, 1); request.reset();
        assertEquals("", request.modelId()); assertEquals("", request.status());
        assertTrue(request.preferencesNeedApply("model", "current", SCALED));
        assertFalse(request.preferencesNeedApply("model", "current", DEFAULT));
        assertFalse(request.confirmed("other", "next"));
    }

    @Test void unsafeOrMissingPendingIdentityAndPreferenceDataAreRejected() {
        var request = new ServerDisguiseRequest();
        assertThrows(IllegalArgumentException.class, () -> request.sent("model extra", "", DEFAULT, 0));
        assertThrows(NullPointerException.class, () -> request.sent("model", null, DEFAULT, 0));
        assertThrows(NullPointerException.class, () -> request.sent("model", "", null, 0));
    }

    @Test void negotiatedIdempotentResultCanConfirmTheExistingInstanceOnlyWithItsExactNonce() {
        var request = new ServerDisguiseRequest(); var nonce = java.util.UUID.randomUUID();
        request.sent(nonce,"model","same",SCALED,true,0);
        assertFalse(request.confirmed("model","same"));
        assertFalse(request.confirmed("model","new"),"A negotiated session requires the command result even for a new instance");
        assertFalse(request.result(java.util.UUID.randomUUID(),"model",true,"same",ACTUAL,""));
        assertFalse(request.result(nonce,"other",true,"same",ACTUAL,""));
        assertTrue(request.result(nonce,"model",true,"same",ACTUAL,""));
        assertTrue(request.confirmed("model","same"));
        assertFalse(request.preferencesNeedApply("model","same",SCALED));
    }

    @Test void aCommandResultAloneDoesNotConfirmAnAppearanceBeforeItsMatchingBindingState() {
        var request = new ServerDisguiseRequest(); var nonce = java.util.UUID.randomUUID();
        request.sent(nonce,"model","old",SCALED,true,0);
        assertTrue(request.result(nonce,"model",true,"new",ACTUAL,""));
        assertEquals("model",request.modelId());
        assertTrue(request.preferencesNeedApply("model","old",SCALED));
        assertFalse(request.confirmed("model","old"));
        assertFalse(request.confirmed("other","new"));
        assertTrue(request.confirmed("model","new"));
        assertFalse(request.result(nonce,"model",true,"new",ACTUAL,""),"A consumed result cannot change a later selection");
    }

    @Test void failedResultsAndChangedActualParametersClearPendingWithoutMarkingPreferencesApplied() {
        var request = new ServerDisguiseRequest(); var nonce = java.util.UUID.randomUUID();
        request.sent(nonce,"model","old",SCALED,true,0);
        assertFalse(request.result(java.util.UUID.randomUUID(),"model",false,"",null,"denied"));
        assertTrue(request.result(nonce,"model",false,"",null,"denied"));
        assertEquals("",request.modelId()); assertTrue(request.status().contains("denied"));
        request.sent(nonce,"model","old",SMALL,true,1);
        assertTrue(request.result(nonce,"model",true,"new",ACTUAL,""));
        assertEquals("",request.modelId()); assertTrue(request.status().contains("不一致"));
        assertFalse(request.confirmed("model","new"));
        assertTrue(request.preferencesNeedApply("model","new",SMALL));
    }

    @Test void allExplicitParametersAreComparedAgainstTheActualServerResult() {
        var request = new ServerDisguiseRequest(); var nonce = java.util.UUID.randomUUID();
        var requested = new ServerDisguisePreferences(.8,false,false,20d,5,2,"slowness:2:30");
        request.sent(nonce,"model","old",requested,true,0);
        assertTrue(request.result(nonce,"model",true,"new",requested,""));
        assertTrue(request.confirmed("model","new"));
        assertFalse(request.preferencesNeedApply("model","new",requested));
        var changed = new ServerDisguisePreferences(.8,false,false,20d,5,3,"slowness:2:30");
        request.sent(nonce,"model","new",requested,true,1);
        assertTrue(request.result(nonce,"model",true,"replacement",changed,""));
        assertEquals("",request.modelId()); assertTrue(request.status().contains("不一致"));
    }
    @Test void explicitlySavedDefaultsCanBeAppliedToAnExternalInstanceWithUnknownOverrides() {
        var request=new ServerDisguiseRequest();
        assertFalse(request.preferencesNeedApply("model","external",DEFAULT));
        request.preferencesSaved("model"); assertTrue(request.preferencesNeedApply("model","external",DEFAULT));
        var nonce=java.util.UUID.randomUUID(); request.sent(nonce,"model","external",DEFAULT,true,0);
        assertTrue(request.result(nonce,"model",true,"defaults-applied",ACTUAL,""));
        assertTrue(request.confirmed("model","defaults-applied"));
        assertFalse(request.preferencesNeedApply("model","defaults-applied",DEFAULT));
        request.preferencesSaved("model"); request.reset();
        assertFalse(request.preferencesNeedApply("model","external",DEFAULT));
    }

    @Test void aNewSaveWhileWaitingCannotBeClearedByTheOlderRequestConfirmation() {
        var request=new ServerDisguiseRequest(); request.preferencesSaved("model");
        request.sent("model","old",SCALED,0); request.preferencesSaved("model");
        assertTrue(request.confirmed("model","new"));
        assertTrue(request.preferencesNeedApply("model","new",SCALED));
    }

}
