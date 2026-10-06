package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerPreviewIdentityTest {
    @Test void sameModelIdCannotMakeOldCacheContentRepresentTheCurrentServerAppearance() {
        String oldHash="a".repeat(64),currentHash="b".repeat(64);
        assertFalse(ServerPreviewIdentity.mayShow("model","model",currentHash,oldHash));
        assertTrue(ServerPreviewIdentity.mayShow("model","model",currentHash,currentHash));
    }
    @Test void anUnknownCurrentAssetKeepsItsPreviewPlaceholderInsteadOfAnOldCachedVersion() {
        assertFalse(ServerPreviewIdentity.mayShow("model","model","","a".repeat(64)));
    }
    @Test void otherCardsAndOfflineBrowsingStillAllowLocalCachePreviews() {
        assertTrue(ServerPreviewIdentity.mayShow("other","current","b".repeat(64),"a".repeat(64)));
        assertTrue(ServerPreviewIdentity.mayShow("model","","","a".repeat(64)));
    }
}
