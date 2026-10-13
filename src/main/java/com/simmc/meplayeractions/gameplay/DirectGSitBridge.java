package com.simmc.meplayeractions.gameplay;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.PrePlayerStopPoseEvent;
import dev.geco.gsit.model.Crawl;
import dev.geco.gsit.model.Pose;
import dev.geco.gsit.model.Seat;
import dev.geco.gsit.model.StopReason;
import dev.geco.gsit.service.SitService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class DirectGSitBridge implements GSitAccess {
    private final Plugin dependency;
    private final SitService sitService;

    DirectGSitBridge(Plugin dependency) {
        this.dependency = dependency;
        if (GSitAPI.getInstance() != dependency) {
            throw new IllegalStateException("GSit API instance does not match the enabled plugin");
        }
        this.sitService = Objects.requireNonNull(GSitAPI.getInstance().getSitService(), "GSit SitService");
        this.sitService.getBaseOffset();
        Objects.requireNonNull(GSitAPI.getAllSeats(), "GSit seats");
        Objects.requireNonNull(GSitAPI.getAllCrawls(), "GSit crawls");
        Objects.requireNonNull(GSitAPI.getAllPoses(), "GSit poses");
    }

    @Override
    public boolean enabled() {
        return this.dependency.isEnabled();
    }

    @Override
    public Object createSeat(Player player) {
        // Stay at the player's X/Z instead of snapping to the center of the supporting block.
        Block block = player.getLocation().clone().subtract(0.0, 0.1, 0.0).getBlock();
        return GSitAPI.createSeat(block, player, true, 0.0, 0.0, 0.0, player.getLocation().getYaw(), false);
    }

    @Override
    public Object seat(Player player) {
        return GSitAPI.getSeatByEntity(player);
    }

    @Override
    public boolean removeSeat(Object seat, boolean useSafeDismount) {
        return GSitAPI.removeSeat((Seat) seat, StopReason.PLUGIN, useSafeDismount);
    }

    @Override
    public Object startCrawl(Player player) {
        return GSitAPI.startCrawl(player);
    }

    @Override
    public Object crawl(Player player) {
        return GSitAPI.getCrawlByPlayer(player);
    }

    @Override
    public boolean stopCrawl(Object crawl) {
        return GSitAPI.stopCrawl((Crawl) crawl, StopReason.PLUGIN);
    }

    @Override
    public Object pose(Player player) {
        return GSitAPI.getPoseByPlayer(player);
    }

    @Override
    public String poseType(Player player) {
        Pose pose = GSitAPI.getPoseByPlayer(player);
        return pose == null ? "" : pose.getPoseType().name();
    }

    @Override
    public GSitAnchor visualAnchor(Player player) {
        Pose pose = GSitAPI.getPoseByPlayer(player);
        Seat seat = pose == null ? GSitAPI.getSeatByEntity(player) : pose.getSeat();
        return seat == null ? null : GSitAnchor.of(seat.getLocation(), this.sitService.getBaseOffset());
    }

    @Override
    public void registerPoseLifecycle(Plugin plugin, Listener listener, BiConsumer<Player, Object> beforeRemoval,
            Consumer<Throwable> onFailure) {
        plugin.getServer().getPluginManager().registerEvent(PrePlayerStopPoseEvent.class, listener,
                EventPriority.MONITOR, (registered, event) -> {
                    try {
                        PrePlayerStopPoseEvent stop = (PrePlayerStopPoseEvent) event;
                        beforeRemoval.accept(stop.getPlayer(), stop.getPose());
                    } catch (RuntimeException | LinkageError failure) {
                        onFailure.accept(failure);
                    }
                }, plugin, true);
    }
}
