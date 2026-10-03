package com.simmc.meplayeractions.me;

import org.bukkit.entity.Player;
import java.util.*;

/** Restore a retired native copy only while the original player is still legitimately tracked. */
final class NativeEntityRestoration {
    static List<Player> recipients(Player owner, Set<UUID> previousViewers) {
        if (previousViewers.isEmpty()) return List.of();
        return NativeEntityHiding.recipients(owner, id -> !previousViewers.contains(id));
    }
}
