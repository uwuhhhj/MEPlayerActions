package com.simmc.meplayeractions.client;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Session-local source selection; a new server disguise starts with the server's appearance. */
public final class PlayerInteractionPolicy {
    public enum Scope { CLIENT, SERVER }
    private boolean serverOwnModelPresent, serverBridgeReady;
    private boolean localMode = true;
    private String serverInstance = "";

    /** Failure or reload may temporarily remove a binding; an empty identity preserves the known instance. */
    public void observe(boolean present, String instance, boolean bridgeReady) {
        Objects.requireNonNull(instance);
        if (!present) {
            localMode = true;
            serverInstance = "";
        } else {
            if (!serverOwnModelPresent || (!instance.isEmpty() && !instance.equals(serverInstance))) localMode = false;
            if (!instance.isEmpty()) serverInstance = instance;
        }
        serverOwnModelPresent = present;
        serverBridgeReady = bridgeReady;
    }

    public void reset() {
        serverOwnModelPresent = false;
        serverBridgeReady = false;
        localMode = true;
        serverInstance = "";
    }

    /** Explicit client selection is allowed during a disguise, but never acknowledges a render lease. */
    public boolean selectSource(boolean local) {
        if (!local && !serverOwnModelPresent) return false;
        localMode = local;
        return true;
    }

    public static boolean isServerDisguised(UUID owner, UUID localPlayer, Collection<UUID> serverOwners,
                                             boolean knownSelfAppearance) {
        Objects.requireNonNull(serverOwners);
        return owner != null && (serverOwners.contains(owner) || owner.equals(localPlayer) && knownSelfAppearance);
    }

    public boolean serverOwnModelPresent() { return serverOwnModelPresent; }
    public boolean serverBridgeReady() { return serverBridgeReady; }
    public String serverInstance() { return serverInstance; }
    public boolean interactionLocalMode() { return localMode; }
    public Scope preferredScope() { return localMode ? Scope.CLIENT : Scope.SERVER; }
    public boolean allowsScope(Scope scope) { return Objects.requireNonNull(scope) == Scope.CLIENT || serverOwnModelPresent; }
    public boolean canEditLocalAppearance() { return localMode; }
    public boolean canUseLocalAppearance() { return localMode; }
    public boolean canUseLocalActions() { return localMode; }
    public boolean canUseServerActions() { return serverOwnModelPresent && serverBridgeReady; }
    public String suspensionReason() {
        return serverOwnModelPresent && !localMode ? "当前使用服务器下发；私人配置已保留，可手动切到客户端" : "";
    }
}
