package com.simmc.meplayeractions.client.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.network.ActionPayload;
import com.simmc.meplayeractions.client.ui.ActionsScreen;
import com.simmc.meplayeractions.client.ui.LocalAppearanceScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Opt-in, isolated no-server-plugin acceptance; never writes the main A/B phase or reports. */
public final class LocalAppearanceHarness {
    private static final Logger LOG = LoggerFactory.getLogger("MEPlayerActions/StandaloneE2E");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> STAGES = List.of("native", "model", "transform", "settings-ui", "local-actions-ui",
            "action", "stop", "save-reload", "resource-reload", "disable");
    private static final LocalAppearanceSettings PROFILE = new LocalAppearanceSettings(true, "ysm_02_jk", .65f, .25, .35, -.2);
    private final ClientRuntime runtime;
    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/standalone-e2e")).toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>(), observations = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final long startedNanos = System.nanoTime(), startedMillis = System.currentTimeMillis();
    private final Map<String, Object> artifactProof = artifactProof();
    private boolean connecting, configured, finished, captured, savedDisabledReload, savedEnabledReload;
    private int ticks, stage = -1, stageTicks, readyAt = -1, clearWorldTicks;
    private long requestBaseline, reloadFrameBaseline;
    private String modelHash = "", savedJson = "";
    private CompletableFuture<Void> reload;
    private String uiActionId = "";
    private boolean uiActionPressed, uiActionVerified;

    private LocalAppearanceHarness(ClientRuntime runtime) { this.runtime = runtime; }
    public static void register(ClientRuntime runtime) {
        LocalAppearanceHarness harness = new LocalAppearanceHarness(runtime);
        ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
        LOG.info("Isolated standalone local appearance acceptance; outputs {}", harness.output);
    }

