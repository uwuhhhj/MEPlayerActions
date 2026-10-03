package com.simmc.meplayeractions.me;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Select native copies from Paper tracking, independent of ME's filtered async audience. */
final class NativeEntityHiding {
    static List<Player> recipients(Player owner, Predicate<UUID> excludedViewer) {
        if (!owner.isOnline()) return List.of();
        List<Player> recipients = new ArrayList<>();
        for (Player viewer : owner.getTrackedBy()) {
            if (viewer == null || !viewer.isOnline() || viewer.getUniqueId().equals(owner.getUniqueId())
                    || !viewer.getWorld().equals(owner.getWorld()) || !viewer.canSee(owner)
                    || excludedViewer.test(viewer.getUniqueId())) continue;
            recipients.add(viewer);
        }
        return List.copyOf(recipients);
    }

    /** A delayed hide must never act on a replacement session or a restored base. */
    static <T> Runnable afterTick(T expected, Supplier<T> current, BooleanSupplier stillHidden, Runnable reconcile) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(current);
        Objects.requireNonNull(stillHidden);
        Objects.requireNonNull(reconcile);
        return () -> {
            if (expected == current.get() && stillHidden.getAsBoolean()) reconcile.run();
        };
    }
}
