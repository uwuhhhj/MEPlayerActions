package com.simmc.meplayeractions.ui;

import com.simmc.meplayeractions.action.ActionController;
import com.simmc.meplayeractions.action.SyncFeature;
import com.simmc.meplayeractions.client.ClientSyncService.StateSnapshot;
import com.simmc.meplayeractions.config.Settings;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.simmc.meplayeractions.MEPlayerActionsPlugin.message;
import static com.simmc.meplayeractions.MEPlayerActionsPlugin.permission;

public final class ActionDialog implements Listener {
    private static final Key ACTION = Key.key("meplayeractions", "action_menu");
    private static final int PAGE_SIZE = 12;
    private static final long LIFETIME_NANOS = Duration.ofMinutes(5).toNanos();
    private final Supplier<ActionController> controllers;
    private final Supplier<Settings> settings;
    private final BiConsumer<Player, String[]> perform;
    private final Map<UUID, Session> sessions = new HashMap<>();

    static final class Session {
        final UUID id = UUID.randomUUID();
        final UUID owner;
        final UUID instance;
        final long createdAt;
        final List<String[]> actions = new ArrayList<>();
        private boolean consumed;

        Session(UUID owner, UUID instance, long createdAt) {
            this.owner = owner;
            this.instance = instance;
            this.createdAt = createdAt;
        }

        String[] claim(UUID viewer, String token, String button, UUID currentInstance, long now) {
            // A response belongs to one player, one dialog and one model instance, and can run only once.
            if (this.consumed || !this.owner.equals(viewer) || !this.id.toString().equals(token)
                    || !this.instance.equals(currentInstance) || now - this.createdAt >= LIFETIME_NANOS
                    || button == null) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(button);
            } catch (NumberFormatException failure) {
                return null;
            }
            if (index < 0 || index >= this.actions.size()) {
                return null;
            }
            this.consumed = true;
            return this.actions.get(index);
        }
    }

    public ActionDialog(Supplier<ActionController> controllers, Supplier<Settings> settings,
            BiConsumer<Player, String[]> perform) {
        this.controllers = controllers;
        this.settings = settings;
        this.perform = perform;
    }

    public void open(Player player, int requestedPage) {
        permission(player, "mact.use");
        ActionController controller = this.controllers.get();
        Settings config = this.settings.get();
        List<String> clips = controller.animations(player);
        Map<String, String> entries = new LinkedHashMap<>();
        for (Settings.CustomAction action : config.customActions.values()) {
            if (clips.contains(action.animation())
                    && (action.permission().isEmpty() || player.hasPermission(action.permission()))) {
                entries.put(action.id(), action.label());
            }
        }
        if (config.rawPlay && player.hasPermission("mact.play")) {
            for (String clip : clips) {
                entries.putIfAbsent(clip, config.animationLabel(clip));
            }
        }
        List<Map.Entry<String, String>> display = List.copyOf(entries.entrySet());
        int pages = Math.max(1, (display.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        StateSnapshot snapshot = controller.snapshot(player.getUniqueId());
        if (snapshot == null) {
            throw new IllegalStateException("当前没有动作会话");
        }
        Session session = new Session(player.getUniqueId(), snapshot.instance(), System.nanoTime());
        List<ActionButton> buttons = new ArrayList<>();
        for (int index = page * PAGE_SIZE; index < Math.min(display.size(), (page + 1) * PAGE_SIZE); index++) {
            Map.Entry<String, String> entry = display.get(index);
            buttons.add(button(session, entry.getValue(), "动作名：" + entry.getKey() + "\n播放动画；姿态动画不会改变真实姿态",
                    "play", entry.getKey()));
        }
        if (player.hasPermission("mact.sit")) {
            buttons.add(button(session, "真实坐下", "需要 GSit；潜行可起身", "pose", "sit"));
        }
        if (player.hasPermission("mact.crawl")) {
            buttons.add(button(session, "真实爬行", "需要 GSit 与爬行动画", "pose", "crawl"));
        }
        if (config.allowFlight && player.hasPermission("mact.flight")) {
            buttons.add(button(session, "真实飞行开关", "切换本插件授予的飞行", "pose", "fly"));
        }
        buttons.add(button(session, "停止手动动作", "结束当前手动播放的动画", "stop"));
        buttons.add(button(session, "重置动作与姿态", "重置动作、姿态和受管药水", "reset"));
        if (player.hasPermission("mact.sync")) {
            boolean enabled = controller.syncEnabled(player, SyncFeature.SIT);
            buttons.add(button(session, "坐姿动画同步：" + (enabled ? "开启" : "关闭"), "切换动画同步；不会改变真实坐下状态",
                    "sync", "sit", enabled ? "off" : "on"));
        }
        if (page > 0) {
            buttons.add(button(session, "上一页", "查看上一页动画", "page", Integer.toString(page - 1)));
        }
        if (page + 1 < pages) {
            buttons.add(button(session, "下一页", "查看下一页动画", "page", Integer.toString(page + 1)));
        }
        ActionButton exit = button(session, "关闭", "返回游戏", "close");
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("玩家动作 · " + (page + 1) + "/" + pages))
                        .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "这是不常用的备用菜单，仅在未安装 MPA 客户端、无法使用 J 轮盘调用动画时偶尔使用。",
                                NamedTextColor.GRAY), 360))).build())
                .type(DialogType.multiAction(buttons, exit, 2)));
        this.sessions.put(player.getUniqueId(), session);
        player.showDialog(dialog);
    }

    private static ActionButton button(Session session, String title, String tooltip, String... action) {
        int index = session.actions.size();
        session.actions.add(action);
        BinaryTagHolder payload = BinaryTagHolder.binaryTagHolder(
                "{session:\"" + session.id + "\",button:\"" + index + "\"}");
        return ActionButton.create(LegacyComponentSerializer.legacySection().deserialize(title),
                Component.text(tooltip), 170, DialogAction.customClick(ACTION, payload));
    }

    @EventHandler
    public void click(PlayerCustomClickEvent event) {
        if (!ACTION.equals(event.getIdentifier())
                || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        DialogResponseView response = event.getDialogResponseView();
        if (response == null) {
            return;
        }
        Player player = connection.getPlayer();
        String token = response.getText("session");
        String button = response.getText("button");
        if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) {
            return;
        }
        Session session = this.sessions.get(player.getUniqueId());
        if (session == null || !session.id.toString().equals(token)) {
            return;
        }
        long now = System.nanoTime();
        StateSnapshot snapshot = this.controllers.get().snapshot(player.getUniqueId());
        if (now - session.createdAt >= LIFETIME_NANOS || snapshot == null
                || !session.instance.equals(snapshot.instance())) {
            this.sessions.remove(player.getUniqueId());
            message(player, "菜单已过期或模型已变化，请重新使用 /meplayeractions menu。");
            return;
        }
        String[] action = session.claim(player.getUniqueId(), token, button,
                snapshot.instance(), now);
        if (action == null) {
            return;
        }
        this.sessions.remove(player.getUniqueId());
        try {
            if (action[0].equals("page")) {
                open(player, Integer.parseInt(action[1]));
            } else if (!action[0].equals("close")) {
                this.perform.accept(player, action);
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            message(player, failure.getMessage());
        }
    }

    public void forget(Player player) {
        this.sessions.remove(player.getUniqueId());
    }

    public void closeAll() {
        List<UUID> owners = List.copyOf(this.sessions.keySet());
        this.sessions.clear();
        for (UUID owner : owners) {
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.closeDialog();
            }
        }
    }
}
