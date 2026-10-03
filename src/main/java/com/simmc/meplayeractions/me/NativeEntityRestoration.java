package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.nms.entity.wrapper.TrackedEntity;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.Function;

/** Restore a retired native copy only while the original player is still legitimately tracked. */
final class NativeEntityRestoration {
    static List<Player> recipients(Player owner, Set<UUID> previousViewers, TrackedEntity tracked,
                                   Function<UUID, Player> lookup) {
        if (!owner.isOnline() || tracked == null || previousViewers.isEmpty()) return List.of();
        Set<UUID> live = tracked.getTrackedPlayer();
        List<Player> recipients = new ArrayList<>();
        for (UUID id : previousViewers) {
            if (!live.contains(id) || id.equals(owner.getUniqueId())) continue;
            Player viewer = lookup.apply(id);
            if (viewer != null && viewer.isOnline() && viewer.getWorld().equals(owner.getWorld()) && viewer.canSee(owner))
                recipients.add(viewer);
        }
        return List.copyOf(recipients);
    }
}
