package com.simmc.meplayeractions.client.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.network.ActionPayload;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.ui.ActionsScreen;
import com.simmc.meplayeractions.client.ui.AnimationWheelScreen;
import com.simmc.meplayeractions.client.ui.LocalAppearanceScreen;
import com.simmc.meplayeractions.client.ui.ModelSettingsScreen;
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
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

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
    private static final List<String> STAGES = List.of("native", "model", "transform", "first-person", "settings-ui", "gallery-browse-ui",
            "model-settings-ui", "wheel-ui", "local-actions-ui", "action", "stop", "save-reload", "resource-reload",
            "ui-reload-ui", "disable");
    private static final LocalAppearanceSettings PROFILE = new LocalAppearanceSettings(true, "openysm_default", .65f, .25, .35, -.2);
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
    private static final String GALLERY_DRAFT_ID = "local:UI-Test-01.bbmodel";
    private String galleryBindingInstance = "", wheelPose = "", wheelActionId = "";
    private boolean galleryVerified, settingsVerified, wheelVerified, originalFavorite, originalHeaddress;
    private int uiStartedAt = -1, wheelSlot = -1;
    private long wheelStartedAt;
    private List<String> galleryFirstPage = List.of();
    private final List<Map<String, Object>> uiEvents = new ArrayList<>();
    private final List<Integer> headdressBefore = new ArrayList<>(), headdressChanged = new ArrayList<>(), headdressRestored = new ArrayList<>();
    private Map<String, Object> galleryFixture = Map.of();

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
            if (name.equals("gallery-browse-ui")) galleryUiTick(client);
            if (name.equals("model-settings-ui")) modelSettingsUiTick(client);
            if (name.equals("wheel-ui")) wheelUiTick(client);
            var binding = own(client);
            Map<String, Object> renderer = ModelRenderer.diagnostics();
            boolean ready;
            if (name.equals("native") || name.equals("disable")) ready = binding == null && !runtime.shouldHidePlayer(client.player.getUuid())
                    && ((List<?>) renderer.get("models")).isEmpty();
            else ready = binding != null && binding.instance().startsWith("local-self:") && visible(renderer, binding);
            if (name.equals("first-person")) ready = binding != null && binding.instance().startsWith("local-self:")
                    && ((Number) renderer.get("firstPersonSelfSkipped")).intValue() > 0 && client.options.getPerspective().isFirstPerson();
            if (name.equals("resource-reload")) {
                ready &= reload != null && reload.isDone() && !reload.isCompletedExceptionally() && clearWorldTicks >= 10;
                if (ready && readyAt < 0) reloadFrameBaseline = ((Number) renderer.get("extractedFrames")).longValue();
                if (readyAt >= 0) ready &= ((Number) renderer.get("extractedFrames")).longValue() >= reloadFrameBaseline + 2;
            }
            if (name.equals("save-reload")) ready &= savedDisabledReload && savedEnabledReload;
            if (name.equals("settings-ui") || name.equals("gallery-browse-ui") || name.equals("ui-reload-ui"))
                ready &= client.currentScreen instanceof LocalAppearanceScreen gallery && previewRendered(gallery.previewDiagnostics())
                        && gallery.loadedPreviewCount() >= gallery.visibleModelIds().size();
            if (name.equals("gallery-browse-ui")) ready &= galleryVerified;
            if (name.equals("model-settings-ui")) ready &= client.currentScreen instanceof ModelSettingsScreen settings
                    && previewRendered(settings.previewDiagnostics()) && settingsVerified;
            if (name.equals("wheel-ui")) ready &= client.currentScreen instanceof AnimationWheelScreen && wheelVerified;
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
        if (stage >= 0 && STAGES.get(stage).endsWith("-ui")) {
            Screen closing = client.currentScreen;
            if (closing != null) closing.close();
            check(STAGES.get(stage) + "StandaloneCloseCallback", closing != null && client.currentScreen != closing
                    && PROFILE.equals(runtime.localAppearance()), "Actual Screen.close callback; active appearance=" + runtime.localAppearance());
        }
        stage++; stageTicks = 0; readyAt = -1; captured = false;
        uiStartedAt = -1;
        if (stage >= STAGES.size()) { finish(client); return; }
        String name = STAGES.get(stage);
        LOG.info("Standalone stage {}", name);
        client.setScreen(null);
        switch (name) {
            case "model" -> {
                check("standaloneBundledModelsAvailable", runtime.localModels().stream().anyMatch(model -> model.id().equals("openysm_default")), runtime.localModels().toString());
                runtime.selectLocalModel("openysm_default");
            }
            case "transform" -> runtime.updateLocalAppearance(PROFILE);
            case "first-person" -> client.options.setPerspective(Perspective.FIRST_PERSON);
            case "settings-ui" -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                prepareGalleryFixtures();
                client.setScreen(new LocalAppearanceScreen(runtime));
            }
            case "gallery-browse-ui" -> {
                galleryVerified = false;
                var binding = own(client); galleryBindingInstance = binding == null ? "" : binding.instance();
                originalFavorite = runtime.options.isFavorite(GALLERY_DRAFT_ID);
                client.setScreen(new LocalAppearanceScreen(runtime));
            }
            case "model-settings-ui" -> {
                settingsVerified = false; originalHeaddress = runtime.options.defaultHeaddress;
                headdressBefore.clear(); headdressChanged.clear(); headdressRestored.clear();
                client.setScreen(new ModelSettingsScreen(runtime, PROFILE.modelId(), null));
            }
            case "wheel-ui" -> {
                wheelVerified = false; wheelActionId = ""; wheelSlot = -1;
                wheelPose = client.player.getPose().name() + ":" + client.player.isSleeping() + ":" + client.player.hasVehicle();
                client.setScreen(new AnimationWheelScreen(runtime, true, null));
            }
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
            case "ui-reload-ui" -> client.setScreen(new LocalAppearanceScreen(runtime));
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
    private void prepareGalleryFixtures() throws Exception {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path directory = runtime.localModelDirectory().toAbsolutePath().normalize();
        // Copies exist only in the launcher's disposable standalone clone, never in a user's model library.
        if (game.getParent() == null || !game.getParent().getFileName().toString().equals("meplayeractions-standalone-e2e")
                || !directory.startsWith(game.resolve("config")))
            throw new IllegalStateException("Gallery fixture requires the isolated standalone game/config directory: " + directory);
        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(game.resolve("config").toRealPath()))
            throw new IllegalStateException("Gallery fixture directory resolves outside the standalone clone");
        byte[] raw = YsmFolderModel.bundledDefault();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        List<String> files = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            Path file = directory.resolve(String.format("UI-Test-%02d.bbmodel", index));
            if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(directory.toRealPath()) || !java.util.Arrays.equals(raw, Files.readAllBytes(file))))
                throw new IllegalStateException("Refusing to overwrite an unrelated gallery fixture: " + file);
            Files.write(file, raw); files.add(file.toString());
        }
        galleryFixture = Map.of("scope", "disposable standalone clone only", "source", "actual bundled OpenYSM model converted by YsmFolderModel",
                "sha256", hash, "files", files);
        check("standaloneGalleryFixturesAvailable", runtime.localModels().stream().filter(model -> model.id().startsWith("local:UI-Test-")).count() == 12,
                galleryFixture.toString());
    }
    private void uiEvent(String action, Object detail) {
        uiEvents.add(Map.of("stage", STAGES.get(stage), "stageTick", stageTicks, "action", action, "detail", detail));
    }
    private boolean draftDidNotApply(MinecraftClient client) {
        var binding = own(client);
        return PROFILE.equals(runtime.localAppearance()) && binding != null && binding.instance().equals(galleryBindingInstance);
    }
    private void galleryUiTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof LocalAppearanceScreen screen)) return;
        if (uiStartedAt < 0) {
            if (!previewRendered(screen.previewDiagnostics())) return;
            uiStartedAt = stageTicks; galleryFirstPage = List.copyOf(screen.visibleModelIds());
        }
        int age = stageTicks - uiStartedAt;
        if (age == 5) {
            screen.setSearchQuery("UI-Test-01");
            var label = runtime.localModels().stream().filter(model -> model.id().equals(GALLERY_DRAFT_ID)).map(ClientRuntime.Action::label).findFirst().orElse("");
            boolean pressed = pressButton(screen, label);
            check("standaloneGallerySearchAndDraft", pressed && screen.visibleModelIds().equals(List.of(GALLERY_DRAFT_ID))
                            && screen.selectedModelId().equals(GALLERY_DRAFT_ID) && draftDidNotApply(client),
                    "Real search field change and model-card callback; selected=" + screen.selectedModelId() + "; visible=" + screen.visibleModelIds()
                            + "; active=" + runtime.localAppearance() + "; original instance=" + galleryBindingInstance);
            uiEvent("search-card-draft", Map.of("selected", screen.selectedModelId(), "visible", screen.visibleModelIds(), "active", runtime.localAppearance()));
        }
        if (age == 10) {
            if (runtime.options.isFavorite(GALLERY_DRAFT_ID)) screen.toggleSelectedFavorite();
            screen.toggleSelectedFavorite(); screen.setFavoritesOnly(true);
            check("standaloneGalleryFavoriteFilter", runtime.options.isFavorite(GALLERY_DRAFT_ID)
                            && screen.visibleModelIds().equals(List.of(GALLERY_DRAFT_ID)) && draftDidNotApply(client),
                    "Actual persisted favorite callback and filtered grid=" + screen.visibleModelIds());
            uiEvent("favorite-filter", screen.visibleModelIds());
        }
        if (age == 18) {
            screen.setSearchQuery("");
            check("standaloneGalleryFavoriteSearchRebuild", !screen.visibleModelIds().isEmpty()
                            && screen.visibleModelIds().stream().allMatch(runtime.options::isFavorite) && draftDidNotApply(client),
                    "Removing the actual search text rebuilds the favorite-only grid=" + screen.visibleModelIds());
        }
        if (age == 24) {
            screen.setFavoritesOnly(false); screen.goToPage(0);
            galleryFirstPage = List.copyOf(screen.visibleModelIds());
            boolean pressed = pressButton(screen, "下一页");
            check("standaloneGalleryPagination", pressed && screen.pageCount() > 1 && screen.pageIndex() == 1
                            && !screen.visibleModelIds().isEmpty() && !screen.visibleModelIds().equals(galleryFirstPage) && draftDidNotApply(client),
                    "Actual next-page button rebuilt cards; first=" + galleryFirstPage + "; next=" + screen.visibleModelIds()
                            + "; page=" + screen.pageIndex() + "/" + screen.pageCount());
            uiEvent("next-page", Map.of("page", screen.pageIndex(), "visible", screen.visibleModelIds()));
        }
        if (age == 30) {
            boolean previous = pressButton(screen, "上一页");
            check("standaloneGalleryPreviousPage", previous && screen.pageIndex() == 0 && screen.visibleModelIds().equals(galleryFirstPage),
                    "Actual previous-page button restores initial card identities=" + screen.visibleModelIds());
            screen.setSearchQuery("UI-Test-01");
            screen.browseModel(GALLERY_DRAFT_ID);
            if (runtime.options.isFavorite(GALLERY_DRAFT_ID) != originalFavorite) screen.toggleSelectedFavorite();
            check("standaloneGalleryDraftNeverApplied", screen.selectedModelId().equals(GALLERY_DRAFT_ID) && draftDidNotApply(client)
                            && runtime.requestPacketsSent() == requestBaseline,
                    "Different selected draft ID remains GUI-only through search/filter/page rebuilds; active=" + runtime.localAppearance());
            galleryVerified = true;
        }
    }
    private static boolean pressButton(Screen screen, String label) {
        var button = screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                .filter(widget -> widget.visible && widget.active && widget.getMessage().getString().equals(label)).findFirst().orElse(null);
        if (button == null) return false;
        button.onPress(null); return true;
    }
    private static int maximum(List<Integer> values) { return values.stream().mapToInt(Integer::intValue).max().orElse(0); }
    private int ownWorldVertices(MinecraftClient client) {
        var binding = own(client);
        if (binding == null) return 0;
        return ((List<?>) ModelRenderer.diagnostics().get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast)
                .filter(model -> model.owner().equals(binding.owner().toString()) && model.hash().equals(binding.assetHash()))
                .mapToInt(ModelRenderer.FrameModel::vertices).sum();
    }
    private void modelSettingsUiTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof ModelSettingsScreen screen)) return;
        if (uiStartedAt < 0) {
            if (!previewRendered(screen.previewDiagnostics())) return;
            uiStartedAt = stageTicks;
        }
        int age = stageTicks - uiStartedAt;
        int vertices = ownWorldVertices(client);
        if (age < 8) headdressBefore.add(vertices);
        if (age == 8) { screen.toggleDefaultHeaddress(); uiEvent("headwear-toggle", screen.defaultHeaddress()); }
        if (age >= 10 && age < 24) headdressChanged.add(vertices);
        if (age == 24) {
            int before = maximum(headdressBefore), changed = maximum(headdressChanged);
            check("standaloneModelSettingsHeaddressGeometry", screen.defaultHeaddress() != originalHeaddress && before > 0 && changed > 0
                            && (originalHeaddress ? changed < before : changed > before) && PROFILE.equals(runtime.localAppearance()),
                    "Actual owner/world FrameModel vertices before=" + headdressBefore + "; toggled=" + headdressChanged
                            + "; original headdress=" + originalHeaddress + ". Maxima exclude short natural eye-closure samples; bow removal must change geometry.");
            screen.toggleDefaultHeaddress(); uiEvent("headwear-restore", screen.defaultHeaddress());
        }
        if (age >= 27 && age < 40) headdressRestored.add(vertices);
        if (age == 40) {
            check("standaloneModelSettingsHeaddressRestored", screen.defaultHeaddress() == originalHeaddress
                            && maximum(headdressBefore) > 0 && maximum(headdressRestored) == maximum(headdressBefore),
                    "Actual restored world vertex samples=" + headdressRestored + "; initial=" + headdressBefore);
            check("standaloneModelSettingsSaveCallback", screen.saveSettings(false) && PROFILE.equals(runtime.localAppearance())
                            && client.currentScreen == screen && runtime.requestPacketsSent() == requestBaseline,
                    "Actual product save callback preserves the validated profile and keeps the settings screen open");
            settingsVerified = true;
        }
    }
    private void wheelUiTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof AnimationWheelScreen screen)) return;
        if (uiStartedAt < 0) {
            var actions = runtime.localActions();
            int index = -1;
            for (int i = 0; i < actions.size(); i++) if (actions.get(i).id().equals("extra1")) { index = i; break; }
            if (index < 0) return;
            uiStartedAt = stageTicks; wheelActionId = actions.get(index).id(); wheelSlot = index % 8;
            boolean locked = pressButton(screen, "锁定轮盘：关");
            boolean paged = true;
            for (int i = 0; i < index / 8; i++) paged &= pressButton(screen, "下一页");
            check("standaloneWheelPageAndLock", locked && paged && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && ((List<?>) screen.diagnostics().get("visibleActionIds")).get(wheelSlot).equals(wheelActionId),
                    "Actual lock and page callbacks; wheel=" + screen.diagnostics());
        }
        int age = stageTicks - uiStartedAt;
        if (age == 5) {
            screen.beginHoldSelection();
            hoverWheel(screen);
            check("standaloneWheelHover", ((Number) screen.diagnostics().get("hovered")).intValue() == wheelSlot,
                    "Actual mouseMoved hit-tests visible wedge " + wheelSlot + "; wheel=" + screen.diagnostics());
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_1 + wheelSlot, 0, 0));
        }
        if (age == 6) {
            check("standaloneWheelUsesLocalApi", wheelManual(client) && runtime.requestPacketsSent() == requestBaseline && client.currentScreen == screen,
                    "Actual numeric-key handler starts local manual=" + wheelActionId + "; C2S=" + runtime.requestPacketsSent());
            uiEvent("wheel-numeric-action", own(client) == null ? "missing binding" : own(client).layers());
            var binding = own(client);
            wheelStartedAt = binding == null ? -1 : binding.layers().stream().filter(layer -> layer.layer().equals("manual")
                    && layer.animation().equals(wheelActionId)).mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_G, 0, 0)); // Held entry-key repeat must not re-arm a consumed numeric choice.
            screen.keyReleased(new KeyInput(GLFW.GLFW_KEY_G, 0, 0));
        }
        if (age == 7) {
            var binding = own(client);
            boolean sameStart = binding != null && binding.layers().stream().anyMatch(layer -> layer.layer().equals("manual")
                    && layer.animation().equals(wheelActionId) && layer.startedAtTick() == wheelStartedAt);
            check("standaloneWheelNumberConsumesHold", wheelStartedAt >= 0 && sameStart && runtime.requestPacketsSent() == requestBaseline,
                    "Selecting a number while holding the entry key consumes that selection; held-G repeat and release cannot restart the locked preview; startedAtTick=" + wheelStartedAt);
        }
        if (age == 16) check("standaloneWheelStopButton", pressButton(screen, "停止当前动作"), "Actual stop button callback invoked");
        if (age == 17) check("standaloneWheelStopped", own(client) != null && own(client).layers().stream().noneMatch(layer -> layer.layer().equals("manual")),
                "Actual stop callback removes the private manual layer");
        if (age == 20) {
            screen.beginHoldSelection(); hoverWheel(screen);
            screen.keyReleased(new KeyInput(GLFW.GLFW_KEY_G, 0, 0));
        }
        if (age == 21) {
            var binding = own(client);
            wheelStartedAt = binding == null ? -1 : binding.layers().stream().filter(layer -> layer.layer().equals("manual")
                    && layer.animation().equals(wheelActionId)).mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
            screen.releaseSelection(); screen.releaseSelection();
        }
        if (age == 22) {
            var binding = own(client);
            boolean sameStart = binding != null && binding.layers().stream().anyMatch(layer -> layer.layer().equals("manual")
                    && layer.animation().equals(wheelActionId) && layer.startedAtTick() == wheelStartedAt);
            check("standaloneWheelReleaseSelectsOnce", wheelStartedAt >= 0 && sameStart && runtime.requestPacketsSent() == requestBaseline,
                    "Actual entry-key release starts a private layer once; repeated release does not restart it; startedAtTick=" + wheelStartedAt);
        }
        if (age == 28) pressButton(screen, "停止当前动作");
        if (age == 30) {
            String pose = client.player.getPose().name() + ":" + client.player.isSleeping() + ":" + client.player.hasVehicle();
            check("standaloneWheelLockOnlyKeepsUi", client.currentScreen == screen && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && pose.equals(wheelPose) && PROFILE.equals(runtime.localAppearance()) && runtime.requestPacketsSent() == requestBaseline,
                    "Native pose/sleep/vehicle before=" + wheelPose + "; after=" + pose + "; local profile unchanged");
            wheelVerified = true;
        }
    }
    private static void hoverWheel(AnimationWheelScreen screen, int slot) {
        var diagnostics = screen.diagnostics();
        double radius = (((Number) diagnostics.get("innerRadius")).doubleValue() + ((Number) diagnostics.get("outerRadius")).doubleValue()) / 2;
        double angle = -Math.PI / 2 + slot * Math.PI * 2 / 8;
        screen.mouseMoved(((Number) diagnostics.get("centerX")).doubleValue() + Math.cos(angle) * radius,
                ((Number) diagnostics.get("centerY")).doubleValue() + Math.sin(angle) * radius);
    }
    private void hoverWheel(AnimationWheelScreen screen) { hoverWheel(screen, wheelSlot); }
    private boolean wheelManual(MinecraftClient client) {
        var binding = own(client);
        return binding != null && binding.layers().stream().anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(wheelActionId));
    }
    private static boolean previewRendered(Map<String, Object> diagnostics) {
        return ((Number) diagnostics.get("frameModels")).longValue() > 0 && ((Number) diagnostics.get("frameQuads")).longValue() > 0
                && ((Number) diagnostics.get("frameVertices")).longValue() > 0 && ((Number) diagnostics.get("emittedVertices")).longValue() > 0
                && ((Number) diagnostics.get("gpuPixels")).longValue() > 0;
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
        if (screen instanceof LocalAppearanceScreen gallery) {
            var fields = widgets.stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).toList();
            Map<String, Object> preview = gallery.previewDiagnostics();
            long cards = widgets.stream().filter(widget -> widget.getClass().getSimpleName().equals("ModelCard")).count();
            observation.put("gallery", Map.of("selectedModelId", gallery.selectedModelId(), "visibleModelIds", gallery.visibleModelIds(),
                    "pageIndex", gallery.pageIndex(), "pageCount", gallery.pageCount(), "loadedPreviews", gallery.loadedPreviewCount(),
                    "drawnPreviews", gallery.drawnPreviewCount(), "preview", preview, "realCardWidgets", cards));
            if (name.equals("settings-ui")) {
                check("standaloneSettingsUiScreen", true, screen.getClass().getName());
                check("standaloneGallerySearchField", fields.size() == 1 && fields.getFirst().getMessage().getString().equals("搜索模型"),
                        "Actual gallery has one search widget; fields=" + fields.stream().map(field -> field.getMessage().getString()).toList());
            }
            check(name + "StandaloneGalleryPreview", cards > 0 && cards == gallery.visibleModelIds().size() && gallery.loadedPreviewCount() > 0
                            && gallery.drawnPreviewCount() >= 2 && previewRendered(preview),
                    "Actual native GUI model meshes and VertexConsumer writes; cards=" + cards + "; preview=" + preview);
            if (name.equals("ui-reload-ui")) check("standaloneGalleryPreviewAfterReload", reload != null && reload.isDone() && !reload.isCompletedExceptionally()
                            && previewRendered(preview) && PROFILE.equals(runtime.localAppearance()),
                    "New gallery/ModelPreview instance uploads and emits vertices after actual resource reload; preview=" + preview);
        } else if (screen instanceof ModelSettingsScreen settings) {
            var fields = widgets.stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).toList();
            Map<String, Double> expected = Map.of("缩放", (double) PROFILE.scale(), "位置 X", PROFILE.offsetX(), "位置 Y", PROFILE.offsetY(), "位置 Z", PROFILE.offsetZ());
            boolean values = fields.size() == 4 && fields.stream().allMatch(field -> {
                try { return expected.containsKey(field.getMessage().getString())
                        && Math.abs(Double.parseDouble(field.getText()) - expected.get(field.getMessage().getString())) < .000001; }
                catch (NumberFormatException invalid) { return false; }
            });
            observation.put("modelSettings", Map.of("selectedModelId", settings.selectedModelId(), "draft", settings.draftSettings(),
                    "defaultHeaddress", settings.defaultHeaddress(), "preview", settings.previewDiagnostics(), "drawnPreviews", settings.drawnPreviewCount(),
                    "headwearWorldVerticesBefore", List.copyOf(headdressBefore), "headwearWorldVerticesChanged", List.copyOf(headdressChanged),
                    "headwearWorldVerticesRestored", List.copyOf(headdressRestored)));
            check("standaloneModelSettingsUiScreen", true, screen.getClass().getName());
            // Preserve the release gate's historical name, now at the dedicated four-field settings page.
            check("standaloneSettingsUiProfileAndModel", PROFILE.equals(runtime.localAppearance()) && PROFILE.equals(settings.draftSettings()) && values
                            && settings.selectedModelId().equals(PROFILE.modelId())
                            && runtime.localModels().stream().anyMatch(model -> model.id().equals(PROFILE.modelId())),
                    "Four actual settings fields match active saved model/scale/XYZ; profile=" + runtime.localAppearance());
            check("standaloneModelSettingsPreview", settings.drawnPreviewCount() > 0 && previewRendered(settings.previewDiagnostics()),
                    "Actual detail-page native mesh submission and VertexConsumer writes=" + settings.previewDiagnostics());
        } else if (screen instanceof AnimationWheelScreen wheel) {
            var diagnostics = wheel.diagnostics(); observation.put("wheel", diagnostics);
            List<String> labels = widgets.stream().map(widget -> widget.getMessage().getString()).toList();
            check("standaloneWheelUiSeparateModes", Boolean.TRUE.equals(diagnostics.get("localMode")) && labels.contains("本地动作 ✓")
                            && labels.contains("服务器动作") && !runtime.localActions().isEmpty(), "Actual wheel scopes=" + labels);
            List<?> rectangles = (List<?>) diagnostics.get("labels");
            boolean separate = !rectangles.isEmpty();
            for (int index = 0; index < rectangles.size(); index++) {
                Map<?, ?> label = (Map<?, ?>) rectangles.get(index);
                int x = number(label, "x"), y = number(label, "y"), w = number(label, "width"), h = number(label, "height");
                separate &= x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height;
                for (ClickableWidget widget : widgets) separate &= !overlap(x, y, w, h, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
                for (int other = index + 1; other < rectangles.size(); other++) {
                    Map<?, ?> next = (Map<?, ?>) rectangles.get(other);
                    separate &= !overlap(x, y, w, h, number(next, "x"), number(next, "y"), number(next, "width"), number(next, "height"));
                }
            }
            for (int index = 0; index < widgets.size(); index++) for (int other = index + 1; other < widgets.size(); other++) {
                var a = widgets.get(index); var b = widgets.get(other);
                separate &= !overlap(a.getX(), a.getY(), a.getWidth(), a.getHeight(), b.getX(), b.getY(), b.getWidth(), b.getHeight());
            }
            check("standaloneWheelLabelsAndWidgetsSeparate", separate,
                    "Real wheel text rectangles do not overlap each other or initialized widgets; labels=" + rectangles + "; window=" + width + "x" + height);
        } else if (screen instanceof ActionsScreen) {
            check("standaloneActionsUiScreen", screen instanceof ActionsScreen, screen == null ? "No screen" : screen.getClass().getName());
            List<String> labels = widgets.stream().map(widget -> widget.getMessage().getString()).toList();
            check("standaloneActionsUiSeparateModes", labels.contains("本地动作 ✓") && labels.contains("服务器动作")
                            && !labels.contains("真实坐下") && !labels.contains("真实爬行") && !runtime.localActions().isEmpty(),
                    "Initialized local actions pane has explicit local/server tabs; controls=" + labels);
        } else {
            check(name + "StandaloneUiScreen", false, screen == null ? "No screen" : screen.getClass().getName());
        }
    }
    private static int number(Map<?, ?> map, String key) { return ((Number) map.get(key)).intValue(); }
    private static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
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
        if (name.endsWith("-ui")) observation.put("uiEvents", List.copyOf(uiEvents));
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
            case "first-person" -> check("standaloneFirstPersonNativeArm", binding != null && binding.instance().startsWith("local-self:")
                            && client.options.getPerspective().isFirstPerson() && ((Number) renderer.get("firstPersonSelfSkipped")).intValue() > 0
                            && !runtime.shouldHideFirstPersonArm() && runtime.requestPacketsSent() == requestBaseline,
                    "Actual first-person extraction skips private body while native arm/held-item rendering is allowed; renderer=" + renderer);
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
            result.put("minecraft", "1.21.11"); result.put("clientVersion", "0.4.1"); result.put("runtimeServerConnected", runtime.serverBridgeConnected());
            result.put("serverChannelAvailable", client.getNetworkHandler() != null && ClientPlayNetworking.canSend(ActionPayload.ID));
            result.put("testedClientSha256", artifactProof.get("clientArtifactSha256")); result.put("artifactProof", artifactProof);
            result.put("startedAtMillis", startedMillis); result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
            result.put("savedProfilePath", runtime.localAppearanceSettingsPath().toString());
            result.put("galleryFixture", galleryFixture); result.put("uiEvents", uiEvents);
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
