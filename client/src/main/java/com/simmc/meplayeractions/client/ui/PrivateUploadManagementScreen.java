package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.network.PrivateModelSyncClient;
import com.simmc.meplayeractions.client.network.PrivateUploadCatalogSnapshot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owner-only saved archive actions. Resharing uses the current matching local file; no archive is downloaded. */
final class PrivateUploadManagementScreen extends Screen
{
    private final ClientRuntime runtime;
    private final Screen parent;
    private final PrivateUploadCatalogSnapshot.Model expected;
    private final String label;
    private final Consumer<String> result;
    private final Runnable deleting;
    private ThreadPoolExecutor worker;
    private ButtonWidget share, stop, delete;
    private boolean open, loading, previewAttempted, previewReady, checkingSource;
    private long epoch;
    private String message = "", previewError = "";
    private int ticks, left, top, panelWidth, controlsY;

    PrivateUploadManagementScreen(ClientRuntime runtime, Screen parent, PrivateUploadCatalogSnapshot.Model expected,
            String label, Consumer<String> result, Runnable deleting)
    {
        super(Text.literal("管理本人已上传模型"));
        this.runtime = runtime;
        this.parent = parent;
        this.expected = expected;
        this.label = label;
        this.result = result;
        this.deleting = deleting;
    }

    @Override
    protected void init()
    {
        this.open = true;
        this.panelWidth = Math.max(180, Math.min(330, super.width - 24));
        this.left = (super.width - this.panelWidth) / 2;
        this.top = Math.max(6, (super.height - 238) / 2);
        if (this.worker == null)
        {
            this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                    runnable ->
                    {
                        Thread thread = new Thread(runnable, "MPA-private-reshare-check");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
        }

        this.controlsY = Math.min(super.height - 100, this.top + 140);
        this.share = button("再次分享", this.left + 10, this.controlsY, this.panelWidth - 20, this::reshare);
        this.stop = button("结束分享", this.left + 10, this.controlsY + 24, this.panelWidth - 20, this::confirmStop);
        this.delete = button("删除服务器资源", this.left + 10, this.controlsY + 48, this.panelWidth - 20, this::confirmDelete);
        button("返回图库", this.left + 10, this.controlsY + 72, this.panelWidth - 20, this::close)
                .setTooltip(ModelUiTooltip.of(super.textRenderer, super.width, "返回模型图库\n不改变当前分享"));
        preparePreview();
        updateButtons();
    }

    private ButtonWidget button(String caption, int x, int y, int w, Runnable action)
    {
        return addDrawableChild(
                ButtonWidget.builder(Text.literal(caption), ignored -> action.run()).dimensions(x, y, w, 20).build());
    }

    private boolean sameSavedVersion()
    {
        return this.runtime.uploadedPrivateModels().stream().anyMatch(this.expected::equals);
    }

    private String deletionBlock()
    {
        if (!this.runtime.privateUploadCatalogReady())
        {
            return "等待服务器本人存档目录\n" + this.runtime.privateUploadCatalogStatus();
        }

        if (!sameSavedVersion())
        {
            return "服务器保存内容已变化\n返回图库，重新选择当前存档";
        }

        if (this.runtime.deletingUploadedPrivateModel(this.expected.modelId()))
        {
            return "正在等待服务器确认删除";
        }

        if (this.runtime.privateModelUploadState(this.expected.modelId()).inProgress())
        {
            return "正在发布此模型\n等待完成后管理或删除存档";
        }

        return "";
    }

    private String sharingBlock()
    {
        String directory = deletionBlock();
        if (!directory.isEmpty())
        {
            return directory;
        }

        if (!this.runtime.canActivateLocalAppearance())
        {
            return "请先解除服务器伪装\n服务器伪装与私人分享不能同时使用";
        }

        if (!this.runtime.canShareLocalModel())
        {
            return "当前无法再次分享\n" + this.runtime.privateSyncStatus();
        }

        if (!this.runtime.uploadedPrivateModelIds().contains(this.expected.modelId()))

        {
            return "本机模型未确认与存档一致\n本机缺失或内容不同均不能再次分享\n返回图库检查文件并刷新";
        }

        if (this.checkingSource)
        {
            return "正在重新核对本机完整资源\n尚未重新发布";
        }

        if (!this.previewReady)
        {
            return this.previewError.isEmpty() ? "正在准备本机模型" : "本机模型无法准备\n" + this.previewError + "\n返回图库检查文件并刷新";
        }

        return "";
    }

    private void preparePreview()
    {
        if (this.previewReady || this.previewAttempted || this.loading || !this.runtime.canShareLocalModel()
                || !this.runtime.canEditLocalAppearance()
                || !this.runtime.uploadedPrivateModelIds().contains(this.expected.modelId()) || !sameSavedVersion())
        {
            return;
        }

        this.loading = this.previewAttempted = true;
        long requestEpoch = this.epoch;
        this.runtime.loadLocalPreview(this.expected.modelId())
                .whenComplete((loaded, error) -> super.client.execute(() ->
                {
                    if (!this.open || requestEpoch != this.epoch)
                    {
                        return;
                    }

                    this.loading = false;
                    this.previewReady = error == null && loaded != null;
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null)
                    {
                        cause = cause.getCause();
                    }

                    boolean busy = cause instanceof RejectedExecutionException;
                    this.previewError = this.previewReady ? "" : (busy ? "本机预览任务繁忙，稍后重试" : "本机文件缺失、损坏或超出限制");
                    if (busy)
                    {
                        this.previewAttempted = false;
                    }
                    updateButtons();
                }));
    }