    private void tick(MinecraftClient client) {
        if (finished) return;
        ticks++;
        try {
            if (System.nanoTime() - startedNanos > 240_000_000_000L) {
                check("standaloneCompletion", false, "240 second timeout; stage=" + (stage < 0 ? "join" : STAGES.get(stage)));
                finish(client); return;
            }
            if (!connecting) {
                if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null && ticks > 40) {
                    check("standaloneProductionJarLoaded", Boolean.TRUE.equals(artifactProof.get("clientFromJar")), artifactProof.toString());
                    String address = System.getProperty("meplayeractions.e2e.server", "127.0.0.1:25592");
                    ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse(address),
                            new ServerInfo("MPA standalone empty-plugin Paper", address, ServerInfo.ServerType.OTHER), false, null);
                    connecting = true;
                }
                return;
            }
            if (client.world == null || client.player == null || client.getNetworkHandler() == null) return;
            client.options.pauseOnLostFocus = false;
            boolean uiStage = stage >= 0 && STAGES.get(stage).endsWith("-ui");
            if (!uiStage && client.currentScreen != null) client.setScreen(null);
            clearWorldTicks = client.getOverlay() == null ? clearWorldTicks + 1 : 0;
            if (!configured) {
                if (clearWorldTicks < 15) return;
                configured = true;
                if (!runtime.options.enabled) runtime.toggleEnabled();
                runtime.options.showSelf = true;
                runtime.disableLocalAppearance();
                client.player.getAbilities().flying = false;
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                client.options.getGamma().setValue(1.0);
                client.getNetworkHandler().sendChatCommand("tp MPATest 0.5 -60 0.5 0 0");
                client.getNetworkHandler().sendChatCommand("time set day");
                requestBaseline = runtime.requestPacketsSent();
                check("standaloneNoServerChannel", !ClientPlayNetworking.canSend(ActionPayload.ID), "Normal Minecraft multiplayer is connected; MPA payload channel is absent");
                check("standaloneNoServerBridge", !runtime.serverBridgeConnected(), runtime.status().toString());
                next(client); return;
            }
            if (ClientPlayNetworking.canSend(ActionPayload.ID) || runtime.serverBridgeConnected()) {
                check("standaloneNoServerBridge", false, "Unexpected MPA server channel/handshake on standalone server");
                finish(client); return;
            }
            stageTicks++;
            String name = STAGES.get(stage);
            if (name.equals("save-reload")) savedProfileTick();
            if (name.equals("local-actions-ui")) localActionUiTick(client);
            var binding = own(client);
            Map<String, Object> renderer = ModelRenderer.diagnostics();
            boolean ready;
            if (name.equals("native") || name.equals("disable")) ready = binding == null && !runtime.shouldHidePlayer(client.player.getUuid())
                    && ((List<?>) renderer.get("models")).isEmpty();
            else ready = binding != null && binding.instance().startsWith("local-self:") && visible(renderer, binding);
            if (name.equals("resource-reload")) {
                ready &= reload != null && reload.isDone() && !reload.isCompletedExceptionally() && clearWorldTicks >= 10;
                if (ready && readyAt < 0) reloadFrameBaseline = ((Number) renderer.get("extractedFrames")).longValue();
                if (readyAt >= 0) ready &= ((Number) renderer.get("extractedFrames")).longValue() >= reloadFrameBaseline + 2;
            }
            if (name.equals("save-reload")) ready &= savedDisabledReload && savedEnabledReload;
            if (name.equals("settings-ui")) ready &= client.currentScreen instanceof LocalAppearanceScreen;
            if (name.equals("local-actions-ui")) ready &= client.currentScreen instanceof ActionsScreen && uiActionVerified;
            if (ready && readyAt < 0) readyAt = stageTicks;
            int delay = name.equals("action") ? 12 : 20;
            if (!captured && ready && readyAt >= 0 && stageTicks >= readyAt + delay) capture(client, name, binding, renderer);
            if (captured && stageTicks >= readyAt + (name.equals("action") ? 35 : 45)) { next(client); return; }
            if (stageTicks > 240) {
                check(name + "StandaloneReady", false, "No stable local appearance state; " + runtime.localAppearanceStatus() + "; renderer=" + renderer);
                if (!captured) capture(client, name, binding, renderer);
                next(client);
            }
        } catch (Exception failure) {
            check("standaloneHarness", false, failure.toString()); LOG.error("Standalone acceptance failed", failure); finish(client);
        }
    }

    private void next(MinecraftClient client) throws Exception {
        stage++; stageTicks = 0; readyAt = -1; captured = false;
        if (stage >= STAGES.size()) { finish(client); return; }
        String name = STAGES.get(stage);
        LOG.info("Standalone stage {}", name);
        client.setScreen(null);
        switch (name) {
            case "model" -> {
                check("standaloneBundledModelsAvailable", runtime.localModels().stream().anyMatch(model -> model.id().equals("ysm_02_jk")), runtime.localModels().toString());
                runtime.selectLocalModel("ysm_02_jk");
            }
            case "transform" -> runtime.updateLocalAppearance(PROFILE);
            case "settings-ui" -> client.setScreen(new LocalAppearanceScreen(runtime));
            case "local-actions-ui" -> {
                uiActionPressed = false; uiActionVerified = false; uiActionId = "";
                client.setScreen(new ActionsScreen(runtime, true, null));
            }
            case "action" -> check("standaloneLocalActionAccepted", runtime.playLocal("extra1"), runtime.localActions().toString());
            case "stop" -> runtime.stopLocal();
            case "save-reload" -> {
                savedJson = Files.readString(runtime.localAppearanceSettingsPath(), StandardCharsets.UTF_8);
                JsonObject document = JsonParser.parseString(savedJson).getAsJsonObject();
                var persisted = GSON.fromJson(document.get("localAppearance"), LocalAppearanceSettings.class);
                check("standaloneProfileSaved", PROFILE.equals(persisted), "Saved JSON localAppearance=" + document.get("localAppearance"));
                savedDisabledReload = false; savedEnabledReload = false;
            }
            case "resource-reload" -> reload = client.reloadResources();
            case "disable" -> runtime.disableLocalAppearance();
            default -> { }
        }
    }

    private void savedProfileTick() throws Exception {
        if (!savedDisabledReload && stageTicks >= 5) {
            JsonObject document = JsonParser.parseString(savedJson).getAsJsonObject();
            document.add("localAppearance", GSON.toJsonTree(new LocalAppearanceSettings(false, PROFILE.modelId(), PROFILE.scale(), PROFILE.offsetX(), PROFILE.offsetY(), PROFILE.offsetZ())));
            Files.writeString(runtime.localAppearanceSettingsPath(), GSON.toJson(document), StandardCharsets.UTF_8);
            runtime.reloadLocalAppearanceSettings();
            savedDisabledReload = true;
            check("standaloneSavedDisableReloaded", !runtime.localAppearance().enabled() && runtime.renderBindings().stream().noneMatch(binding -> binding.instance().startsWith("local-self:")),
                    "External clone JSON disable is actually read, without using updateLocalAppearance");
        }
        if (savedDisabledReload && !savedEnabledReload && stageTicks >= 15) {
            Files.writeString(runtime.localAppearanceSettingsPath(), savedJson, StandardCharsets.UTF_8);
            runtime.reloadLocalAppearanceSettings();
            savedEnabledReload = true;
            check("standaloneSavedProfileReloaded", PROFILE.equals(runtime.localAppearance()), "Reloaded model/scale/XYZ=" + runtime.localAppearance());
        }
    }

    private ClientRuntime.RenderBinding own(MinecraftClient client) {
        return runtime.renderBindings().stream().filter(binding -> binding.owner().equals(client.player.getUuid())).findFirst().orElse(null);
    }
    private void localActionUiTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof ActionsScreen screen)) return;
        if (!uiActionPressed && stageTicks >= 5) {
            var actions = runtime.localActions();
            var button = screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                    .filter(widget -> widget.getY() == 100 && widget.active)
                    .min(java.util.Comparator.comparingInt(ClickableWidget::getX)).orElse(null);
            if (button != null && !actions.isEmpty()) {
                uiActionId = actions.getFirst().id(); uiActionPressed = true;
                // Run the actual product button callback, which must choose playLocal rather than a server request.
                button.onPress(null);
            }
        } else if (uiActionPressed && !uiActionVerified && stageTicks >= 6) {
            var local = own(client);
            boolean localLayer = local != null && local.layers().stream()
                    .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(uiActionId));
            check("standaloneActionsUiUsesLocalApi", localLayer && runtime.requestPacketsSent() == requestBaseline,
                    "Actual local action button selected=" + uiActionId + "; local manual layer=" + localLayer
                            + "; C2S request count=" + runtime.requestPacketsSent());
            uiActionVerified = true;
            runtime.stopLocal();
        }
    }
    private void captureUi(MinecraftClient client, String name, Map<String, Object> observation) {
        Screen screen = client.currentScreen;
        List<ClickableWidget> widgets = screen == null ? List.of() : screen.children().stream()
                .filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast).filter(widget -> widget.visible).toList();
        int width = client.getWindow().getScaledWidth(), height = client.getWindow().getScaledHeight();
        boolean inBounds = !widgets.isEmpty() && widgets.stream().allMatch(widget -> widget.getWidth() > 0 && widget.getHeight() > 0
                && widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= width && widget.getBottom() <= height);
        observation.put("ui", Map.of("class", screen == null ? "" : screen.getClass().getName(), "scaledWidth", width, "scaledHeight", height,
                "controls", widgets.stream().map(widget -> Map.of("class", widget.getClass().getSimpleName(), "label", widget.getMessage().getString(),
                        "x", widget.getX(), "y", widget.getY(), "width", widget.getWidth(), "height", widget.getHeight(), "active", widget.active,
                        "value", widget instanceof TextFieldWidget field ? field.getText() : "")).toList()));
        check(name + "StandaloneControlsInBounds", inBounds, "Actual initialized UI controls=" + widgets.size() + "; scaled window=" + width + "x" + height);
        if (name.equals("settings-ui")) {
            check("standaloneSettingsUiScreen", screen instanceof LocalAppearanceScreen, screen == null ? "No screen" : screen.getClass().getName());
            var fields = widgets.stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).toList();
            Map<String, Double> expected = Map.of("缩放", (double) PROFILE.scale(), "X 位置", PROFILE.offsetX(), "Y 位置", PROFILE.offsetY(), "Z 位置", PROFILE.offsetZ());
            boolean values = fields.size() == 4 && fields.stream().allMatch(field -> {
                try { return expected.containsKey(field.getMessage().getString())
                        && Math.abs(Double.parseDouble(field.getText()) - expected.get(field.getMessage().getString())) < .000001; }
                catch (NumberFormatException invalid) { return false; }
            });
            check("standaloneSettingsUiProfileAndModel", PROFILE.equals(runtime.localAppearance()) && values
                            && runtime.localModels().stream().anyMatch(model -> model.id().equals(PROFILE.modelId())),
                    "Four initialized edit fields match active saved model/scale/XYZ; profile=" + runtime.localAppearance());
        } else {
            check("standaloneActionsUiScreen", screen instanceof ActionsScreen, screen == null ? "No screen" : screen.getClass().getName());
            List<String> labels = widgets.stream().map(widget -> widget.getMessage().getString()).toList();
            check("standaloneActionsUiSeparateModes", labels.contains("本地动作 ✓") && labels.contains("服务器动作")
                            && !labels.contains("真实坐下") && !labels.contains("真实爬行") && !runtime.localActions().isEmpty(),
                    "Initialized local actions pane has explicit local/server tabs; controls=" + labels);
        }
    }
    private static boolean visible(Map<String, Object> renderer, ClientRuntime.RenderBinding binding) {
        return ((List<?>) renderer.get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                .anyMatch(model -> model.owner().equals(binding.owner().toString()) && model.hash().equals(binding.assetHash()) && model.vertices() > 0 && model.maxY() > model.minY());
    }
    private void capture(MinecraftClient client, String name, ClientRuntime.RenderBinding binding, Map<String, Object> renderer) throws Exception {
        captured = true;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("stage", name); observation.put("stageTick", stageTicks);
        if (binding != null) observation.put("binding", Map.of("instance", binding.instance(), "hash", binding.assetHash(),
                "motionSource", binding.motionSource(), "x", binding.x(), "y", binding.y(), "z", binding.z(),
                "scale", binding.scale(), "layers", binding.layers()));
        observation.put("profile", runtime.localAppearance()); observation.put("renderer", renderer);
        observation.put("requestPacketsSent", runtime.requestPacketsSent()); observation.put("serverBridgeConnected", runtime.serverBridgeConnected());
        observations.add(observation);
        if (name.endsWith("-ui")) captureUi(client, name, observation);
        check(name + "StandaloneNoServerRequest", runtime.requestPacketsSent() == requestBaseline,
                "Only private local actions; C2S request baseline=" + requestBaseline + "; actual=" + runtime.requestPacketsSent());
        switch (name) {
            case "native" -> check("standaloneNativeBeforeSelection", binding == null && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "model" -> {
                modelHash = binding == null ? "" : binding.assetHash();
                check("standaloneLocalModelVisible", binding != null && binding.instance().startsWith("local-self:") && visible(renderer, binding), "Own bundled model renders without plugin=" + binding);
            }
            case "transform", "save-reload", "resource-reload" -> {
                var nativePos = client.player.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
                check(name + "StandaloneTransform", binding != null && binding.assetHash().equals(modelHash) && visible(renderer, binding)
                                && Math.abs(binding.scale() - PROFILE.scale()) < .000001
                                && Math.abs(binding.x() - nativePos.x - PROFILE.offsetX()) < .000001
                                && Math.abs(binding.y() - nativePos.y - PROFILE.offsetY()) < .000001
                                && Math.abs(binding.z() - nativePos.z - PROFILE.offsetZ()) < .000001,
                        "World XYZ offsets and scale match saved profile; native=" + nativePos + "; local=" + binding);
                if (name.equals("resource-reload")) check("standaloneResourceReload", reload != null && reload.isDone() && !reload.isCompletedExceptionally()
                                && ((Number) renderer.get("extractedFrames")).longValue() >= reloadFrameBaseline + 2,
                        "Fresh geometry and restored textures after resource reload=" + renderer);
            }
            case "action" -> check("standaloneLocalActionLayer", binding != null && binding.layers().stream().anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals("extra1")), "Private animation=" + binding);
            case "stop" -> check("standaloneLocalActionStopped", binding != null && binding.layers().stream().noneMatch(layer -> layer.layer().equals("manual")) && visible(renderer, binding), "Model stays visible after stopping private action=" + binding);
            case "disable" -> check("standaloneDisableRestoresNative", binding == null && !runtime.localAppearance().enabled()
                            && !runtime.shouldHidePlayer(client.player.getUuid()) && ((List<?>) renderer.get("models")).isEmpty(), "Native player restored; renderer=" + renderer);
            default -> { }
        }
        Files.createDirectories(output);
        String filename = String.format("%02d-%s.png", stage + 1, name);
        screenshots.add("screenshots/" + filename);
        client.inGameHud.getChatHud().clear(false);
        ScreenshotRecorder.saveScreenshot(output.toFile(), filename, client.getFramebuffer(), 1, message -> LOG.info("Standalone screenshot {}", filename));
    }

    private void check(String name, boolean passed, String detail) {
        checks.add(Map.of("name", name, "passed", passed, "detail", detail)); LOG.info("Standalone check {}={} {}", name, passed, detail);
    }
    private void finish(MinecraftClient client) {
        if (finished) return;
        finished = true;
        try {
            check("standaloneAllStagesCaptured", observations.size() == STAGES.size(), "Captured stages=" + observations.stream().map(row -> row.get("stage")).toList());
            check("standaloneBridgeStayedDisconnected", !runtime.serverBridgeConnected(), runtime.status().toString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", "standalone-no-server-plugins"); result.put("passed", checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
            result.put("minecraft", "1.21.11"); result.put("clientVersion", "0.4.0"); result.put("runtimeServerConnected", runtime.serverBridgeConnected());
            result.put("serverChannelAvailable", client.getNetworkHandler() != null && ClientPlayNetworking.canSend(ActionPayload.ID));
            result.put("testedClientSha256", artifactProof.get("clientArtifactSha256")); result.put("artifactProof", artifactProof);
            result.put("startedAtMillis", startedMillis); result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
            result.put("savedProfilePath", runtime.localAppearanceSettingsPath().toString());
            Files.createDirectories(output);
            Files.writeString(output.resolve("results.json"), GSON.toJson(result), StandardCharsets.UTF_8);
        } catch (Exception failure) { LOG.error("Cannot save standalone results", failure); }
        client.scheduleStop();
    }
    private static Map<String, Object> artifactProof() {
        Map<String, Object> proof = new LinkedHashMap<>();
        try {
            Path origin = FabricLoader.getInstance().getModContainer("meplayeractions").orElseThrow().getOrigin().getPaths().getFirst();
            boolean fromJar = Files.isRegularFile(origin) && origin.getFileName().toString().endsWith(".jar");
            proof.put("clientFromJar", fromJar); proof.put("clientSource", origin.toAbsolutePath().toString());
            proof.put("clientArtifactSha256", fromJar ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin))) : "");
            proof.put("clientHashSource", "FabricLoader actual loaded production mod origin");
        } catch (Exception failure) { proof.put("clientFromJar", false); proof.put("clientArtifactSha256", ""); proof.put("error", failure.toString()); }
        return Map.copyOf(proof);
    }
}
