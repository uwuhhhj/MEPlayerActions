package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.Perspective;
import net.minecraft.text.Text;
import java.util.List;

/** Local previews and shared server actions have explicit, separate controls. */
public final class ActionsScreen extends Screen {
    private final ClientRuntime runtime;
    private final Screen parent;
    private boolean localMode, serverAvailable;
    private int page, rows;
    private List<ClientRuntime.Action> actions = List.of();
    private com.simmc.meplayeractions.client.model.YsmModelProfile menuProfile;
    private ModelActionMenu menu;
    private List<ModelActionMenu.Entry> localEntries = List.of();

    public ActionsScreen(ClientRuntime runtime) {
        this(runtime, runtime.localAppearance().enabled() || !runtime.serverOwnModelReady(), null);
    }
    public ActionsScreen(ClientRuntime runtime, boolean localMode, Screen parent) {
        super(Text.literal("玩家模型动作"));
        this.runtime = runtime;
        this.localMode = localMode;
        this.parent = parent;
    }
    @Override protected void init() {
        serverAvailable = runtime.serverBridgeReady() && runtime.serverOwnModelReady();
        actions = currentActions();
        rows = Math.max(1, Math.min(4, (height - 226) / 24));
        int pageSize = rows * 3;
        page = Math.max(0, Math.min(page, Math.max(0, (actions.size() - 1) / pageSize)));
        int panel = Math.min(450, width - 24), left = (width - panel) / 2, col = (panel - 12) / 3;
        var wheel = ButtonWidget.builder(Text.literal("动作轮盘"), b -> {
            if (client != null) client.setScreen(new AnimationWheelScreen(runtime, localMode, this));
        }).dimensions(width - 80, 10, 68, 20).build();
        wheel.setTooltip(Tooltip.of(Text.literal("扇形选择、数字 1–8 和分页；本地动作仅自己可见")));
        addDrawableChild(wheel);
        addDrawableChild(ButtonWidget.builder(Text.literal(localMode ? "本地动作 ✓" : "本地动作"), b -> switchMode(true))
                .dimensions(left, 61, (panel - 6) / 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(localMode ? "服务器动作" : "服务器动作 ✓"), b -> switchMode(false))
                .dimensions(left + (panel + 6) / 2, 61, (panel - 6) / 2, 20).build());
        for (int index = page * pageSize; index < Math.min(actions.size(), (page + 1) * pageSize); index++) {
            var action = actions.get(index);
            int slot = index - page * pageSize;
            String config = localMode && menu != null ? localEntries.get(index).configGroup() : "";
            int actionWidth = config.isEmpty() ? col : col - 22;
            var button = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(action.label(), Math.max(1, actionWidth - 8))), b -> play(action.id()))
                    .dimensions(left + (slot % 3) * (col + 6), 100 + (slot / 3) * 24, actionWidth, 20).build();
            if (!config.isEmpty()) {
                var gear = ButtonWidget.builder(Text.literal("⚙"), b -> client.setScreen(new ModelConfigScreen(runtime, runtime.localAppearance().modelId(), config, this)))
                        .dimensions(left + (slot % 3) * (col + 6) + actionWidth + 2, 100 + (slot / 3) * 24, 20, 20).build();
                gear.setTooltip(Tooltip.of(Text.literal("作者模型配置；仅自己可见"))); addDrawableChild(gear);
            }
            button.setTooltip(Tooltip.of(Text.literal(action.id() + (localMode ? " · 仅自己可见" : " · 服务器动作"))));
            addDrawableChild(button);
        }
        int bottom = 100 + rows * 24 + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("上一页"), b -> { page--; clearAndInit(); })
                .dimensions(left, bottom, col, 20).build()).active = page > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("停止动画"), b -> stop())
                .dimensions(left + col + 6, bottom, col, 20).build()).active = localMode || serverAvailable;
        addDrawableChild(ButtonWidget.builder(Text.literal("下一页"), b -> { page++; clearAndInit(); })
                .dimensions(left + 2 * (col + 6), bottom, col, 20).build()).active = (page + 1) * pageSize < actions.size();
        bottom += 24;
        if (localMode) {
            addDrawableChild(ButtonWidget.builder(Text.literal("本地外观设置"), b -> openAppearance())
                    .dimensions(left, bottom, col, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("模型配置 / 皮肤"), b -> {
                client.setScreen(new ModelConfigScreen(runtime, runtime.localAppearance().modelId(), this));
            }).dimensions(left + col + 6, bottom, col, 20).build()).active = menuProfile != null;
            addDrawableChild(ButtonWidget.builder(Text.literal("回游戏查看"), b -> viewInWorld())
                    .dimensions(left + 2 * (col + 6), bottom, col, 20).build()).active = client != null && client.world != null;
        } else {
            serverButton("真实坐下", "sit", left, bottom, col);
            serverButton("真实爬行", "crawl", left + col + 6, bottom, col);
            serverButton("重置姿态", "reset", left + 2 * (col + 6), bottom, col);
        }
        bottom += 24;
        addDrawableChild(ButtonWidget.builder(enabledText(), b -> {
            runtime.toggleEnabled(); b.setMessage(enabledText());
        }).dimensions(left, bottom, col, 20).build());
        var self = ButtonWidget.builder(selfText(), b -> {
            runtime.options.showSelf = !runtime.options.showSelf;
            runtime.options.save(); b.setMessage(selfText());
        }).dimensions(left + col + 6, bottom, col, 20).build();
        self.setTooltip(Tooltip.of(Text.literal("控制本客户端第三人称中本人模型的显示。本地外观启用在模型设置中操作，其他玩家不受此开关影响。")));
        addDrawableChild(self);
        addDrawableChild(ButtonWidget.builder(Text.literal(localMode ? menu != null && menu.depth() > 0 ? "返回上级" : "关闭菜单" : "本地外观设置"), b -> {
            if (localMode) close(); else openAppearance();
        }).dimensions(left + 2 * (col + 6), bottom, col, 20).build());
    }
    private List<ClientRuntime.Action> currentActions() {
        if (!localMode) return runtime.serverBridgeReady() && runtime.serverOwnModelReady() ? runtime.actions() : List.of();
        var profile = runtime.localModelProfile();
        if (profile != menuProfile) {
            menuProfile = profile; menu = null; page = 0;
            try { if (profile != null && profile.isYsm()) menu = new ModelActionMenu(profile, client == null ? "zh_cn" : client.getLanguageManager().getLanguage()); }
            catch (RuntimeException invalid) { }
        }
        if (menu == null) { localEntries = List.of(); return runtime.localActions(); }
        localEntries = menu.entries(); return localEntries.stream().map(entry -> new ClientRuntime.Action(entry.id(), entry.label())).toList();
    }
    private void switchMode(boolean local) { localMode = local; menu = null; menuProfile = null; page = 0; clearAndInit(); }
    private void play(String id) {
        if (localMode) {
            var entry = localEntries.stream().filter(value -> value.id().equals(id)).findFirst();
            if (entry.isPresent() && (entry.get().category() || entry.get().back())) {
                if (menu.enter(entry.get())) { page = 0; clearAndInit(); }
            } else runtime.playLocal(id);
        }
        else if (runtime.serverBridgeReady() && runtime.serverOwnModelReady()) runtime.request("play", id);
    }
    private void stop() {
        if (localMode) runtime.stopLocal();
        else if (runtime.serverBridgeReady() && runtime.serverOwnModelReady()) runtime.request("stop", "");
    }
    private void serverButton(String label, String action, int x, int y, int buttonWidth) {
        var button = ButtonWidget.builder(Text.literal(label), b -> {
            if (runtime.serverBridgeReady() && runtime.serverOwnModelReady()) runtime.request(action, "");
        }).dimensions(x, y, buttonWidth, 20).build();
        button.active = serverAvailable;
        button.setTooltip(Tooltip.of(Text.literal("需要服务器支持与服务器伪装；本地外观不会改变真实姿态")));
        addDrawableChild(button);
    }
    private void openAppearance() { if (client != null) client.setScreen(new LocalAppearanceScreen(runtime, this)); }
    private void viewInWorld() {
        if (client != null && client.world != null) {
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            client.setScreen(null);
        }
    }
    private Text enabledText() { return Text.literal("客户端渲染：" + (runtime.options.enabled ? "开启" : "关闭")); }
    private Text selfText() { return Text.literal("第三人称本人：" + (runtime.options.showSelf ? "显示" : "隐藏")); }
    @Override public void tick() {
        if (!currentActions().equals(actions) || serverAvailable != (runtime.serverBridgeReady() && runtime.serverOwnModelReady())) clearAndInit();
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101823);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(localMode ? "本地动作仅自己可见，不改变真实姿态" : "服务器动作由服务器同步给其他观看者", Math.max(1, width - 24))), width / 2, 31, 0xffc6d5ec);
        String status = localMode ? runtime.localAppearanceStatus()
                : serverAvailable ? "已连接服务器模型" : "需要服务器支持，并先使用服务器伪装";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, width - 24)), width / 2, 46, 0xffa4bbd6);
        if (actions.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,
                Text.literal(localMode ? "打开本地外观设置，保存并启用一个模型" : "当前没有可用的服务器动作"), width / 2, 86, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }
    public java.util.Map<String,Object> diagnostics() {
        return java.util.Map.of("visibleActionIds",actions.stream().skip((long)page*rows*3).limit(rows*3).map(ClientRuntime.Action::id).toList(),
                "localMode",localMode,"page",page);
    }
    @Override public void close() {
        if (client != null) {
            if (localMode && menu != null && menu.back()) { page = 0; clearAndInit(); }
            else client.setScreen(parent);
        }
    }
    @Override public boolean shouldPause() { return false; }
}
