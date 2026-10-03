package com.simmc.meplayeractions.client;

import java.util.Objects;
import java.util.Set;

/** A private mesh must not overlap the server fallback for an already known self disguise. */
final class LocalAppearanceVisibility {
    private static final Set<String> REMOVED = Set.of("command", "external-undisguise", "plugin-close",
            "plugin_stopping", "sync_disabled", "registration-failed", "death", "quit", "offline", "session_ended");
    private String serverInstance = "";
    private long snapshot = -1;
    private boolean snapshotOwn, snapshotUncertain, snapshotComplete;

    void reset() {
        serverInstance = ""; snapshot = -1; snapshotOwn = false; snapshotUncertain = false; snapshotComplete = false;
    }
    void serverSessionStarted() {
        snapshot = -1; snapshotOwn = false; snapshotUncertain = false; snapshotComplete = false;
    }
    void serverOwnState(String instance) {
        serverInstance = Objects.requireNonNull(instance);
        if (snapshot >= 0) snapshotOwn = true;
    }
    void serverUnbound(String instance, String reason) {
        if (!serverInstance.equals(instance)) return;
        if (REMOVED.contains(reason)) serverInstance = "";
        else if (snapshot >= 0) snapshotUncertain = true;
    }
    void beginSnapshot(long id) {
        snapshot = id; snapshotOwn = false; snapshotUncertain = false;
    }
    void endSnapshot(long id) {
        if (snapshot < 0 || snapshot != id) return;
        if (!snapshotOwn && !snapshotUncertain) serverInstance = "";
        snapshotComplete = true; snapshot = -1;
    }
    boolean hasServerAppearance() { return !serverInstance.isEmpty(); }
    boolean canRender(boolean bridgeAvailable, boolean bridgeReady, boolean ownServerReady) {
        if (bridgeAvailable && (!bridgeReady || !snapshotComplete)) return false;
        return !hasServerAppearance() || ownServerReady;
    }
}
