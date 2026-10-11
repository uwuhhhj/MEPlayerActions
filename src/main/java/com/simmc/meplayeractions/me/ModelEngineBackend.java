package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.MEPlayerActionsPlugin;
import com.simmc.meplayeractions.server.ServerBackend;
import com.simmc.meplayeractions.server.ServerMessages;
import static com.simmc.meplayeractions.MEPlayerActionsPlugin.message;
import com.simmc.meplayeractions.action.ActionController;
import com.simmc.meplayeractions.action.DisguiseOptions;
import com.simmc.meplayeractions.action.SyncFeature;
import com.simmc.meplayeractions.client.ClientSyncService;
import com.simmc.meplayeractions.config.AnimationLabels;
import com.simmc.meplayeractions.config.Settings;
import com.simmc.meplayeractions.gameplay.GameplayBackend;
import com.simmc.meplayeractions.gameplay.PaperEffectPort;
import com.simmc.meplayeractions.ui.ActionMenu;
import com.simmc.meplayeractions.command.CommandLayout;
import com.simmc.meplayeractions.command.DisguiseRequestId;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import com.ticxo.modelengine.api.ModelEngineAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockBreakEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.ticxo.modelengine.api.events.BoneTransformReadEvent;
import org.bukkit.event.player.*;

import java.util.*;

public final class ModelEngineBackend implements ServerBackend, Listener {
    private final MEPlayerActionsPlugin plugin;
    private boolean running;
    private Settings settings;
    private ActionController controller;
    private ModelEngineBridge bridge;
    private GameplayBackend gameplay;
    private ClientSyncService clients;
    private ActionMenu menu;