    private void updateButtons()
    {
        if (this.share == null)
        {
            return;
        }

        String shareReason = sharingBlock(), deleteReason = deletionBlock();
        this.share.active = shareReason.isEmpty();
        this.share.setMessage(Text.literal(this.checkingSource ? "正在核对资源" : "再次分享"));
        this.share.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                this.share.active ? "使用本机相同模型并再次分享\n完整资源仍须匹配当前服务器存档\n服务器缓存可免去重复传输\n不创建服务器伪装" : shareReason));
        this.delete.active = deleteReason.isEmpty();
        this.delete.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                this.delete.active ? "删除此模型的服务器存档及旧版本\n相关私人分享会结束\n本地模型文件保留\n删除前再次确认" : deleteReason));
        PrivateModelSyncClient.PublicationIdentity publication = this.runtime.privatePublicationIdentity();
        this.stop.active = publication != null && publication.modelId().equals(this.expected.modelId())
                && publication.hash().equals(this.expected.hash());
        this.stop.setTooltip(ModelUiTooltip.of(super.textRenderer, super.width,
                this.stop.active ? "结束当前私人分享\n本人的本地外观继续显示\n服务器存档和本地文件保留\n之后点击使用模型可明确恢复分享" : "此存档当前没有正在展示的私人分享"));
    }

    private void reshare()
    {
        String blocked = sharingBlock();
        if (!blocked.isEmpty())
        {
            this.message = blocked;
            updateButtons();
            return;
        }
        this.checkingSource = true;
        this.message = "正在核对本机完整资源，尚未发布";
        updateButtons();
        long requestEpoch = this.epoch;
        CompletableFuture<Boolean> sourceCheck;
        try
        {
            sourceCheck = CompletableFuture.supplyAsync(() ->
            {
                try
                {
                    return PrivateUploadSourceCheck.matches(this.runtime.localModelDirectory(), this.expected);
                }
                catch (IOException | RuntimeException failure)
                {
                    throw new CompletionException(failure);
                }
            }, this.worker);
        }
        catch (RejectedExecutionException busy)
        {
            this.checkingSource = false;
            this.message = "本机校验任务繁忙，请返回后重试";
            updateButtons();
            return;
        }
        sourceCheck.whenComplete((matches, error) -> super.client.execute(() ->
        {
            if (!this.open || requestEpoch != this.epoch)
            {
                return;
            }

            this.checkingSource = false;
            if (error != null || !Boolean.TRUE.equals(matches))
            {
                this.message = "无法再次分享：本机完整资源已缺失或与存档不同\n本机文件未上传，服务器存档保留";
                updateButtons();
                return;
            }
            // Directory version, permissions and server-disguise priority may change while source bytes are being read.
            String changed = sharingBlock();
            if (!changed.isEmpty())
            {
                this.message = changed;
                updateButtons();
                return;
            }
            if (this.runtime.uploadAndShareLocalModel(this.expected.modelId()))
            {
                this.result.accept("已请求再次分享，等待服务器确认");
                super.client.setScreen(this.parent);
            }
            else
            {
                this.message = "无法再次分享\n" + this.runtime.privateSyncStatus();
                updateButtons();
            }
        }));
    }

    private void confirmDelete()
    {
        String blocked = deletionBlock();
        if (!blocked.isEmpty())
        {
            this.message = blocked;
            updateButtons();
            return;
        }
        super.client.setScreen(new ConfirmScreen(confirmed ->
        {
            if (!confirmed)
            {
                super.client.setScreen(this);
                return;
            }
            if (this.runtime.requestDeleteUploadedPrivateModel(this.expected))
            {
                this.deleting.run();
                this.result.accept("删除请求已提交，等待服务器确认");
            }
            else
            {
                String status = this.runtime.privateUploadDeleteStatus(this.expected.modelId());
                this.result.accept(status.isEmpty() ? "当前无法删除：存档或权限已变化，请重新选择" : status);
            }
            super.client.setScreen(this.parent);
        }, Text.literal("删除服务器模型？"), Text.literal("删除 " + this.label + " 的服务器存档及已保存旧版本。\n相关私人分享会结束，本地模型文件保留。"),
                Text.literal("删除服务器资源"), Text.literal("取消")));
    }

    private void confirmStop()
    {
        PrivateModelSyncClient.PublicationIdentity publication = this.runtime.privatePublicationIdentity();
        if (publication == null || !publication.modelId().equals(this.expected.modelId())
                || !publication.hash().equals(this.expected.hash()))
        {
            this.message = "此存档的私人分享已变化或结束";
            updateButtons();
            return;
        }
        super.client.setScreen(new ConfirmScreen(confirmed ->
        {
            if (!confirmed)
            {
                super.client.setScreen(this);
                return;
            }
            if (this.runtime.stopPrivatePublication(publication))
            {
                this.result.accept("已结束私人分享，本地外观与服务器存档保留");
            }

            else
            {
                this.result.accept("私人分享已变化或结束，请重新确认；当前分享未改动");
            }

            super.client.setScreen(this.parent);
        }, Text.literal("结束私人分享？"), Text.literal("停止其他玩家观看此私人模型。\n本人的本地外观、模型文件与服务器存档保留。"), Text.literal("结束分享"),
                Text.literal("取消")));
    }

    @Override
    public void tick()
    {
        this.ticks++;
        if (this.ticks % 10 == 0)
        {
            preparePreview();
            updateButtons();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(this.left, this.top, this.left + this.panelWidth, Math.min(super.height - 4, this.top + 238),
                0xED171F2A);
        context.drawCenteredTextWithShadow(super.textRenderer, super.title, super.width / 2, this.top + 8, 0xfff3f6ff);
        context.drawCenteredTextWithShadow(super.textRenderer,
                super.textRenderer.trimToWidth(this.label, this.panelWidth - 24), super.width / 2, this.top + 24,
                0xff92b9df);
        String blocked = sharingBlock();
        String detail = (blocked.isEmpty() ? "本机已匹配，可以再次分享。" : blocked) + "\n\n"
                + "再次分享：使用本机相同资源，重新开启私人展示。\n删除：移除服务器存档及旧版本，保留本地文件。\n服务器伪装期间仍可管理、删除存档。";
        List<OrderedText> lines = super.textRenderer
                .wrapLines(Text.literal(this.message.isEmpty() ? detail : this.message), this.panelWidth - 24);
        int lineLimit = Math.max(1, Math.min(8, (this.controlsY - this.top - 50) / 11));
        for (int index = 0; index < Math.min(lineLimit, lines.size()); index++)
        {
            context.drawTextWithShadow(super.textRenderer, lines.get(index), this.left + 12, this.top + 45 + index * 11,
                    this.message.isEmpty() ? 0xffbccce0 : (this.message.startsWith("正在") ? 0xffffd589 : 0xffff8585));
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void removed()
    {
        this.open = false;
        this.epoch++;
        if (this.checkingSource)
        {
            this.message = "本机核对已取消，尚未再次分享";
        }
        this.checkingSource = false;
        this.loading = false;
        if (!this.previewReady)
        {
            this.previewAttempted = false;
        }
        if (this.worker != null)
        {
            this.worker.shutdownNow();
            this.worker = null;
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
