package com.simmc.meplayeractions.gameplay;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

interface GSitAccess {
    boolean enabled();

    Object createSeat(Player player);

    Object seat(Player player);

    boolean removeSeat(Object seat, boolean useSafeDismount);

    Object startCrawl(Player player);

    Object crawl(Player player);

    boolean stopCrawl(Object crawl);

    Object pose(Player player);

    String poseType(Player player);

    GSitAnchor visualAnchor(Player player);

    void registerPoseLifecycle(Plugin plugin, Listener listener, BiConsumer<Player, Object> beforeRemoval,
            Consumer<Throwable> onFailure);
}