    public ModelEngineBackend(MEPlayerActionsPlugin plugin, FileConfiguration config) {
        this.plugin = Objects.requireNonNull(plugin);
        settings = Settings.load(config);
    }
    @Override public boolean modelEngine() { return true; }
    @Override public void start() {
        gameplay = new GameplayBackend(plugin);
        bridge = new ModelEngineBridge();
        bridge.configurePerformance(settings.performance);
        bridge.configureResources(plugin, plugin.resources());
        controller = new ActionController(plugin, settings, bridge, gameplay);
        clients = new ClientSyncService(plugin, controller::snapshots,
                request -> plugin.handleAction(request.player(), CommandLayout.clientAction(request.action(), request.argument())));
        clients.configure(settings.clientEnabled, settings.clientMaxPayload,
                settings.clientCooldownTicks, settings.clientViewDistance);
        clients.configureResources(plugin.resources());
        clients.writability(viewer -> ModelEngineAPI.getNetworkHandler().getPipeline(viewer)
                .map(pipeline -> pipeline.getChannel().isWritable()).orElse(false));
        clients.snapshotSources(controller::ownerIds, controller::snapshot);
        clients.modelCatalog(controller::models);
        clients.configurePerformance(settings.performance);
        clients.configurePrivateModels(settings.privateModels);
        clients.audience(controller::canView);
        clients.rendering(new ClientSyncService.RenderControl() {
            @Override public boolean set(UUID viewer, UUID owner, UUID instance, boolean enabled) {
                return controller.localRendering(viewer, owner, instance, enabled);
            }
            @Override public boolean pending(UUID viewer, UUID owner, UUID instance) {
                return bridge.localRenderingPending(viewer, owner);
            }
        });
        controller.clients(clients);
        menu = new ActionMenu(plugin, () -> controller, () -> settings, plugin::handleAction);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        clients.enable(); controller.start(); running = true;
    }
    @Override public String diagnosis() { return "ModelEngine R4.1.1 动作后端；" + gameplay.diagnosis(); }
    @Override public void status(CommandSender sender) {
        message(sender, "§e服务器模型：ModelEngine 已注册蓝图（/meg disguise 同源）。");
        message(sender, "§7MPA models/ 只提供客户端资源；资源准备不会替代 ME 的模型注册。");
        message(sender, "§7私人多人分享：" + (settings.privateModels.enabled() ? "§a开启§7；发布和观看各自需要许可。" : "关闭。"));
        message(sender, "§7姿态后端：" + gameplay.diagnosis());
    }
    @Override public void playerStatus(CommandSender sender, Player player) {
        message(sender, "§e玩家：§f" + player.getName());
        if (controller.controlled(player)) controller.debug(player).forEach(line -> message(sender, line));
        else {
            message(sender, "§7当前没有 MPA 管理的服务器伪装。");
            List<String> attached = controller.existingModels(player);
            ServerMessages.list("§7当前附着的 ME 模型：", attached).forEach(line -> message(sender, line));
            if (!attached.isEmpty()) message(sender, "§e用 /meplayeractions attach" + (attached.size() == 1 ? "" : " <模型名>") + " 管理现有动作。");
            clients.statusLines(player).forEach(line -> message(sender, line));
        }
    }
    @Override public void close() {
        running = false;
        HandlerList.unregisterAll(this);
        try { if (menu != null) { HandlerList.unregisterAll(menu); menu.closeAll(); } }
        finally {
            try { if (controller != null) controller.close(); }
            finally {
                try { if (gameplay != null) gameplay.close(); }
                finally {
                    try { if (clients != null) clients.close(); }
                    finally { if (bridge != null) bridge.closeNativeRendering(); }
                }
            }
        }
    }
    @Override public void handleAction(Player player, String[] args) {
        ClientSyncService.DisguiseRequest resultRequest = null;
        try {
            DisguiseRequestId.Parsed request = DisguiseRequestId.parse(args);
            args = request.arguments();
            if (clients != null && request.requestId() != null) {
                resultRequest = clients.beginDisguiseRequest(player, request.requestId(), request.modelId(), request.fingerprint(), request.wireBytes());
                if (resultRequest != null && !resultRequest.execute()) return;
            }
            ActionController.permission(player, "mact.use");
            args = CommandLayout.normalize(args);
            String sub = args[0];
            switch (sub) {
                case "help" -> help(player);
                case "disguise" -> {
                    ActionController.permission(player, "mact.disguise");
                    var options = DisguiseOptions.parse(args, settings);
                    controller.disguise(player, options);
                    if (resultRequest != null) clients.disguiseSucceeded(player, resultRequest,
                            controller.snapshot(player.getUniqueId()).instance(), controller.disguiseOptions(player));
                    ServerMessages.disguise(controller.disguiseOptions(player)).forEach(line -> message(player, line));
                    clients.modelResourceLines(controller.modelId(player), controller.snapshot(player.getUniqueId()).localRenderable())
                            .forEach(line -> message(player, line));
                }
                case "attach" -> {
                    ActionController.permission(player, "mact.disguise");
                    if (args.length > 1) controller.attach(player, Settings.id(args[1]));
                    else controller.attach(player);
                    message(player, "§a已管理现有 ME 模型的动作：§f" + controller.modelId(player));
                    message(player, "§7保留 ME 原有伪装参数；客户端接管需资源准备与观看者确认。");
                }
                case "undisguise" -> {
                    ActionController.permission(player, "mact.disguise");
                    if (!controller.controlled(player)) throw new IllegalStateException("当前没有动作会话");
                    boolean owned = controller.remove(player, "command");
                    message(player, owned ? "伪装已解除。" : "动作接管已停止；原生伪装用 /meg undisguise 解除。");
                }
                case "models" -> {
                    ServerMessages.list("§e已注册的 ModelEngine 蓝图：", controller.models()).forEach(line -> message(player, line));
                    message(player, "§7使用 /meplayeractions disguise <模型名>；无需在 MPA 配置或 models/ 重复登记。");
                    message(player, "§7模型名沿用安全格式：1–64 位小写英文、数字、_、-。");
                }
                case "animations" -> {
                    ServerMessages.list("§e当前模型动作：", controller.animations(player)
                            .stream().map(clip -> AnimationLabels.displayWithId(clip, settings.animationLabel(clip))).toList())
                            .forEach(line -> message(player, line));
                    message(player, "§7播放：/meplayeractions play <动作 ID>；J 轮盘或 menu 可直接选择。");
                }
                case "menu" -> menu.open(player, 0);
                case "play" -> {
                    if (args.length < 2) throw new IllegalArgumentException("用法：/meplayeractions play <动作> [速度] [ONCE|LOOP|HOLD]");
                    Double speed = args.length > 2 ? Double.valueOf(args[2]) : null;
                    LoopMode loop = args.length > 3 ? LoopMode.valueOf(args[3].toUpperCase(Locale.ROOT)) : null;
                    controller.play(player, Settings.id(args[1]), speed, loop);
                    message(player, "播放：" + AnimationLabels.displayWithId(args[1], settings.actionLabel(args[1])));
                }
                case "stop" -> { controller.stop(player); message(player, "手动动作已停止。"); }
                case "reset" -> {
                    controller.clearDisguiseEffects(player); controller.reset(player, true);
                    message(player, "动作、本插件姿态与伪装药水已重置。");
                }
                case "sit" -> {
                    ActionController.permission(player, "mact.sit"); controller.sit(player);
                    message(player, "已坐下。Shift 或 /meplayeractions reset 起身。");
                }
                case "crawl" -> {
                    ActionController.permission(player, "mact.crawl"); controller.crawl(player);
                    message(player, "已进入爬行。/meplayeractions reset 退出。");
                }
                case "fly" -> {
                    boolean enable = args.length < 2 ? !player.isFlying() : bool(args[1]);
                    controller.flight(player, enable);
                    message(player, enable ? "已开启本插件飞行。" : "已释放本插件授予的飞行；原有飞行能力保留。");
                }
                case "sync" -> {
                    ActionController.permission(player, "mact.sync");
                    if (args.length < 2) {
                        for (SyncFeature f : SyncFeature.values()) message(player, f.key() + "=" + controller.syncEnabled(player, f));
                    } else {
                        SyncFeature f = SyncFeature.parse(args[1]);
                        if (args.length < 3) throw new IllegalArgumentException("用法：/meplayeractions sync <类型> <on|off|default>");
                        Boolean enabled = args[2].equalsIgnoreCase("default") ? null : bool(args[2]);
                        controller.synchronization(player, f, enabled);
                        message(player, f.key() + " 动画同步=" + controller.syncEnabled(player, f) + "（不改变真实姿态）");
                    }
                }
                case "status" -> {
                    plugin.status(player, args);
                }
                default -> throw new IllegalArgumentException("未知子命令；使用 " + CommandLayout.PREFIX + " help");
            }
        } catch (NumberFormatException ex) {
            if (clients != null) clients.disguiseFailure(player, resultRequest, "invalid_options", ex.getMessage());
            message(player, "速度请输入有效数字。");
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            if (clients != null) {
                if (ex instanceof com.simmc.meplayeractions.protection.ResourceRejectedException rejected)
                    clients.disguiseFailure(player, resultRequest, rejected.error(), ex.getMessage());
                else clients.disguiseFailure(player, resultRequest, disguiseFailureCode(ex), ex.getMessage());
            }
            message(player, Objects.toString(ex.getMessage(), "动作失败"));
        }
        catch (RuntimeException ex) {
            if (clients != null) clients.disguiseFailure(player, resultRequest, "action_failed", "伪装执行失败，请查看服务器日志");
            plugin.getLogger().warning("指令执行失败 " + player.getName() + ": " + ex);
            message(player, "动作执行失败，请查看服务器日志和 /meplayeractions status。");
        }
    }
    static String disguiseFailureCode(RuntimeException failure) {
        if (failure instanceof com.simmc.meplayeractions.protection.ResourceRejectedException rejected) return rejected.error().code();
        if (Objects.toString(failure.getMessage(), "").startsWith("缺少权限：")) return "permission_denied";
        return failure instanceof IllegalArgumentException ? "invalid_options" : "action_unavailable";
    }
    private void help(Player player) {
        String prefix = CommandLayout.PREFIX;
        for (String line : List.of("§e服务器伪装：" + prefix + " disguise <ME 模型名> [参数...]；undisguise 解除。",
                "§7models 列出 ME 已注册模型；attach [模型名] 管理现有 ME 动作（仅一个模型时可省略）。",
                "§7MPA models/ 用于客户端下载；没有这些资源仍可使用 ME 伪装。",
                "§7模型名：1–64 位小写英文、数字、_、-；沿用 MPA 的安全格式。",
                "伪装参数：scale、hide-self、delay、effect=slowness:等级[:秒数]；药水仅支持缓慢。",
                "观众参数：show-self（默认 " + settings.showSelf + "）、view-distance（默认 " + settings.modelViewDistance
                        + " 格）、max-viewers（默认 " + settings.maxViewers + " 名其他玩家）。",
                "§e动画：" + prefix + " menu 中文菜单；animations 动画列表；play <动作名> [速度] [ONCE|LOOP|HOLD]。",
                "§e姿态：" + prefix + " pose sit 坐下；pose crawl 爬行；pose fly [on|off] 飞行。",
                "§e停止／重置：" + prefix + " stop 停止手动动作；reset 重置动作、姿态和受管药水。",
                "§e同步：" + prefix + " sync <类型> <on|off|default>，省略全部参数查看设置；类型："
                        + String.join("、", Arrays.stream(SyncFeature.values()).map(SyncFeature::key).toList()) + "。",
                "§e管理：" + prefix + " status 查看全局保护与资源预算；status player <玩家> 查看个人诊断；reload 重载配置。")) message(player, line);
    }
    private static boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("参数使用 on 或 off");
        };
    }
    private static String commandPermission(String root) {
        return switch (root.toLowerCase(Locale.ROOT)) {
            case "disguise", "undisguise", "attach" -> "mact.disguise";
            case "play" -> "mact.play";
            case "sync" -> "mact.sync";
            case "status" -> "mact.debug";
            case "reload" -> "mact.admin";
            default -> "mact.use";
        };
    }

    @Override public List<String> tabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p) || !p.hasPermission("mact.use")) return List.of();
        if (args.length == 0 || (args.length > 1 && !p.hasPermission(commandPermission(args[0])))) return List.of();
        if (args.length >= 2 && args[0].equalsIgnoreCase("disguise")) {
            if (!p.hasPermission("mact.disguise")) return List.of();
            return DisguiseOptions.suggestions(args, controller.models(),
                    p.hasPermission("mact.disguise.effects") ? PaperEffectPort.names() : List.of());
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String root : CommandLayout.ROOTS) {
                if (p.hasPermission(commandPermission(root))) options.add(root);
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(s -> s.startsWith(prefix)).toList();
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "disguise" -> options.addAll(controller.models());
                case "attach" -> options.addAll(controller.existingModels(p));
                case "play" -> {
                    if (!p.hasPermission("mact.play")) break;
                    for (var action : settings.customActions.values())
                        if (action.permission().isEmpty() || p.hasPermission(action.permission())) options.add(action.id());
                    if (controller.controlled(p) && settings.rawPlay) {
                        try { options.addAll(controller.animations(p)); } catch (IllegalStateException ignored) {}
                    }
                }
                case "sync" -> { for (SyncFeature f : SyncFeature.values()) options.add(f.key()); }
                case "pose" -> {
                    if (p.hasPermission("mact.sit")) options.add("sit");
                    if (p.hasPermission("mact.crawl")) options.add("crawl");
                    if (settings.allowFlight && p.hasPermission("mact.flight")) options.add("fly");
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sync")) options.addAll(List.of("on", "off", "default"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("pose") && args[1].equalsIgnoreCase("fly")
                && settings.allowFlight && p.hasPermission("mact.flight")) options.addAll(List.of("on", "off"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("play")) options.addAll(List.of("0.5", "1.0", "1.5", "2.0"));
        else if (args.length == 4 && args[0].equalsIgnoreCase("play")) options.addAll(List.of("ONCE", "LOOP", "HOLD"));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().distinct().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
    @Override public void forget(Player player) {
        try { controller.remove(player, "quit"); }
        finally { clients.forget(player); }
    }
    @EventHandler public void death(PlayerDeathEvent e) { controller.remove(e.getEntity(), "death"); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void potion(EntityPotionEffectEvent e) {
        if (e.getEntity() instanceof Player player)
            controller.potionChanged(player, e.getModifiedType().getKey().getKey(),
                    e.getCause() == EntityPotionEffectEvent.Cause.EXPIRATION);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swing(PlayerArmSwingEvent e) { controller.swung(e.getPlayer(), e.getHand()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void jump(PlayerJumpEvent e) { controller.jumped(e.getPlayer()); }
    @EventHandler
    public void modelTransform(BoneTransformReadEvent e) { controller.visualTransform(e); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void blockDamage(BlockDamageEvent e) {
        if (e.isCancelled() || e.getInstaBreak()) controller.miningStopped(e.getPlayer(), e.getBlock());
        else controller.mining(e.getPlayer(), e.getBlock());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void blockAbort(BlockDamageAbortEvent e) { controller.miningStopped(e.getPlayer(), e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void blockBreak(BlockBreakEvent e) { controller.miningStopped(e.getPlayer(), e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void itemHeld(PlayerItemHeldEvent e) {
        if (controller.controlled(e.getPlayer())) controller.clearInteractions(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swapHands(PlayerSwapHandItemsEvent e) { controller.clearInteractions(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) controller.damaged(p); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (running && player.isOnline()) {
                clients.observationChanged(player);
                controller.reset(player, false);
            }
        });
    }
    @EventHandler public void worldChanged(PlayerChangedWorldEvent e) {
        if (running) clients.observationChanged(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent e) {
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (running && player.isOnline()) controller.reset(player, false); });
    }
}
