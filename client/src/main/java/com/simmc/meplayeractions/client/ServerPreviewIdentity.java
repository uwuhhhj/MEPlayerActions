package com.simmc.meplayeractions.client;

/** A directory ID is permission metadata; only an exact content hash can represent the current appearance. */
public final class ServerPreviewIdentity {
    private ServerPreviewIdentity() { }
    public static boolean mayShow(String selectedId, String currentId, String currentHash, String candidateHash) {
        if(currentId.isEmpty() || !currentId.equals(selectedId))return true;
        return !currentHash.isEmpty() && currentHash.equals(candidateHash);
    }
}
