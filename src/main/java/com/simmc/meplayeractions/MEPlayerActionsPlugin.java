package com.simmc.meplayeractions;

import com.simmc.meplayeractions.command.CommandLayout;
import com.simmc.meplayeractions.server.PrivateOnlyBackend;
import com.simmc.meplayeractions.server.ServerBackend;
import com.simmc.meplayeractions.protection.ResourceProtection;
import com.simmc.meplayeractions.protection.ResourceSettings;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Pure Bukkit entry point: optional ModelEngine signatures never participate in listener scanning. */
public final class MEPlayerActionsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private ServerBackend backend;
    private ResourceProtection resources;

    public ResourceProtection resources() {
        return Objects.requireNonNull(resources, "资源保护运行时未启动");
    }

    @Override public void onEnable() {
        if (!Bukkit.getBukkitVersion().startsWith("1.21.11-")) {
            getLogger().severe("MEPlayerActions " + getDescription().getVersion() + " 仅支持 Paper 1.21.11");
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        saveDefaultConfig();
        try {
            resources = new ResourceProtection(this, ResourceSettings.fromConfiguration(getConfig()));
            resources.start();
            startBackend(prepareBackend());
        }
        catch (RuntimeException | LinkageError failure) {
            shutdown();
            getLogger().severe("启动失败：" + failure);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        PluginCommand command = Objects.requireNonNull(getCommand(CommandLayout.NAME));
        command.setExecutor(this); command.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MEPlayerActions " + getDescription().getVersion() + " 已启用；" + backend.diagnosis());
    }
    private ServerBackend prepareBackend() {
        var engine = Bukkit.getPluginManager().getPlugin("ModelEngine");
        String reason = unavailableReason(engine != null, engine != null && engine.isEnabled(),
                engine == null ? null : engine.getDescription().getVersion());
        if (reason == null) {
            try {
                // Keep this class out of the bootstrap constant pool's class/method signatures.
                Class<?> adapter = Class.forName("com.simmc.meplayeractions.me.ModelEngineBackend", true, getClass().getClassLoader());
                return (ServerBackend) adapter.getConstructor(MEPlayerActionsPlugin.class, FileConfiguration.class).newInstance(this, getConfig());
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (!(cause instanceof LinkageError)) throw new IllegalStateException("ModelEngine 后端初始化失败", cause);
                reason = "ModelEngine API 不兼容";
                getLogger().warning(reason + "：" + cause);
            } catch (ReflectiveOperationException | LinkageError failure) {
                reason = "ModelEngine API 不兼容";
                getLogger().warning(reason + "：" + failure);
            }
        }
        return new PrivateOnlyBackend(this, getConfig(), reason);
    }
    /** Dependency checks are separate from class loading, including the disabled-plugin case. */
    static String unavailableReason(boolean installed, boolean enabled, String version) {
        if (!installed) return "未安装 ModelEngine";
        if (!enabled) return "ModelEngine 未启用";
        if (!"R4.1.1".equals(version)) return "ModelEngine 版本不兼容（需要 R4.1.1，当前 " + version + "）";
        return null;
    }
    private void startBackend(ServerBackend next) {
        backend = next;
        try { next.start(); }
        catch (RuntimeException | LinkageError failure) {
            if (!next.modelEngine()) throw failure;
            try { next.close(); } catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            getLogger().warning("ModelEngine 动作后端启动失败，启用私人同步模式：" + failure);
            backend = new PrivateOnlyBackend(this, getConfig(), "ModelEngine 后端启动失败");
            backend.start();
        }
    }
    @Override public void onDisable() { HandlerList.unregisterAll((Listener)this); shutdown(); }
    private void shutdown() {
        ServerBackend old = backend; backend = null;
        if (old != null) {
            try { old.close(); }
            catch (RuntimeException | LinkageError failure) { getLogger().warning("后端清理失败：" + failure); }
        }
        ResourceProtection protection = resources; resources = null;
        if (protection != null) protection.close();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String[] normalized;
        try { normalized = CommandLayout.normalize(args); }
        catch (IllegalArgumentException failure) { message(sender, failure.getMessage()); return true; }
        if (normalized[0].equals("reload")) {
            if (!sender.hasPermission("mact.admin")) { message(sender, "缺少权限：mact.admin"); return true; }
            try {
                replaceAfterValidation(() -> {
                    reloadConfig();
                    return new BackendReload(ResourceSettings.fromConfiguration(getConfig()), prepareBackend());
                }, prepared -> {
                    shutdown();
                    resources = new ResourceProtection(this, prepared.settings()); resources.start();
                    startBackend(prepared.backend());
                }, this::shutdown);
                message(sender, "配置已重载，旧伪装和私人共享会话已清理；" + backend.diagnosis() + "。客户端将重新协商共享。");
            } catch (RuntimeException | LinkageError failure) { message(sender, "重载失败：" + failure.getMessage()); }
            return true;
        }
        if (normalized[0].equals("status")) {
            try { status(sender, normalized); }
            catch (IllegalArgumentException | IllegalStateException failure) { message(sender, failure.getMessage()); }
            return true;
        }
        if (sender instanceof Player player) { handleAction(player, args); return true; }
        else {
            message(sender, "玩家使用 " + CommandLayout.PREFIX + "；控制台可用 status 查看全局资源与保护状态，reload 重载配置。");
        }
        return true;
    }
    private record BackendReload(ResourceSettings settings, ServerBackend backend) { }
    /** Validation keeps the old mode alive; a failed replacement must close partial new services. */
    static <T> void replaceAfterValidation(Supplier<T> validate, Consumer<T> replace, Runnable cleanupFailed) {
        T prepared = validate.get();
        try { replace.accept(prepared); }
        catch (RuntimeException | LinkageError failure) {
            try { cleanupFailed.run(); }
            catch (RuntimeException | LinkageError cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public void status(CommandSender sender, String[] args) {
        permission(sender, "mact.debug");
        String[] normalized = CommandLayout.normalize(args);
        if (backend == null || resources == null) throw new IllegalStateException("插件后端未启动");
        if (normalized.length == 3) {
            Player target = Bukkit.getPlayerExact(normalized[2]);
            if (target == null) throw new IllegalArgumentException("玩家不在线：" + normalized[2]);
            backend.playerStatus(sender, target); return;
        }
        String section = normalized.length == 1 ? "global" : normalized[1];
        if (section.equals("global")) {
            message(sender, "MEPlayerActions " + getDescription().getVersion() + " 全局诊断");
            backend.status(sender);
        }
        resources.statusLines(section).forEach(line -> message(sender, line));
    }
    /** Kept as the stable callback for ActionMenu and the authorized client-action bridge. */
    public void handleAction(Player player, String[] args) {
        try {
            permission(player, "mact.use");
            if (backend == null) throw new IllegalStateException("插件后端未启动");
            if (args.length > 0 && args[0].equalsIgnoreCase("status")) { status(player, args); return; }
            backend.handleAction(player, args);
        } catch (IllegalArgumentException | IllegalStateException failure) { message(player, Objects.toString(failure.getMessage(), "动作失败")); }
        catch (RuntimeException failure) {
            getLogger().warning("指令执行失败 " + player.getName() + ": " + failure);
            message(player, "动作执行失败，请查看服务器日志和 " + CommandLayout.PREFIX + " status。");
        }
    }
    public static void permission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) throw new IllegalStateException("缺少权限：" + permission);
    }
    public static String commandPermission(String root) {
        return switch (root.toLowerCase(Locale.ROOT)) {
            case "disguise", "undisguise", "attach" -> "mact.disguise";
            case "play" -> "mact.play";
            case "sit" -> "mact.sit";
            case "crawl" -> "mact.crawl";
            case "fly" -> "mact.flight";
            case "sync" -> "mact.sync";
            case "status" -> "mact.debug";
            case "reload" -> "mact.admin";
            default -> "mact.use";
        };
    }
    public static void message(CommandSender sender, String text) { sender.sendMessage("§b[动作] §f" + text); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("mact.debug")) return List.of();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) return java.util.stream.Stream.concat(CommandLayout.STATUS_SECTIONS.stream(), java.util.stream.Stream.of("player"))
                    .filter(value -> value.startsWith(prefix)).toList();
            if (args.length == 3 && args[1].equalsIgnoreCase("player")) return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
            return List.of();
        }
        return backend == null ? List.of() : backend.tabComplete(sender, command, alias, args);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        try { if (backend != null) backend.forget(event.getPlayer()); }
        finally { if (resources != null) resources.cancelOwner(event.getPlayer().getUniqueId()); }
    }
}
