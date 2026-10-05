package com.simmc.meplayeractions.server;

import com.simmc.meplayeractions.MEPlayerActionsPlugin;
import com.simmc.meplayeractions.client.PrivateModelSyncService;
import com.simmc.meplayeractions.command.CommandLayout;
import com.simmc.meplayeractions.config.PerformanceSettings;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import static com.simmc.meplayeractions.MEPlayerActionsPlugin.message;

/** A relay-only mode. No Settings, actions, ModelEngine or gameplay classes are linked here. */
public final class PrivateOnlyBackend implements ServerBackend, Listener {
    private final MEPlayerActionsPlugin plugin;
    private final String reason;
    private final PrivateModelSyncService.Policy policy;
    private final PerformanceSettings performance;
    private PrivateModelSyncService relay;

    public PrivateOnlyBackend(MEPlayerActionsPlugin plugin, ConfigurationSection config, String reason) {
        this.plugin = Objects.requireNonNull(plugin);
        this.reason = Objects.requireNonNull(reason);
        policy = PrivateModelSyncService.Policy.fromConfiguration(config);
        performance = PerformanceSettings.fromConfiguration(config);
    }
    @Override public void start() {
        relay = new PrivateModelSyncService(plugin, Set::of);
        relay.configure(policy);
        relay.configurePerformance(performance);
        relay.enable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    @Override public String diagnosis() {
        return "仅私人多人同步（" + reason + "）；" + (policy.enabled() ? "私人同步已开启" : "私人同步关闭");
    }
    @Override public void status(CommandSender sender) {
        message(sender, "模式：" + diagnosis());
        message(sender, "开关：client-sync.enabled 与 client-sync.private-models.enabled；发布权限 mact.private.upload，观看权限 mact.private.view。");
        if (sender instanceof Player player) message(sender, relay.status(player));
        else message(sender, "发布与观看各自需要显式许可；观看距离 " + policy.viewDistance() + " 格，最多 " + policy.maxViewers() + " 名其他玩家。");
    }
    @Override public void handleAction(Player player, String[] args) {
        String[] normalized = CommandLayout.normalize(args);
        MEPlayerActionsPlugin.permission(player, MEPlayerActionsPlugin.commandPermission(normalized[0]));
        switch (normalized[0]) {
            case "help" -> {
                message(player, "模式：" + diagnosis());
                message(player, "私人外观在客户端选取并显式开启多人同步；服务器同时开启两项开关并授予发布/观看权限后才能共享。");
                message(player, CommandLayout.PREFIX + " status 查看诊断；reload 重载配置。服务器伪装与动作需要 ModelEngine R4.1.1。");
            }
            case "status" -> status(player);
            default -> message(player, "服务器伪装与动作后端不可用：" + reason + "。私人模型多人同步使用客户端的显式共享开关。");
        }
    }
    @Override public List<String> tabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("mact.use")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("help", "status", "reload").stream()
                .filter(root -> sender.hasPermission(MEPlayerActionsPlugin.commandPermission(root)) && root.startsWith(prefix)).toList();
    }
    @Override public void forget(Player player) { if (relay != null) relay.forget(player); }
    @EventHandler public void worldChanged(PlayerChangedWorldEvent event) {
        if (relay != null) relay.observationChanged(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (relay != null && player.isOnline()) relay.observationChanged(player);
        });
    }
    @Override public void close() {
        HandlerList.unregisterAll(this);
        if (relay != null) { relay.close(); relay = null; }
    }
}
