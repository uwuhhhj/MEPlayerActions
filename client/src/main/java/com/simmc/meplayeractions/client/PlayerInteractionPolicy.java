package com.simmc.meplayeractions.client;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** A server disguise always owns the appearance; a paused private selection resumes only on explicit use. */
public final class PlayerInteractionPolicy {
    public enum Scope { CLIENT, SERVER }
    private boolean serverOwnModelPresent, serverBridgeReady;
    private boolean localSelectionPaused;
    private String serverInstance = "";

    /** Failure or reload may temporarily remove a binding; an empty identity preserves the known instance. */
    public void observe(boolean present, String instance, boolean bridgeReady) {
        Objects.requireNonNull(instance);
        if (!present) {
            serverInstance = "";
        } else {
            localSelectionPaused = true;
            if (!instance.isEmpty()) serverInstance = instance;
        }
        serverOwnModelPresent = present;
        serverBridgeReady = bridgeReady;
    }

    public void reset() {
        // Reconnect/world changes must not turn a retained private draft into a fresh implicit selection.
        if (serverOwnModelPresent) localSelectionPaused = true;
        serverOwnModelPresent = false;
        serverBridgeReady = false;
        serverInstance = "";
    }

    /** Selects an action scope only; browsing a client tab cannot reactivate a paused private model. */
    public boolean selectSource(boolean local) {
        return local ? !serverOwnModelPresent : serverOwnModelPresent;
    }

    public boolean activateLocalAppearance() {
        if (serverOwnModelPresent) return false;
        localSelectionPaused = false;
        return true;
    }
    /** Restore a saved suspension without treating any saved model draft as an activation. */
    public void pauseLocalAppearance() { localSelectionPaused = true; }

    public static boolean isServerDisguised(UUID owner, UUID localPlayer, Collection<UUID> serverOwners,
                                             boolean knownSelfAppearance) {
        Objects.requireNonNull(serverOwners);
        return owner != null && (serverOwners.contains(owner) || owner.equals(localPlayer) && knownSelfAppearance);
    }

    public boolean serverOwnModelPresent() { return serverOwnModelPresent; }
    public boolean serverBridgeReady() { return serverBridgeReady; }
    public String serverInstance() { return serverInstance; }
    public boolean interactionLocalMode() { return !serverOwnModelPresent; }
    public Scope preferredScope() { return serverOwnModelPresent ? Scope.SERVER : Scope.CLIENT; }
    public boolean allowsScope(Scope scope) { return (Objects.requireNonNull(scope) == Scope.SERVER) == serverOwnModelPresent; }
    /** Browser and configuration edits are allowed independently of the currently applied source. */
    public boolean canEditLocalAppearance() { return true; }
    public boolean canActivateLocalAppearance() { return !serverOwnModelPresent; }
    public boolean localSelectionPaused() { return localSelectionPaused; }
    public boolean canUseLocalAppearance() { return !serverOwnModelPresent && !localSelectionPaused; }
    public boolean canUseLocalActions() { return canUseLocalAppearance(); }
    public boolean isCurrentLocalAppearance(boolean configuredEnabled, boolean applied) {
        return canUseLocalAppearance() && configuredEnabled && applied;
    }
    public boolean canUseServerActions() { return serverOwnModelPresent && serverBridgeReady; }
    public String suspensionReason() {
        return serverOwnModelPresent ? "当前使用服务端伪装；请先解除服务端伪装，再使用私人模型"
                : localSelectionPaused ? "私人模型已暂停；点击使用模型后恢复" : "";
    }
}
