package com.simmc.meplayeractions.client;

import java.util.Objects;
import java.util.Set;

/** A private mesh must not overlap the server fallback for an already known self disguise. */
final class LocalAppearanceVisibility
{
    private static final Set<String> REMOVED = Set.of("command", "external-undisguise", "plugin-close",
            "plugin_stopping", "sync_disabled", "registration-failed", "death", "quit", "offline", "session_ended");
    private String serverInstance = "";
    private long snapshot = -1;
    private boolean snapshotOwn, snapshotUncertain, snapshotComplete;

    void reset()
    {
        this.serverInstance = "";
        this.snapshot = -1;
        this.snapshotOwn = false;
        this.snapshotUncertain = false;
        this.snapshotComplete = false;
    }

    void serverSessionStarted()
    {
        this.snapshot = -1;
        this.snapshotOwn = false;
        this.snapshotUncertain = false;
        this.snapshotComplete = false;
    }

    void serverOwnState(String instance)
    {
        this.serverInstance = Objects.requireNonNull(instance);
        if (this.snapshot >= 0)
        {
            this.snapshotOwn = true;
        }

    }

    void serverUnbound(String instance, String reason)
    {
        if (!this.serverInstance.equals(instance))
        {
            return;
        }

        if (REMOVED.contains(reason))
        {
            this.serverInstance = "";
        }

        else if (this.snapshot >= 0)
        {
            this.snapshotUncertain = true;
        }

    }

    void beginSnapshot(long id)
    {
        this.snapshot = id;
        this.snapshotOwn = false;
        this.snapshotUncertain = false;
    }

    void endSnapshot(long id)
    {
        if (this.snapshot < 0 || this.snapshot != id)
        {
            return;
        }

        if (!this.snapshotOwn && !this.snapshotUncertain)
        {
            this.serverInstance = "";
        }

        this.snapshotComplete = true;
        this.snapshot = -1;
    }

    boolean hasServerAppearance()
    {
        return !this.serverInstance.isEmpty();
    }

    boolean canRender(boolean bridgeAvailable, boolean bridgeReady, boolean ownServerReady)
    {
        if (bridgeAvailable && (!bridgeReady || !this.snapshotComplete))
        {
            return false;
        }

        return !hasServerAppearance() || ownServerReady;
    }
}
