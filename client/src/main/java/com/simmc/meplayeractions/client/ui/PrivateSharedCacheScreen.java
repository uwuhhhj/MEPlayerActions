package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.network.PrivateModelSyncClient;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Passive private-model inspection. Every action here is local; this screen never creates a gameplay binding. */
public final class PrivateSharedCacheScreen extends Screen
{
    private static final int MAX_CURRENT_ENTRIES = 16;

    private record Entry(String key, String hash, String label, String texture, int bytes, boolean cached,
            PrivateModelSyncClient.ReceivedModel receiving)
    {
    }

    private static final class Preview
    {
        LocalModelLibrary.Loaded loaded;
        String error = "";
        boolean pending;
    }

    private final ClientRuntime runtime;
    private final Screen parent;
    private final ModelPreview preview;
    private PrivateSharedCacheLibrary library;
    private GalleryPanelLayout.Bounds layout;
    private final Map<String, Preview> previews = new HashMap<>();
    private List<PrivateSharedCacheLibrary.Entry> cached = List.of();
    private List<PrivateModelSyncClient.ReceivedModel> received = List.of();
    private List<Entry> entries = List.of();
    private List<Entry> visible = List.of();
    private String selected = "", message = "";
    private int page, inFlight, ticks, pendingScanTick = -1;
    private long epoch;
    private boolean open, scanning, dragging;
    private float yaw, pitch = -8;

    public PrivateSharedCacheScreen(ClientRuntime runtime, Screen parent)
    {
        super(Text.literal("私人分享缓存 · 只读预览"));
        this.runtime = runtime;
        this.parent = parent;
        this.preview = new ModelPreview(runtime);
    }

    @Override
    protected void init()
    {
        this.open = true;
        this.layout = GalleryPanelLayout.create(super.width, super.height, 38, 29);
        if (this.library == null)
        {
            this.library = new PrivateSharedCacheLibrary(this.runtime.localPrivateModelCacheDirectory());
            this.received = this.runtime.receivedPrivateModels();
            scan();
        }
        buildEntries();
        buildControls();
        pump();
    }

    private ButtonWidget button(String label, int x, int y, int w, Runnable action, String tooltip)
    {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), ignored -> action.run()).dimensions(x, y, w, 20)
                .build();
        button.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, tooltip));
        return addDrawableChild(button);
    }

    private void buildControls()
    {
        clearChildren();
        int x = this.layout.right() + 5, y = this.layout.top() + 5, w = this.layout.rightWidth() - 10;
        int segment = (w - 6) / 4;
        button("本人已上传", x, y, segment, this::openUploads, "本人服务器存档\n到客户端云朵查看、分享或删除\n这里的观看缓存不属于本人上传目录");
        button("刷新", x + segment + 2, y, segment, () -> scan(), "重新检查本机私人缓存\n只读取已有文件，不申请下载");
        button("缓存目录", x + 2 * (segment + 2), y, segment, this::openDirectory,
                "打开本机私人分享缓存\nconfig/meplayeractions/cache/private-models\n不是服务器伪装模型目录");
        button("教程", x + 3 * (segment + 2), y, w - 3 * (segment + 2),
                () -> super.client.setScreen(new ModelHelpScreen(this.runtime, this, true)),
                "私人分享教程\n接收、缓存、显示授权与本人上传管理");
        for (int i = 0; i < this.visible.size(); i++)
        {
            int cardX = this.layout.right() + 5
                    + i % this.layout.columns() * (GalleryPanelLayout.CARD_WIDTH + GalleryPanelLayout.CARD_GAP);
            int cardY = this.layout.top() + 29
                    + i / this.layout.columns() * (GalleryPanelLayout.CARD_HEIGHT + GalleryPanelLayout.CARD_GAP);
            addDrawableChild(new Card(this.visible.get(i), cardX, cardY));
        }
        int navigationWidth = Math.max(35, Math.min(62, (this.layout.rightWidth() - 58) / 2));
        button("上一页", this.layout.right() + 5, this.layout.bottom() - 21, navigationWidth,
                () -> changePage(this.page - 1), "上一页私人缓存").active = this.page > 0;
        button("下一页", this.layout.right() + this.layout.rightWidth() - navigationWidth - 5, this.layout.bottom() - 21,
                navigationWidth, () -> changePage(this.page + 1), "下一页私人缓存").active = this.page + 1 < pageCount();
        button("返回", this.layout.left(), this.layout.footerY(), 48, this::close, "返回服务器模型图库");
        pump();
    }

    private void openUploads()
    {
        PlayerModelScreen gallery = new PlayerModelScreen(this.runtime, this);
        gallery.showUploadedPrivateModels();
        super.client.setScreen(gallery);
    }

    private void openDirectory()
    {
        try
        {
            Files.createDirectories(this.runtime.localPrivateModelCacheDirectory());
            Util.getOperatingSystem().open(this.runtime.localPrivateModelCacheDirectory());
            this.message = "已打开本机私人分享缓存";
        }
        catch (IOException | SecurityException failure)
        {
            this.message = "无法打开缓存目录，请检查本机文件权限";
        }
    }

    private void scan()
    {
        if (this.scanning || this.library == null)
        {
            return;
        }

        this.scanning = true;
        this.message = "正在检查本机私人缓存";
        long requestEpoch = this.epoch;
        this.library.scan().whenComplete((found, error) -> super.client.execute(() ->
        {
            if (!this.open || requestEpoch != this.epoch)
            {
                return;
            }

            this.scanning = false;
            if (error == null)
            {
                this.cached = found;
                this.message = "只读预览 · 不下载、不应用伪装";
            }
            else
            {
                this.message = "无法读取本机私人缓存";
            }

            // A manual refresh can recover a restored file without requiring the screen to be reopened.
            this.previews.entrySet().removeIf(entry -> !entry.getValue().error.isEmpty());
            this.received = this.runtime.receivedPrivateModels();
            buildEntries();
            buildControls();
        }));
    }

    private void buildEntries()
    {
        LinkedHashMap<String, PrivateSharedCacheLibrary.Entry> byHash = new LinkedHashMap<String, PrivateSharedCacheLibrary.Entry>();
        this.cached.forEach(entry -> byHash.put(entry.hash(), entry));
        ArrayList<PrivateSharedCacheScreen.Entry> result = new ArrayList<Entry>();
        Set<String> liveHashes = new HashSet<>();
        List<PrivateModelSyncClient.ReceivedModel> current = this.received.stream()
                .sorted(Comparator.comparingInt((PrivateModelSyncClient.ReceivedModel model) -> model
                        .phase() == PrivateModelSyncClient.ReceivingPhase.FAILED ? 1 : 0))
                .limit(MAX_CURRENT_ENTRIES).toList();
        for (PrivateModelSyncClient.ReceivedModel remote : current)
        {
            PrivateSharedCacheLibrary.Entry file = byHash.get(remote.hash());
            liveHashes.add(remote.hash());
            String owner = ownerName(remote.owner());
            String source = remote.modelId().isEmpty() ? shortHash(remote.hash()) : remote.modelId();
            result.add(new Entry(remote.owner() + ":" + remote.hash(), remote.hash(), owner + " · " + source,
                    remote.textureId(), remote.bytes(), file != null, remote));
        }
        for (PrivateSharedCacheLibrary.Entry file : this.cached)
        {
            if (!liveHashes.contains(file.hash()))

            {
                result.add(new Entry("history:" + file.hash(), file.hash(), "历史缓存 · " + shortHash(file.hash()), "",
                        file.bytes(), true, null));
            }

        }

        this.entries = List.copyOf(result);
        this.page = Math.min(this.page, pageCount() - 1);
        if (this.entries.stream().noneMatch(entry -> entry.key().equals(this.selected)))
        {
            this.selected = this.entries.isEmpty() ? "" : this.entries.getFirst().key();
        }

        int start = this.page * pageSize();
        this.visible = this.entries.subList(Math.min(start, this.entries.size()),
                Math.min(start + pageSize(), this.entries.size()));
        Set<String> retained = new HashSet<>();
        this.visible.forEach(entry -> retained.add(previewKey(entry)));
        this.entries.stream().filter(entry -> entry.key().equals(this.selected)).findFirst()
                .ifPresent(entry -> retained.add(previewKey(entry)));
        this.previews.entrySet().removeIf(entry ->
        {
            if (retained.contains(entry.getKey()))
            {
                return false;
            }

            this.preview.release(entry.getKey());
            return true;
        });
    }

    private String ownerName(UUID owner)
    {
        PlayerListEntry player = super.client == null || super.client.getNetworkHandler() == null ? null
                : super.client.getNetworkHandler().getPlayerListEntry(owner);
        return player == null ? owner.toString().substring(0, 8) : player.getProfile().name();
    }

    private int pageSize()
    {
        return this.layout.columns() * this.layout.rows();
    }

    private int pageCount()
    {
        return Math.max(1, (this.entries.size() + pageSize() - 1) / pageSize());
    }

    private void changePage(int next)
    {
        this.page = Math.max(0, Math.min(pageCount() - 1, next));
        buildEntries();
        buildControls();
    }

    private static String shortHash(String hash)
    {
        return hash.length() < 12 ? hash : hash.substring(0, 12);
    }

    private static String previewKey(Entry entry)
    {
        return "private-cache:" + entry.hash() + ":" + entry.texture();
    }

    private void select(Entry entry)
    {
        this.selected = entry.key();
        buildEntries();
        this.preview.restart(previewKey(entry), ModelPreview.Context.SELECTED);
        pump();
    }

    private void pump()
    {
        if (this.library == null || !this.open)
        {
            return;
        }

        ArrayList<PrivateSharedCacheScreen.Entry> wanted = new ArrayList<Entry>();
        this.entries.stream().filter(entry -> entry.key().equals(this.selected)).findFirst().ifPresent(wanted::add);
        wanted.addAll(this.visible);
        for (PrivateSharedCacheScreen.Entry entry : wanted)
        {
            if (this.inFlight >= 2)
            {
                break;
            }

            if (!entry.cached())
            {
                continue;
            }

            String key = previewKey(entry);
            if (this.previews.containsKey(key))
            {
                continue;
            }

            PrivateSharedCacheScreen.Preview pending = new Preview();
            pending.pending = true;
            this.previews.put(key, pending);
            this.inFlight++;
            long requestEpoch = this.epoch;
            this.library.preview(entry.hash(), entry.texture())
                    .whenComplete((loaded, error) -> super.client.execute(() ->
                    {
                        if (!this.open || requestEpoch != this.epoch)
                        {
                            return;
                        }

                        this.inFlight = Math.max(0, this.inFlight - 1);
                        if (this.previews.get(key) == pending)
                        {
                            pending.pending = false;
                            pending.loaded = loaded;
                            pending.error = error == null ? "" : "缓存无法解析或已被清理";
                        }
                        pump();
                    }));
        }
    }

    @Override
    public void tick()
    {
        this.ticks++;
        if (this.ticks % 20 == 0)
        {
            List<PrivateModelSyncClient.ReceivedModel> current = this.runtime.receivedPrivateModels();
            if (!current.equals(this.received))
            {
                this.received = current;
                buildEntries();
                buildControls();
                this.pendingScanTick = this.ticks + 20;
            }
        }
        // A new receipt may be saved asynchronously. Reinspect once after that change, never every tick.
        if (this.pendingScanTick >= 0 && this.ticks >= this.pendingScanTick)
        {
            this.pendingScanTick = -1;
            scan();
        }
    }

    private String status(Entry entry)
    {
        if (entry.receiving() == null)
        {
            return "历史缓存 · 仅预览";
        }

        PrivateModelSyncClient.ReceivedModel remote = entry.receiving();
        return switch (remote.phase())
        {
        case CHECKING_CACHE -> "正在核对缓存";
        case WAITING_DOWNLOAD -> "等待服务器分发";
        case DOWNLOADING -> "接收中 " + (remote.bytes() == 0 ? 0 : remote.receivedBytes() * 100L / remote.bytes()) + "%";
        case DECODING -> "正在准备客户端模型";
        case WAITING_ACK -> "已准备 · 等待服务器确认";
        case ACTIVE -> this.runtime.options.enabled ? "已获准显示" : "已获准 · 客户端渲染暂停";
        case FAILED -> "分享显示失败";
        };
    }

    private int statusColor(Entry entry)
    {
        if (entry.receiving() == null)
        {
            return 0xff92b9df;
        }

        return switch (entry.receiving().phase())
        {
        case ACTIVE -> 0xff8df2b5;
        case FAILED -> 0xffff8585;
        default -> 0xffffd589;
        };
    }

    private String tooltip(Entry entry)
    {
        String owner = entry.receiving() == null ? "历史资源未保存发布者身份" : "发布者：" + ownerName(entry.receiving().owner());
        String detail = entry.receiving() != null && !entry.receiving().failure().isEmpty()
                ? "\n失败原因：" + entry.receiving().failure()
                : "";
        return entry.label() + "\n" + owner + "\n" + status(entry) + detail + "\n本机文件：" + localCacheStatus(entry)
                + "\n点击只预览，不请求下载\n显示人物仍需当次服务器授权\n不能伪装或上传他人资源" + "\n完整资源：" + Math.max(1, (entry.bytes() + 1023) / 1024)
                + " KiB · " + shortHash(entry.hash());
    }

    private String localCacheStatus(Entry entry)
    {
        PrivateSharedCacheScreen.Preview prepared = this.previews.get(previewKey(entry));
        if (prepared != null && !prepared.error.isEmpty())
        {
            return "已有文件 · 预览校验失败";
        }

        if (prepared != null && prepared.loaded != null)
        {
            return "已校验并可预览";
        }

        return entry.cached() ? "已有文件 · 预览时校验" : "尚未保存";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        this.preview.beginFrame(context);
        context.fill(this.layout.left() - 4, this.layout.frameTop(), this.layout.left() + this.layout.panelWidth() + 4,
                this.layout.frameBottom(), 0xDD171F2A);
        context.fill(this.layout.left(), this.layout.top(), this.layout.left() + this.layout.previewWidth(),
                this.layout.bottom(), 0xE6222730);
        context.fill(this.layout.right(), this.layout.top(), this.layout.right() + this.layout.rightWidth(),
                this.layout.bottom(), 0xE6222730);
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, this.layout.titleY(),
                0xfff3f6ff);
        if (!this.layout.compact())
        {
            context.drawCenteredTextWithShadow(super.textRenderer, "私人分享与服务器伪装独立 · 本页仅查看已收到的资源", super.width / 2,
                    this.layout.titleY() + 13, 0xff92b9df);
        }

        PrivateSharedCacheScreen.Entry selectedEntry = this.entries.stream()
                .filter(entry -> entry.key().equals(this.selected)).findFirst().orElse(null);
        if (selectedEntry != null)
        {
            renderSelected(context, selectedEntry, delta);
        }

        else
        {
            wrapped(context, "尚未收到私人模型\n发布者需主动分享，并由服务器授予观看权限。\n点击刷新可检查本机历史缓存。", this.layout.left() + 6,
                    this.layout.top() + 8, this.layout.previewWidth() - 12, 0xffbccce0, 10);
            wrapped(context, this.scanning ? "正在读取缓存…" : "暂无私人观看缓存\n此页面不会主动申请资源", this.layout.right() + 8,
                    this.layout.top() + 42, this.layout.rightWidth() - 16, 0xffbccce0, 4);
        }
        context.drawCenteredTextWithShadow(super.textRenderer, (this.page + 1) + " / " + pageCount(),
                this.layout.right() + this.layout.rightWidth() / 2, this.layout.bottom() - 15, 0xffe6ecf4);
        clipped(context, this.message.isEmpty() ? "只读 · 最多16项接收状态、40项历史缓存" : this.message, this.layout.left() + 54,
                this.layout.footerY() + 6, this.layout.panelWidth() - 54, 0xffffd589);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSelected(DrawContext context, Entry entry, float delta)
    {
        int x = this.layout.left() + 6, w = this.layout.previewWidth() - 12;
        clipped(context, entry.label(), x, this.layout.top() + 6, w, 0xfff3f6ff);
        clipped(context, status(entry), x, this.layout.top() + 17, w, statusColor(entry));
        int y = this.layout.top() + 32, h = Math.max(20, this.layout.bottom() - y - 76);
        PrivateSharedCacheScreen.Preview ready = this.previews.get(previewKey(entry));
        if (ready != null && ready.loaded != null)
        {
            LocalModelLibrary.Loaded loaded = ready.loaded;
            if (!this.preview.render(context, loaded.model(), previewKey(entry), x, y, w, h, this.yaw, this.pitch,
                    this.ticks + delta, Map.of(), loaded.previewAnimation(), loaded.profile(),
                    ModelPreview.Context.SELECTED))
            {
                clipped(context, "预览暂不可用", x, y + h / 2, w, 0xffffd589);
            }

        }
        else
        {
            wrapped(context, ready != null && !ready.error.isEmpty() ? ready.error
                    : (entry.cached() ? "正在准备预览…" : "资源尚未保存到本机\n可以等待分发后点击刷新"), x, y + h / 2, w, 0xffffd589, 3);
        }

        wrapped(context, "本机：" + localCacheStatus(entry) + "\n只读预览，不改变人物外观\n当次授权结束后仅保留历史预览\n本人上传请点“本人已上传”", x,
                this.layout.bottom() - 66, w, 0xffa7b5c8, 6);
    }

    private void clipped(DrawContext context, String value, int x, int y, int w, int color)
    {
        context.drawTextWithShadow(super.textRenderer, super.textRenderer.trimToWidth(value, Math.max(1, w)), x, y,
                color);
    }

    private void wrapped(DrawContext context, String value, int x, int y, int w, int color, int maxLines)
    {
        List<OrderedText> lines = super.textRenderer.wrapLines(Text.literal(value), Math.max(20, w));
        for (int index = 0; index < Math.min(maxLines, lines.size()); index++)
        {
            context.drawTextWithShadow(super.textRenderer, lines.get(index), x, y + index * 10, color);
        }

    }

    private final class Card extends ButtonWidget
    {
        private final Entry entry;
        private String lastTooltip = "";

        Card(Entry entry, int x, int y)
        {
            super(x, y, GalleryPanelLayout.CARD_WIDTH, GalleryPanelLayout.CARD_HEIGHT,
                    net.minecraft.text.Text.literal(entry.label()), ignored -> select(entry),
                    DEFAULT_NARRATION_SUPPLIER);
            this.entry = entry;
            setTooltip(ModelUiTooltip.of(PrivateSharedCacheScreen.super.textRenderer,
                    PrivateSharedCacheScreen.super.width, tooltip(entry)));
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta)
        {
            context.fill(getX(), getY(), getRight(), getBottom(), 0xff303d4e);
            PrivateSharedCacheScreen.Preview ready = PrivateSharedCacheScreen.this.previews.get(previewKey(this.entry));
            boolean drawn = false;
            if (ready != null && ready.loaded != null)
            {
                LocalModelLibrary.Loaded loaded = ready.loaded;
                context.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 20);
                drawn = PrivateSharedCacheScreen.this.preview.render(context, loaded.model(), previewKey(this.entry),
                        getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 21, 0, -8,
                        PrivateSharedCacheScreen.this.ticks + delta, Map.of(), loaded.previewAnimation(),
                        loaded.profile(), ModelPreview.Context.CARD);
                context.disableScissor();
            }
            if (!drawn)
            {
                wrapped(context,
                        this.entry.cached() ? (ready != null && !ready.error.isEmpty() ? "预览失败" : "加载中…") : "等待资源",
                        getX() + 4, getY() + 32, getWidth() - 8, 0xffbccce0, 2);
            }

            boolean verified = ready != null && ready.loaded != null;
            int cloudColor = verified ? 0xff8df2b5
                    : (ready != null && !ready.error.isEmpty() ? 0xffff8585
                            : (this.entry.cached() ? 0xffffd589 : 0xff92b9df));
            CloudUploadIcon.draw(context, getX() + 2, getY() + 2, cloudColor, verified);
            String explanation = tooltip(this.entry);
            if (!explanation.equals(this.lastTooltip))
            {
                this.lastTooltip = explanation;
                setTooltip(ModelUiTooltip.of(PrivateSharedCacheScreen.super.textRenderer,
                        PrivateSharedCacheScreen.super.width, explanation));
            }
            context.fill(getX() + 1, getBottom() - 20, getRight() - 1, getBottom() - 1, 0xff263240);
            wrapped(context, this.entry.label(), getX() + 3, getBottom() - 19, getWidth() - 6, 0xfff3f0e0, 2);
            if (super.hovered || isFocused() || this.entry.key().equals(PrivateSharedCacheScreen.this.selected))
            {
                context.drawStrokedRectangle(getX(), getY(), getWidth(), getHeight(), 0xff92b9df);
            }

        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical)
    {
        if (vertical != 0 && x >= this.layout.right() && x < this.layout.right() + this.layout.rightWidth())
        {
            changePage(this.page + (vertical > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled)
    {
        if (click.button() == 0 && click.x() >= this.layout.left()
                && click.x() < this.layout.left() + this.layout.previewWidth() && click.y() >= this.layout.top() + 32
                && click.y() < this.layout.bottom() - 76)
        {
            this.dragging = true;
            if (doubled)
            {
                this.yaw = 0;
                this.pitch = -8;
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy)
    {
        if (this.dragging)
        {
            this.yaw = (this.yaw + (float) dx * 1.5f) % 360;
            this.pitch = Math.max(-90, Math.min(90, this.pitch - (float) dy));
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click)
    {
        if (this.dragging)
        {
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void removed()
    {
        this.open = false;
        this.epoch++;
        this.inFlight = 0;
        this.scanning = false;
        this.previews.clear();
        this.preview.clear();
        if (this.library != null)
        {
            this.library.close();
            this.library = null;
        }
    }

    @Override
    public void close()
    {
        super.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }
}
