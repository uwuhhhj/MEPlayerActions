package com.simmc.meplayeractions.client.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.network.ActionPayload;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.ui.PlayerModelScreen;
import com.simmc.meplayeractions.client.MEPlayerActionsClient;
import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.WheelPreferences;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import com.simmc.meplayeractions.client.ui.AnimationWheelScreen;
import com.simmc.meplayeractions.client.ui.LocalAppearanceScreen;
import com.simmc.meplayeractions.client.ui.ModelSettingsScreen;
import com.simmc.meplayeractions.client.ui.ModelConfigScreen;
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
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.gui.Click;
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
    private static final List<String> FULL_STAGES = List.of("native", "model", "transform", "first-person", "settings-ui", "gallery-browse-ui",
            "model-settings-ui", "wheel-ui", "local-actions-ui", "action", "stop", "vehicle-component", "projectile-component", "model-effects", "save-reload", "resource-reload",
            "ui-reload-ui", "disable");
    private static final LocalAppearanceSettings PROFILE = new LocalAppearanceSettings(true, "openysm_default", .65f, .25, .35, -.2);
    private final ClientRuntime runtime;
    private final String validationProfile = System.getProperty("meplayeractions.standalone.focus", "full");
    private final boolean interactions = validationProfile.equals("interactions");
    private final List<String> stages = interactions ? List.of("interaction-ui") : FULL_STAGES;
    private static final String INTERACTION_MODEL = "local:UI-Interaction-Memory.bbmodel";
    private static final List<String> INTERACTION_REQUIRED = List.of(
            "standaloneInteractionJOnlyBinding", "standaloneInteractionJOpensWheel", "standaloneInteractionReferenceWheel",
            "standaloneInteractionSingleSettings", "standaloneInteractionSettingsOpensModelScreen", "standaloneInteractionSources",
            "standaloneInteractionReferenceModelScreen", "standaloneInteractionClientGallery", "standaloneInteractionClientModelSettings",
            "standaloneInteractionAdvancedPreferences", "standaloneInteractionPreferencePersistence", "standaloneInteractionInlineAuthorForms",
            "standaloneInteractionLocalActionNoServerRequest", "standaloneInteractionWheelMemory", "standaloneInteractionNoServerAuthority",
            "standaloneInteractionControlsInBounds", "standaloneInteractionCleanup",
            "standaloneWineFoxTaishoMaidGuiAndWorld", "standaloneWineFoxNewYearGuiAndWorld", "standaloneWineFoxAstronautGuiAndWorld");
    private static final List<String> WINE_FOX_IDS=List.of(BuiltinYsmModels.TAISHO_MAID_ID,BuiltinYsmModels.NEW_YEAR_ID,BuiltinYsmModels.ASTRONAUT_ID);
    private static final List<String> WINE_FOX_CHECKS=List.of("standaloneWineFoxTaishoMaidGuiAndWorld","standaloneWineFoxNewYearGuiAndWorld","standaloneWineFoxAstronautGuiAndWorld");
    private int wineFoxIndex,wineFoxStep,wineFoxWorldSamples,wineFoxContinueAt;
    private long wineFoxPreviewBaseline,wineFoxFrameBaseline,wineFoxDrawBaseline,wineFoxLastFrame=-1;
    private String wineFoxHash="",wineFoxInstance="",wineFoxHomeScreenshot="",wineFoxGameScreenshot="";
    private LocalAppearanceSettings wineFoxDraftProfile;
    private Map<String,Object> wineFoxSource=Map.of(),wineFoxPreview=Map.of(),wineFoxWorld=Map.of();
    private boolean wineFoxClassesCurrent;
    private final List<Map<String,Object>> wineFoxModels=new ArrayList<>(),wineFoxWorldFrames=new ArrayList<>();
    private static final Map<String,Map<String,Object>> GUI_PREVIEW_SOURCES=new LinkedHashMap<>();
    private int interactionStep, interactionStepAt;
    private boolean interactionCompleted, interactionControlsValid = true, interactionCardSelected, interactionHoverSelected;
    private AnimationWheelScreen interactionFormWheel;
    private String interactionAuthorGroup = "";
    private LocalAppearanceSettings interactionOriginalProfile;
    private WheelPreferences interactionOriginalWheel;
    private boolean interactionOriginalActionLock, interactionOriginalEnabled, interactionOriginalShowSelf;
    private Path interactionFixture;
    private String interactionAction = "", interactionMemorySource = "";
    private int interactionMemoryPage;
    private long interactionActionStart;
    private double interactionOriginalX;
    private String interactionHomeScreenshot="";
    private Map<String,Object> interactionHomeEvidence=Map.of();

    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/standalone-e2e")).toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>(), observations = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final java.util.Set<String> completedScreenshots=new java.util.HashSet<>();
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
    private long firstPersonArmsBaseline, firstPersonArmVerticesBaseline;
    private boolean wheelOriginalActionLock, wheelConfigVerified;
    private int configStartedAt = -1;
    private double configOriginalValue;
    private List<String> wheelAuthorIds = List.of();
    private final List<Integer> configBefore = new ArrayList<>(), configChanged = new ArrayList<>(), configRestored = new ArrayList<>();
    private List<String> galleryFirstPage = List.of();
    private final List<Map<String, Object>> uiEvents = new ArrayList<>();
    private final List<Integer> headdressBefore = new ArrayList<>(), headdressChanged = new ArrayList<>(), headdressRestored = new ArrayList<>();
    private Map<String, Object> galleryFixture = Map.of();
    private Map<String, Object> builtinPreviewAsset = Map.of();
    private final List<Map<String, Object>> previewRegressionSamples = new ArrayList<>();
    private boolean previewEntrySeen, previewEntryCompleted, previewClockValid = true, previewSharedClockValid = true;
    private boolean previewAtlasValid = true, previewLayersValid = true, previewMainEyesSeen, previewCardEyesSeen;
    private boolean previewEntryScreenshot, previewRegressionVerified, previewSameSelectionRequested, previewEntryAlphaValid = true;
    private int previewMainSamples, previewCardSamples, previewMatureSamples;
    private int previewAfterSameSelectionSamples;
    private double previewFirstStart = Double.NaN, previewPreviousAge = -1, previewMaximumAge;
    private double previewCardFirstStart = Double.NaN, previewCardPreviousAge = -1;
    private boolean galleryDragAttempted, settingsDragAttempted;
    private ModelEffectsHarness effects;
    private boolean effectsScreenshot, effectsChecksMerged;
    private String effectsScreenshotPath = "";
    private Map<String, Object> effectsReport = Map.of();
    private final String componentFixtureTag = "mpac_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<java.util.UUID> componentFixtureUuids = new ArrayList<>();
    private java.util.UUID componentEntityUuid;
    private long componentSubmissionsBaseline, componentVerticesBaseline;
    private Map<String, Object> componentProof = Map.of();
    private net.minecraft.util.math.Vec3d componentOriginalPosition;

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
                check("standaloneCompletion", false, "240 second timeout; stage=" + (stage < 0 ? "join" : stages.get(stage)));
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
            boolean uiStage = stage >= 0 && stages.get(stage).endsWith("-ui");
            if (!uiStage && client.currentScreen != null) client.setScreen(null);
            clearWorldTicks = client.getOverlay() == null ? clearWorldTicks + 1 : 0;
            if (!configured) {
                if (clearWorldTicks < 15) return;
                configured = true;
                if (interactions) {
                    interactionOriginalProfile = runtime.localAppearance(); interactionOriginalWheel = runtime.wheelPreferences();
                    interactionOriginalActionLock = runtime.localActionLocked(); interactionOriginalEnabled = runtime.options.enabled;
                    interactionOriginalShowSelf = runtime.options.showSelf;
                }
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
            String name = stages.get(stage);
            if (interactions) { interactionUiTick(client); return; }
            if (name.equals("save-reload")) savedProfileTick();
            if (name.equals("local-actions-ui")) localActionUiTick(client);
            if (name.equals("gallery-browse-ui")) galleryUiTick(client);
            if (name.equals("model-settings-ui")) modelSettingsUiTick(client);
            if (name.equals("wheel-ui")) wheelUiTick(client);
            if (name.equals("settings-ui")) previewRegressionTick(client);
            if (name.equals("vehicle-component") || name.equals("projectile-component")) componentProof = componentProof(client, name);
            if (name.equals("model-effects")) {
                effects.tick(client);
                if (!effectsScreenshot && Boolean.TRUE.equals(effects.diagnostics().get("effectsActive"))) {
                    saveScreenshot(client, "model-effects-active"); effectsScreenshot = true;
                    effectsScreenshotPath = screenshots.getLast();
                }
            }
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
            if (name.equals("model-effects")) ready &= effects.complete() && PROFILE.equals(runtime.localAppearance())
                    && binding != null && binding.assetHash().equals(modelHash);
            if (name.equals("vehicle-component") || name.equals("projectile-component")) ready &= Boolean.TRUE.equals(componentProof.get("passed"));
            if (name.equals("settings-ui") || name.equals("gallery-browse-ui") || name.equals("ui-reload-ui"))
                ready &= client.currentScreen instanceof LocalAppearanceScreen gallery && previewRendered(gallery.previewDiagnostics())
                        && gallery.loadedPreviewCount() >= gallery.visibleModelIds().size();
            if (name.equals("gallery-browse-ui")) ready &= galleryVerified;
            if (name.equals("settings-ui")) ready &= previewRegressionVerified;
            if (name.equals("settings-ui") || name.equals("ui-reload-ui")) ready &= client.currentScreen instanceof LocalAppearanceScreen gallery
                    && currentPreviewEyes(gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128));
            if (name.equals("model-settings-ui")) ready &= client.currentScreen instanceof ModelSettingsScreen settings
                    && previewRendered(settings.previewDiagnostics()) && settingsVerified
                    && singlePreviewEyes(settings.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128));
            if (name.equals("wheel-ui")) ready &= client.currentScreen instanceof AnimationWheelScreen && wheelVerified;
            if (name.equals("local-actions-ui")) ready &= client.currentScreen instanceof PlayerModelScreen && uiActionVerified;
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
        if (stage >= 0 && (stages.get(stage).equals("vehicle-component") || stages.get(stage).equals("projectile-component"))) cleanupComponentFixtures(client);
        if (stage >= 0 && stages.get(stage).equals("model-effects") && effects != null) { effects.close(); mergeEffectsChecks(); }
        if (stage >= 0 && stages.get(stage).endsWith("-ui")) {
            Screen closing = client.currentScreen;
            if (closing != null) closing.close();
            check(stages.get(stage) + "StandaloneCloseCallback", closing != null && client.currentScreen != closing
                    && PROFILE.equals(runtime.localAppearance()), "Actual Screen.close callback; active appearance=" + runtime.localAppearance());
        }
        stage++; stageTicks = 0; readyAt = -1; captured = false;
        uiStartedAt = -1;
        if (stage >= stages.size()) { finish(client); return; }
        String name = stages.get(stage);
        LOG.info("Standalone stage {}", name);
        client.setScreen(null);
        switch (name) {
            case "interaction-ui" -> {
                prepareInteractionFixture(); runtime.updateLocalAppearance(PROFILE);
                runtime.updateWheelPreferences(runtime.wheelPreferences().withSource(WheelPreferences.Source.CLIENT).withPage(WheelPreferences.Source.CLIENT, 0));
                interactionStep = 0; interactionStepAt = 0; requestInteractionWheel(client);
            }
            case "model" -> {
                check("standaloneBundledModelsAvailable", runtime.localModels().stream().anyMatch(model -> model.id().equals("openysm_default")), runtime.localModels().toString());
                runtime.selectLocalModel("openysm_default");
            }
            case "transform" -> runtime.updateLocalAppearance(PROFILE);
            case "first-person" -> {
                firstPersonArmsBaseline = ((Number) ((Map<?, ?>) ModelRenderer.diagnostics().get("components")).get("submittedArms")).longValue();
                firstPersonArmVerticesBaseline = ((Number) ((Map<?, ?>) ModelRenderer.diagnostics().get("components")).get("emittedArmVertices")).longValue();
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
            case "settings-ui" -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                readBuiltinPreviewEvidence();
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
                wheelConfigVerified = false; configStartedAt = -1; wheelOriginalActionLock = runtime.localActionLocked();
                configBefore.clear(); configChanged.clear(); configRestored.clear();
                wheelPose = client.player.getPose().name() + ":" + client.player.isSleeping() + ":" + client.player.hasVehicle();
                client.setScreen(new AnimationWheelScreen(runtime, true, null));
            }
            case "local-actions-ui" -> {
                uiActionPressed = false; uiActionVerified = false; uiActionId = "";
                client.setScreen(new PlayerModelScreen(runtime, null));
            }
            case "action" -> check("standaloneLocalActionAccepted", runtime.playLocal("extra1"), runtime.localActions().toString());
            case "stop" -> runtime.stopLocal();
            case "vehicle-component", "projectile-component" -> beginComponentFixture(client, name);
            case "model-effects" -> effects = new ModelEffectsHarness(runtime);
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
    /** New entry/source/remembered UI acceptance only; historical asset and renderer stages stay above. */
    private void interactionUiTick(MinecraftClient client) throws Exception {
        if (stageTicks > 900) { check("standaloneInteractionCompletion", false, "UI step timeout=" + interactionStep
                + "; Wine index/step="+wineFoxIndex+"/"+wineFoxStep+"; screen="+(client.currentScreen==null?"world":client.currentScreen.getClass().getName())
                + "; preview="+(client.currentScreen instanceof PlayerModelScreen hub?hub.previewDiagnostics():Map.of())); finish(client); return; }
        if (stageTicks - interactionStepAt < 4) return;
        Screen current = client.currentScreen;
        if (current != null) interactionControlsValid &= interactionControlsInBounds(client, current);
        switch (interactionStep) {
            case 0 -> {
                if (!(current instanceof AnimationWheelScreen wheel) || own(client) == null || !runtime.localModelProfile().isYsm()
                        || diagnosticNumber(wheel.diagnostics(), "emittedVertices") <= 0) return;
                var keys = java.util.Arrays.stream(client.options.allKeys).filter(key -> key.getId().startsWith("key.meplayeractions.")).toList();
                check("standaloneInteractionJOnlyBinding", keys.size() == 1 && keys.getFirst() == MEPlayerActionsClient.actionWheelKey
                        && "key.meplayeractions.action_wheel".equals(keys.getFirst().getId())
                        && KeyBindingHelper.getBoundKeyOf(keys.getFirst()).getCode() == GLFW.GLFW_KEY_J,
                        "Actual registered bindings=" + keys.stream().map(key -> key.getId() + ":" + KeyBindingHelper.getBoundKeyOf(key).getCode()).toList());
                check("standaloneInteractionJOpensWheel", number(wheel.diagnostics(), "releaseKey") == GLFW.GLFW_KEY_J,
                        "KeyBinding.onKeyPressed drove the registered END_CLIENT_TICK route, not Screen construction");
                check("standaloneInteractionReferenceWheel", referenceWheel(wheel.diagnostics()), "Actual reference polygon wheel=" + wheel.diagnostics());
                var controls = (List<?>) wheel.diagnostics().get("controls");
                check("standaloneInteractionSingleSettings", controls.stream().filter(Map.class::isInstance).map(Map.class::cast)
                        .filter(row -> "settings".equals(row.get("id"))).count() == 1
                        && Boolean.FALSE.equals(wheel.diagnostics().get("scopeButtonsVisible"))
                        && !wheel.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                        .anyMatch(button -> button.getMessage().getString().contains("本地动作") || button.getMessage().getString().contains("服务器动作")),
                        "One settings gear; source and preferences belong to the model page; controls=" + controls);
                interactionObservation(client, "j-wheel", wheel.diagnostics(), true);
                if (!interactionPress(client, wheel, label -> label.equals("⚙"))) throw new IllegalStateException("Wheel settings callback missing");
                advanceInteraction();
            }
            case 1 -> {
                if (!(current instanceof PlayerModelScreen hub)) return;
                var diagnostics = hub.diagnostics();
                if (!(diagnostics.get("leftPreview") instanceof Map<?, ?> preview) || !Boolean.TRUE.equals(preview.get("drawn"))) return;
                Map<String,Object> source=guiPreviewSource(PROFILE.modelId());
                Map<String,Object> contexts=nativePreviewContextProof(client,nativePreviewDiagnostics(hub,PROFILE.modelId()),String.valueOf(preview.get("key")),source);
                if(!Boolean.TRUE.equals(contexts.get("passed")))return;
                if(interactionHomeScreenshot.isEmpty()) {
                    var evidence=new LinkedHashMap<String,Object>(diagnostics);evidence.put("nativePreviewSource",source);evidence.put("nativeContextProof",contexts);
                    evidence.put("scaledWidth",client.getWindow().getScaledWidth());evidence.put("scaledHeight",client.getWindow().getScaledHeight());
                    evidence.put("windowScaleFactor",client.getWindow().getScaleFactor());
                    evidence.put("framebufferWidth",client.getWindow().getFramebufferWidth());evidence.put("framebufferHeight",client.getWindow().getFramebufferHeight());
                    interactionHomeEvidence=evidence;interactionObservation(client,"model-home",evidence,true);interactionHomeScreenshot=screenshots.getLast();return;
                }
                @SuppressWarnings("unchecked") Map<String,Object> capturedContext=(Map<String,Object>)interactionHomeEvidence.get("nativeContextProof");
                if(!completedScreenshots.contains(interactionHomeScreenshot))return;
                @SuppressWarnings("unchecked") Map<String,Object> pixels=interactionHomeEvidence.get("defaultVisibilityPixels") instanceof Map<?,?> cached && cached.containsKey("passed")
                        ?(Map<String,Object>)cached:defaultPngVisibilityProof(output.resolve(interactionHomeScreenshot),capturedContext,
                        ((Number)interactionHomeEvidence.get("scaledWidth")).intValue(),((Number)interactionHomeEvidence.get("scaledHeight")).intValue(),
                        ((Number)interactionHomeEvidence.get("windowScaleFactor")).intValue(),((Number)interactionHomeEvidence.get("framebufferWidth")).intValue(),
                        ((Number)interactionHomeEvidence.get("framebufferHeight")).intValue());
                interactionHomeEvidence.put("defaultVisibilityPixels",pixels);
                if(!Boolean.TRUE.equals(pixels.get("passed")))return;
                check("standaloneInteractionSettingsOpensModelScreen", "client".equals(diagnostics.get("mode")) && Boolean.TRUE.equals(diagnostics.get("localModelControlsVisible")),
                        "Actual wheel settings gear opens unified client model page=" + diagnostics);
                check("standaloneInteractionReferenceModelScreen", referenceModelScreen(hub, diagnostics) && Boolean.TRUE.equals(contexts.get("passed")),
                        "Actual source tabs/search/cards, depth-rendered owner/card cameras, unclipped head and real PNG white-eye pixels=" + interactionHomeEvidence);
                if (!interactionPress(client, hub, label -> label.startsWith("服务器下发"))) throw new IllegalStateException("Server source tab missing");
                advanceInteraction();
            }
            case 2 -> {
                if (!(current instanceof PlayerModelScreen hub)) return;
                var diagnostics = hub.diagnostics();
                check("standaloneInteractionSources", "server".equals(diagnostics.get("mode")) && Boolean.FALSE.equals(diagnostics.get("localModelControlsVisible"))
                        && Boolean.FALSE.equals(diagnostics.get("actionGrid")), "Unavailable server page can be browsed without a private action grid=" + diagnostics);
                check("standaloneInteractionNoServerAuthority", !runtime.serverBridgeConnected() && !runtime.hasOwnServerDisguise()
                        && runtime.requestPacketsSent() == requestBaseline && !ClientPlayNetworking.canSend(ActionPayload.ID),
                        "Actual server-page callback grants no server model, transport or request capability");
                interactionObservation(client, "unavailable-server-page", diagnostics, true);
                if (!interactionPress(client, hub, label -> label.startsWith("客户端"))) throw new IllegalStateException("Client source tab missing");
                advanceInteraction();
            }
            case 3 -> {
                if (!(current instanceof PlayerModelScreen hub)) return;
                if (!Boolean.TRUE.equals(hub.diagnostics().get("advanced"))) {
                    if (!interactionPress(client, hub, label -> label.startsWith("轮盘选项") || label.equals("⚙"))) throw new IllegalStateException("Advanced options callback missing");
                    return;
                }
                boolean keepCallback = interactionPress(client, hub, label -> label.startsWith("选择后保持轮盘"));
                if (!runtime.wheelPreferences().keepOpen()) keepCallback &= interactionPress(client, hub, label -> label.startsWith("选择后保持轮盘"));
                boolean lockCallback = interactionPress(client, hub, label -> label.startsWith("移动时保留本地动作"));
                check("standaloneInteractionAdvancedPreferences", keepCallback && lockCallback && runtime.wheelPreferences().keepOpen()
                        && runtime.localActionLocked() != interactionOriginalActionLock && Boolean.TRUE.equals(hub.diagnostics().get("advanced")),
                        "Actual advanced controls change separate keep-open/action-lock options=" + hub.diagnostics());
                var persisted = new ClientOptions(runtime.localAppearanceSettingsPath());
                check("standaloneInteractionPreferencePersistence", persisted.wheelPreferences().equals(runtime.wheelPreferences())
                        && persisted.localActionLocked == runtime.localActionLocked(), "Fresh Options read of actual saved settings=" + persisted.wheelPreferences());
                interactionObservation(client, "advanced-options", hub.diagnostics(), true); hub.close(); advanceInteraction();
            }
            case 4 -> {
                if (!(current instanceof AnimationWheelScreen wheel)) return;
                var slots = (List<?>) wheel.diagnostics().get("configSlots");
                var gear = slots.stream().filter(Map.class::isInstance).map(Map.class::cast).findFirst().orElse(null);
                if (gear == null) throw new IllegalStateException("Default author configuration gear missing");
                interactionFormWheel = wheel; interactionAuthorGroup = String.valueOf(gear.get("group")).replaceFirst("^#", "");
                wheel.mouseMoved(((Number) gear.get("x")).doubleValue(), ((Number) gear.get("y")).doubleValue());
                wheel.mouseClicked(new Click(((Number) gear.get("x")).doubleValue(), ((Number) gear.get("y")).doubleValue(), new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
                advanceInteraction();
            }
            case 5 -> {
                if (!(current instanceof AnimationWheelScreen wheel)) return;
                Object panelValue = wheel.diagnostics().get("formPanel");
                Map<?, ?> panel = panelValue instanceof Map<?, ?> value ? value : Map.of();
                check("standaloneInteractionInlineAuthorForms", Boolean.TRUE.equals(panel.get("visible"))
                        && panel.get("forms") instanceof List<?> forms && !forms.isEmpty()
                        && forms.stream().anyMatch(value -> value instanceof Map<?, ?> form && form.get("controls") instanceof List<?> controls
                            && controls.stream().anyMatch(item -> item instanceof Map<?, ?> control && Boolean.TRUE.equals(control.get("visible")) && Boolean.TRUE.equals(control.get("active"))))
                        && current == interactionFormWheel && interactionAuthorGroup.equals(String.valueOf(panel.get("group")).replaceFirst("^#", ""))
                        && runtime.requestPacketsSent() == requestBaseline,
                        "Actual authored gear opens forms on the same roulette screen=" + panel);
                interactionObservation(client, "inline-author-forms", wheel.diagnostics(), true);
                var action = interactionActions(wheel).stream().filter(row -> "extra1".equals(row.get("id"))).findFirst().orElseThrow();
                interactionAction = "extra1"; wheel.beginHoldSelection();
                wheel.mouseMoved(((Number) action.get("x")).doubleValue(), ((Number) action.get("y")).doubleValue());
                interactionHoverSelected = number(wheel.diagnostics(), "hovered") == number(action, "slot");
                wheel.keyPressed(new KeyInput(GLFW.GLFW_KEY_1 + number(action, "slot"), 0, 0)); advanceInteraction();
            }
            case 6 -> {
                if (!(current instanceof AnimationWheelScreen wheel)) return;
                var binding = own(client);
                boolean manual = binding != null && binding.layers().stream().anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(interactionAction));
                interactionActionStart = binding == null ? -1 : binding.layers().stream().filter(layer -> layer.layer().equals("manual") && layer.animation().equals(interactionAction)).mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
                wheel.keyReleased(new KeyInput(GLFW.GLFW_KEY_J, 0, 0)); wheel.keyReleased(new KeyInput(GLFW.GLFW_KEY_J, 0, 0));
                check("standaloneInteractionLocalActionNoServerRequest", manual && interactionHoverSelected && interactionActionStart >= 0 && runtime.requestPacketsSent() == requestBaseline
                        && client.currentScreen == wheel && runtime.wheelPreferences().keepOpen() && Boolean.TRUE.equals(wheel.diagnostics().get("releaseConsumed")), "Actual numeric callback selects local extra1 without server requests; release is consumed");
                interactionObservation(client, "local-numeric-action", wheel.diagnostics(), true);
                wheel.close(); requestInteractionWheel(client); advanceInteraction();
            }
            case 7 -> {
                if (!(current instanceof AnimationWheelScreen wheel)) return;
                if (!interactionPress(client, wheel, label -> label.equals("⚙"))) throw new IllegalStateException("Reopened wheel settings missing");
                advanceInteraction();
            }
            case 8 -> {
                if (!(current instanceof PlayerModelScreen hub)) return;
                if (!interactionCardSelected) {
                    var search = hub.children().stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).findFirst().orElse(null);
                    if (search == null) throw new IllegalStateException("Unified gallery search missing");
                    search.setText("UI-Interaction-Memory");
                    interactionCardSelected = interactionPress(client, hub, label -> label.contains("UI-Interaction-Memory"));
                    if (!interactionCardSelected) return;
                }
                if (!interactionPress(client, hub, label -> label.equals("使用模型"))) return;
                advanceInteraction();
            }
            case 9 -> {
                if (!(current instanceof PlayerModelScreen hub) || !INTERACTION_MODEL.equals(runtime.localAppearance().modelId()) || own(client) == null) return;
                check("standaloneInteractionClientGallery", interactionCardSelected && runtime.localAppearance().enabled()
                        && runtime.requestPacketsSent() == requestBaseline, "Actual search/card/use callbacks selected the disposable multi-page model=" + runtime.localAppearance());
                interactionObservation(client, "gallery-use-model", hub.diagnostics(), true);
                if (!interactionPress(client, hub, label -> label.equals("设置") || label.startsWith("详情") || label.startsWith("外观设置"))) throw new IllegalStateException("Selected-model detail settings missing");
                advanceInteraction();
            }
            case 10 -> {
                if (!(current instanceof ModelSettingsScreen settings)) return;
                var field = settings.children().stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast)
                        .filter(widget -> widget.getMessage().getString().equals("位置 X")).findFirst().orElseThrow();
                interactionOriginalX = runtime.localAppearance().offsetX(); field.setText(Double.toString(interactionOriginalX + .125));
                boolean saved = interactionPress(client, settings, label -> label.equals("保存"));
                check("standaloneInteractionClientModelSettings", saved && INTERACTION_MODEL.equals(settings.selectedModelId())
                        && Math.abs(runtime.localAppearance().offsetX() - interactionOriginalX - .125) < .000001
                        && runtime.requestPacketsSent() == requestBaseline, "Actual selected-model detail X field/save callback is private=" + runtime.localAppearance());
                field.setText(Double.toString(interactionOriginalX)); interactionPress(client, settings, label -> label.equals("保存"));
                interactionObservation(client, "model-detail-settings", Map.of("selectedModelId", settings.selectedModelId(), "profile", runtime.localAppearance()), true);
                settings.close(); advanceInteraction();
            }
            case 11 -> {
                if (!(current instanceof PlayerModelScreen hub)) return;
                hub.close(); advanceInteraction();
            }
            case 12 -> {
                if (!(current instanceof AnimationWheelScreen wheel) || number(wheel.diagnostics(), "pageCount") < 2) return;
                wheel.keyPressed(new KeyInput(GLFW.GLFW_KEY_RIGHT, 0, 0));
                interactionMemoryPage = number(wheel.diagnostics(), "page"); interactionMemorySource = String.valueOf(wheel.diagnostics().get("source"));
                interactionObservation(client, "wheel-next-page", wheel.diagnostics(), true);
                wheel.close(); requestInteractionWheel(client); advanceInteraction();
            }
            case 13 -> {
                if (!(current instanceof AnimationWheelScreen wheel)) return;
                var persisted = new ClientOptions(runtime.localAppearanceSettingsPath());
                check("standaloneInteractionWheelMemory", interactionMemoryPage > 0 && number(wheel.diagnostics(), "page") == interactionMemoryPage
                        && interactionMemorySource.equals(wheel.diagnostics().get("source")) && "CLIENT".equals(interactionMemorySource)
                        && persisted.wheelPreferences().clientPage() == interactionMemoryPage && persisted.wheelPreferences().keepOpen()
                        && Boolean.TRUE.equals(wheel.diagnostics().get("keepOpen")) && persisted.localActionLocked == runtime.localActionLocked(),
                        "Real next-page callback + close + registered J reopen restore nonzero source/page/preferences=" + wheel.diagnostics());
                interactionObservation(client, "wheel-memory-reopen", wheel.diagnostics(), true);
                if(!interactionCoordinatePress(wheel,button->button.getMessage().getString().equals("⚙")))return;
                advanceInteraction();
            }
            case 14 -> {
                if(!wineFoxUiTick(client))return;
                check("standaloneInteractionControlsInBounds", interactionControlsValid, "Visible initialized controls stayed within the actual scaled window throughout the new flow, including all three WineFox selections");
                interactionCompleted=true;finish(client);
            }
            default -> throw new IllegalStateException("Unknown interaction UI step=" + interactionStep);
        }
    }
    private void advanceInteraction() { interactionStep++; interactionStepAt = stageTicks; }
    private void requestInteractionWheel(MinecraftClient client) {
        client.setScreen(null);
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(MEPlayerActionsClient.actionWheelKey));
    }
    private static boolean interactionCoordinateClick(Screen screen,double x,double y) {
        if(!Double.isFinite(x)||!Double.isFinite(y))return false;
        screen.mouseMoved(x,y);var click=new Click(x,y,new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT,0));
        boolean accepted=screen.mouseClicked(click,false);screen.mouseReleased(click);return accepted;
    }
    private static boolean interactionCoordinatePress(Screen screen,java.util.function.Predicate<ButtonWidget> predicate) {
        var button=screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                .filter(widget->widget.visible && widget.active && predicate.test(widget)).findFirst().orElse(null);
        return button!=null && interactionCoordinateClick(screen,button.getX()+button.getWidth()/2d,button.getY()+button.getHeight()/2d);
    }
    private static Map<String,Object> classJarOrigin(Class<?> type) throws Exception {
        var location=type.getProtectionDomain().getCodeSource().getLocation();Path path=Path.of(location.toURI()).toAbsolutePath().normalize();
        boolean jar=Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
        return Map.of("class",type.getName(),"location",location.toString(),"path",path.toString(),"fromJar",jar,
                "sha256",jar?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))):"");
    }
    /** Read the author's actual declaration and clip from the loaded JAR, independent of GUI labels. */
    static Map<String,Object> guiPreviewSource(String id) throws Exception {
        if(GUI_PREVIEW_SOURCES.containsKey(id))return GUI_PREVIEW_SOURCES.get(id);
        var registered=BuiltinYsmModels.find(id).orElseThrow();String manifestPath=registered.resourceRoot()+"ysm.json";
        byte[] manifestBytes=registeredResource(manifestPath);
        var manifest=JsonParser.parseString(new String(manifestBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        var properties=manifest.getAsJsonObject("properties");
        String animation=properties.has("preview_animation")?properties.get("preview_animation").getAsString():"";
        String animationPath=registered.resourceRoot()+manifest.getAsJsonObject("files").getAsJsonObject("player")
                .getAsJsonObject("animation").get("main").getAsString();
        byte[] animationBytes=registeredResource(animationPath);
        var clips=JsonParser.parseString(new String(animationBytes,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
        var clip=clips.has(animation)?clips.getAsJsonObject(animation):new JsonObject();
        String loop=!clip.has("loop")?"ONCE":clip.get("loop").isJsonPrimitive() && clip.get("loop").getAsJsonPrimitive().isBoolean()
                ?clip.get("loop").getAsBoolean()?"LOOP":"ONCE":"hold_on_last_frame".equals(clip.get("loop").getAsString())?"HOLD":"ONCE";
        var source=new LinkedHashMap<String,Object>();source.put("modelId",id);source.put("manifest",manifestPath);
        source.put("manifestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
        source.put("properties",properties.deepCopy());source.put("animationResource",animationPath);
        source.put("animationSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(animationBytes)));
        source.put("animation",animation);source.put("loop",loop);source.put("length",clip.has("animation_length")?clip.get("animation_length").getAsDouble():0d);
        source.put("clipPresent",clips.has(animation));source.put("disableCardRotation",properties.has("disable_preview_rotation") && properties.get("disable_preview_rotation").getAsBoolean());
        source.put("background",properties.has("gui_background")?properties.get("gui_background").getAsString():"");
        source.put("foreground",properties.has("gui_foreground")?properties.get("gui_foreground").getAsString():"");
        double authorHeight=properties.has("height_scale")?properties.get("height_scale").getAsDouble():.7;
        double authorWidth=properties.has("width_scale")?properties.get("width_scale").getAsDouble():.7;
        source.put("shape",Map.of("heightScalePropertyPresent",properties.has("height_scale"),"widthScalePropertyPresent",properties.has("width_scale"),
                "heightScale",authorHeight,"widthScale",authorWidth,"modelScale",List.of(authorHeight,authorWidth,authorHeight),"initialYOffset",.01));
        if(id.equals(PROFILE.modelId()))source.put("defaultVisual",defaultVisualSource(registered.resourceRoot(),manifest,clips));
        Map<String,Object> frozen=Map.copyOf(source);GUI_PREVIEW_SOURCES.put(id,frozen);return frozen;
    }
    private static Map<String,Object> defaultVisualSource(String root,JsonObject manifest,JsonObject animations) throws Exception {
        String geometryPath=root+manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonObject("model").get("main").getAsString();
        byte[] geometryBytes=registeredResource(geometryPath);
        var geometry=JsonParser.parseString(new String(geometryBytes,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        int width=geometry.getAsJsonObject("description").get("texture_width").getAsInt(),height=geometry.getAsJsonObject("description").get("texture_height").getAsInt();
        var bounds=new LinkedHashMap<String,List<Double>>();List<List<Double>> headBounds=new ArrayList<>();double openAfter=0;
        for(var value:geometry.getAsJsonArray("bones")) {
            var bone=value.getAsJsonObject();String name=bone.get("name").getAsString();
            if(!List.of("Head","RightEyelid","LeftEyelid").contains(name))continue;
            var face=bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv").getAsJsonObject("north");
            var uv=face.getAsJsonArray("uv");var size=face.getAsJsonArray("uv_size");
            bounds.put(name,List.of(uv.get(0).getAsDouble(),uv.get(1).getAsDouble(),uv.get(0).getAsDouble()+size.get(0).getAsDouble(),uv.get(1).getAsDouble()+size.get(1).getAsDouble()));
            if(name.equals("Head"))for(var entry:bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv").entrySet()) {
                var headUv=entry.getValue().getAsJsonObject().getAsJsonArray("uv");var headSize=entry.getValue().getAsJsonObject().getAsJsonArray("uv_size");
                double u=headUv.get(0).getAsDouble(),v=headUv.get(1).getAsDouble(),du=headSize.get(0).getAsDouble(),dv=headSize.get(1).getAsDouble();
                headBounds.add(List.of(Math.min(u,u+du),Math.min(v,v+dv),Math.max(u,u+du),Math.max(v,v+dv)));
            }
            if(!name.equals("Head")) {
                var scales=animations.getAsJsonObject("pre_parallel1").getAsJsonObject("bones").getAsJsonObject(name).getAsJsonObject("scale");
                for(var entry:scales.entrySet()) {
                    var keyframe=entry.getValue().getAsJsonObject();
                    if(keyframe.getAsJsonArray("post").get(1).getAsDouble()==1)openAfter=Math.max(openAfter,Double.parseDouble(entry.getKey())*20);
                }
            }
        }
        String texturePath=root+manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonArray("texture").get(0).getAsString();
        byte[] textureBytes=registeredResource(texturePath);var texture=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(textureBytes));
        var eye=bounds.get("RightEyelid");List<Integer> white=new ArrayList<>();
        for(int y=eye.get(1).intValue();y<eye.get(3);y++)for(int x=eye.get(0).intValue();x<eye.get(2);x++)white.add(texture.getRGB(x,y));
        var proof=new LinkedHashMap<String,Object>();proof.put("geometry",geometryPath);proof.put("geometrySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometryBytes)));
        proof.put("texture",texturePath);proof.put("textureSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(textureBytes)));
        proof.put("textureWidth",width);proof.put("textureHeight",height);proof.put("frontUvPixels",bounds);proof.put("whiteSourcePixels",white);
        proof.put("headUvPixels",headBounds);
        proof.put("blinkAnimation","pre_parallel1");proof.put("blinkCycleTicks",animations.getAsJsonObject("pre_parallel1").get("animation_length").getAsDouble()*20);
        proof.put("blinkSafeOpenStartTicks",Math.ceil(openAfter)+2);proof.put("blinkSafeEndMarginTicks",3);return proof;
    }
    private static byte[] registeredResource(String path) throws Exception {
        try(var input=BuiltinYsmModels.class.getResourceAsStream(path)) {
            if(input==null)throw new IllegalStateException("Missing registered GUI source: "+path);
            byte[] raw=input.readNBytes(8*1024*1024+1);
            if(raw.length==0 || raw.length>8*1024*1024)throw new IllegalStateException("Invalid registered GUI source size: "+path);
            return raw;
        }
    }
    private static boolean nearDiagnostic(Map<?,?> draw,String name,double expected) {
        return Double.isFinite(diagnosticNumber(draw,name)) && Math.abs(diagnosticNumber(draw,name)-expected)<.00001;
    }
    private static boolean matureNativeDraw(Map<?,?> draw) {
        return diagnosticNumber(draw,"vertices")>0 && diagnosticNumber(draw,"quads")>0
                && diagnosticNumber(draw,"vertices")==diagnosticNumber(draw,"quads")*4
                && diagnosticNumber(draw,"sampleTick")>=12 && diagnosticNumber(draw,"entryProgress")>=.999
                && diagnosticNumber(draw,"startCount")==1 && Double.isFinite(diagnosticNumber(draw,"startedAtTick"));
    }
    private static boolean nativeScaleAndExecution(Map<?,?> draw,Map<String,Object> source) {
        if(!(source.get("shape") instanceof Map<?,?> shape) || !(draw.get("modelScale") instanceof Map<?,?> scale)
                || !(draw.get("nativeBackend") instanceof Map<?,?> backend) || !(draw.get("nativeExecution") instanceof Map<?,?> execution))return false;
        List<Double> range=diagnosticVector(draw.get("modelVertexDepthRange"),2);
        return nearDiagnostic(scale,"x",diagnosticNumber(shape,"heightScale")) && nearDiagnostic(scale,"y",diagnosticNumber(shape,"widthScale"))
                && nearDiagnostic(scale,"z",diagnosticNumber(shape,"heightScale"))
                && "minecraft-special-gui-offscreen".equals(backend.get("backend"))
                && "meplayeractions:pipeline/gui_model_native_depth".equals(backend.get("pipeline"))
                && "minecraft:core/position_tex_color".equals(backend.get("shader")) && "minecraft:core/position_tex_color".equals(backend.get("fragmentShader"))
                && "LEQUAL_DEPTH_TEST".equals(backend.get("depthTest")) && Boolean.TRUE.equals(backend.get("depthWrite")) && Boolean.TRUE.equals(backend.get("perVertexDepth"))
                && diagnosticNumber(execution,"renderCount")>0
                && diagnosticNumber(execution,"emittedVertices")==(diagnosticNumber(draw,"quads")-diagnosticNumber(draw,"decorationQuads"))*4*diagnosticNumber(execution,"renderCount")
                && Boolean.TRUE.equals(execution.get("colorAttachment")) && Boolean.TRUE.equals(execution.get("depthAttachment"))
                && "RGBA8".equals(execution.get("colorFormat")) && "DEPTH32".equals(execution.get("depthFormat"))
                && diagnosticNumber(execution,"targetWidth")>0 && diagnosticNumber(execution,"targetHeight")>0
                && range.size()==2 && Double.isFinite(range.get(0)) && Double.isFinite(range.get(1)) && range.get(1)>range.get(0);
    }
    private static boolean ownerNativeContext(Map<?,?> draw,String animation,double height,double scale) {
        return "OWNER".equals(draw.get("context")) && Boolean.TRUE.equals(draw.get("nativeCamera"))
                && "openysm-owner-inventory".equals(draw.get("cameraSource")) && matureNativeDraw(draw)
                && nearDiagnostic(draw,"displaySize",70) && nearDiagnostic(draw,"pixelsPerBlock",70)
                && nearDiagnostic(draw,"nativeEntityHeight",height) && nearDiagnostic(draw,"nativeEntityScale",scale)
                && nearDiagnostic(draw,"anchorY",height/scale*.5+.0625) && Boolean.FALSE.equals(draw.get("rotationDisabled"))
                && Double.isFinite(diagnosticNumber(draw,"yaw")) && Double.isFinite(diagnosticNumber(draw,"pitch"))
                && "".equals(draw.get("background")) && "".equals(draw.get("foreground")) && nearDiagnostic(draw,"decorationQuads",0)
                && draw.get("controllerSlots") instanceof List<?> slots && !slots.isEmpty() && !slots.contains("player.cap")
                && draw.get("layers") instanceof List<?> layers && !layers.contains(animation);
    }
    private static boolean cardNativeContext(Map<?,?> draw,Map<String,Object> source) {
        boolean locked=Boolean.TRUE.equals(source.get("disableCardRotation"));
        String animation=String.valueOf(source.get("animation"));
        int decorations=(String.valueOf(source.get("background")).isEmpty()?0:1)+(String.valueOf(source.get("foreground")).isEmpty()?0:1);
        List<Map<?,?>> requests=diagnosticRows(draw,"layerRequests");
        boolean actualCap=requests.size()==1 && "player.cap".equals(requests.getFirst().get("layer"))
                && animation.equals(requests.getFirst().get("animation")) && source.get("loop").equals(requests.getFirst().get("loop"));
        return actualCap && nativeScaleAndExecution(draw,source) && "CARD".equals(draw.get("context")) && Boolean.TRUE.equals(draw.get("nativeCamera"))
                && "openysm-dummy-card".equals(draw.get("cameraSource")) && matureNativeDraw(draw)
                && nearDiagnostic(draw,"displaySize",30) && nearDiagnostic(draw,"pixelsPerBlock",30)
                && nearDiagnostic(draw,"nativeEntityHeight",1.8) && nearDiagnostic(draw,"nativeEntityScale",1)
                && nearDiagnostic(draw,"anchorY",.9+(locked?5.5/30:0))
                && Boolean.valueOf(locked).equals(draw.get("rotationDisabled"))
                && nearDiagnostic(draw,"yaw",locked?0:-20) && nearDiagnostic(draw,"pitch",locked?0:-10)
                && source.get("background").equals(draw.get("background")) && source.get("foreground").equals(draw.get("foreground"))
                && nearDiagnostic(draw,"decorationQuads",decorations)
                && List.of("player.cap").equals(draw.get("controllerSlots")) && List.of(animation).equals(draw.get("layers"));
    }
    /** Both contexts must be real current submissions; their independent clocks need not be equal. */
    static Map<String,Object> nativePreviewContextProof(MinecraftClient client,Map<?,?> diagnostics,String key,Map<String,Object> source) {
        List<Map<?,?>> draws=diagnosticRows(diagnostics,"draws").stream().filter(row->key.equals(row.get("key"))).toList();
        Map<?,?> owner=draws.stream().filter(row->"OWNER".equals(row.get("context"))).findFirst().orElse(Map.of());
        Map<?,?> card=draws.stream().filter(row->"CARD".equals(row.get("context"))).findFirst().orElse(Map.of());
        double height=client.player==null?Double.NaN:client.player.getHeight(),scale=client.player==null?Double.NaN:client.player.getScale();
        String animation=String.valueOf(source.get("animation"));
        boolean ownerValid=ownerNativeContext(owner,animation,height,scale) && nativeScaleAndExecution(owner,source),cardValid=cardNativeContext(card,source);
        Map<String,Object> visibility=source.get("defaultVisual") instanceof Map<?,?> visual?defaultProjectedVisibility(owner,card,visual):Map.of();
        boolean visualValid=visibility.isEmpty() || Boolean.TRUE.equals(visibility.get("passed"));
        var proof=new LinkedHashMap<String,Object>();proof.put("passed",Boolean.TRUE.equals(source.get("clipPresent")) && ownerValid && cardValid && visualValid);
        proof.put("owner",owner);proof.put("card",card);proof.put("ownerValid",ownerValid);proof.put("cardValid",cardValid);
        proof.put("actualOwnerNativeHeight",height);proof.put("actualOwnerNativeScale",scale);
        proof.put("sourcePreviewAnimation",animation);proof.put("sourcePreviewAnimationSha256",source.get("animationSha256"));
        proof.put("sourcePreviewAnimationLoop",source.get("loop"));proof.put("sourcePreviewAnimationLength",source.get("length"));
        proof.put("independentContexts",!owner.isEmpty() && !card.isEmpty() && owner!=card);
        proof.put("sourceShape",source.get("shape"));proof.put("actualOwnerPlayerAgeTicks",client.player==null?0:client.player.age);
        if(!visibility.isEmpty())proof.put("defaultProjectedVisibility",visibility);
        return proof;
    }
    static Map<String,Object> nativePreviewDiagnostics(PlayerModelScreen screen,String id) throws Exception {
        var source=guiPreviewSource(id);
        if(!(source.get("defaultVisual") instanceof Map<?,?> visual) || !(visual.get("frontUvPixels") instanceof Map<?,?> regions))return screen.previewDiagnostics();
        var eye=diagnosticVector(regions.get("RightEyelid"),4);
        float width=(float)diagnosticNumber(visual,"textureWidth"),height=(float)diagnosticNumber(visual,"textureHeight");
        Map<String,Object> diagnostics=screen.previewDiagnostics((float)(eye.get(0)/width),(float)(eye.get(1)/height),(float)(eye.get(2)/width),(float)(eye.get(3)/height));
        List<Map<String,Object>> headDiagnostics=new ArrayList<>();
        for(Object value:(List<?>)visual.get("headUvPixels")) {
            var head=diagnosticVector(value,4);
            headDiagnostics.add(screen.previewDiagnostics((float)(head.get(0)/width),(float)(head.get(1)/height),(float)(head.get(2)/width),(float)(head.get(3)/height)));
        }
        List<Map<String,Object>> draws=new ArrayList<>();
        for(Map<?,?> draw:diagnosticRows(diagnostics,"draws")) {
            Map<String,Object> copy=new LinkedHashMap<>();draw.forEach((name,value)->copy.put(String.valueOf(name),value));
            var heads=headDiagnostics.stream().flatMap(query->diagnosticRows(query,"draws").stream()).filter(row->draw.get("key").equals(row.get("key"))
                    && draw.get("context").equals(row.get("context"))).flatMap(row->diagnosticRows(row,"faces").stream()).distinct().toList();
            copy.put("headFaces",heads);draws.add(copy);
        }
        var result=new LinkedHashMap<String,Object>(diagnostics);result.put("draws",draws);return result;
    }
    private static boolean faceMatchesSource(Map<?,?> face,List<Double> bounds,double width,double height) {
        if(!(face.get("sourceUv") instanceof List<?> uvs) || uvs.size()!=4 || diagnosticVector(face.get("depths"),4).size()!=4)return false;
        double minU=Double.POSITIVE_INFINITY,minV=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
        for(Object value:uvs) {
            var uv=diagnosticVector(value,2);if(uv.isEmpty())return false;
            minU=Math.min(minU,uv.get(0)*width);maxU=Math.max(maxU,uv.get(0)*width);minV=Math.min(minV,uv.get(1)*height);maxV=Math.max(maxV,uv.get(1)*height);
        }
        return Math.abs(minU-bounds.get(0))<.001 && Math.abs(minV-bounds.get(1))<.001
                && Math.abs(maxU-bounds.get(2))<.001 && Math.abs(maxV-bounds.get(3))<.001;
    }
    private static double projectedFaceArea(Map<?,?> face) {
        if(!(face.get("points") instanceof List<?> points) || points.size()!=4)return 0;
        double area=0;
        for(int index=0;index<4;index++) {
            var a=diagnosticVector(points.get(index),2);var b=diagnosticVector(points.get((index+1)%4),2);
            if(a.isEmpty() || b.isEmpty())return 0;
            area+=a.get(0)*b.get(1)-b.get(0)*a.get(1);
        }
        return Math.abs(area)*.5;
    }
    private static boolean faceInsideClip(Map<?,?> face,Map<?,?> clip) {
        if(!(face.get("points") instanceof List<?> points) || points.size()!=4)return false;
        return points.stream().allMatch(value->{var point=diagnosticVector(value,2);return point.size()==2 && Double.isFinite(point.get(0)) && Double.isFinite(point.get(1))
                && point.get(0)>=diagnosticNumber(clip,"x") && point.get(0)<=diagnosticNumber(clip,"x")+diagnosticNumber(clip,"width")
                && point.get(1)>=diagnosticNumber(clip,"y") && point.get(1)<=diagnosticNumber(clip,"y")+diagnosticNumber(clip,"height");});
    }
    private static Map<String,Object> defaultProjectedVisibility(Map<?,?> owner,Map<?,?> card,Map<?,?> source) {
        if(!(source.get("frontUvPixels") instanceof Map<?,?> regions))return Map.of("passed",false);
        var eyeUv=diagnosticVector(regions.get("RightEyelid"),4);
        var headUvs=((List<?>)source.get("headUvPixels")).stream().map(value->diagnosticVector(value,4)).toList();
        List<Map<String,Object>> contexts=new ArrayList<>();
        for(Map<?,?> draw:List.of(owner,card)) {
            var eyes=diagnosticRows(draw,"faces").stream().filter(face->faceMatchesSource(face,eyeUv,diagnosticNumber(source,"textureWidth"),diagnosticNumber(source,"textureHeight"))
                    && projectedFaceArea(face)>.25).toList();
            var heads=diagnosticRows(draw,"headFaces").stream().filter(face->headUvs.stream().anyMatch(uv->faceMatchesSource(face,uv,diagnosticNumber(source,"textureWidth"),diagnosticNumber(source,"textureHeight")))).toList();
            Map<?,?> clip=draw.get("clip") instanceof Map<?,?> value?value:Map.of();
            double age=diagnosticNumber(draw,"sampleTick"),cycle=diagnosticNumber(source,"blinkCycleTicks"),phase=age%cycle;
            boolean open=Double.isFinite(phase) && phase>=diagnosticNumber(source,"blinkSafeOpenStartTicks") && phase<cycle-diagnosticNumber(source,"blinkSafeEndMarginTicks");
            boolean contained=eyes.size()==2 && heads.size()==6 && eyes.stream().allMatch(face->faceInsideClip(face,clip)) && heads.stream().allMatch(face->faceInsideClip(face,clip));
            contexts.add(Map.of("context",String.valueOf(draw.get("context")),"passed",open && contained,"sourceBlinkSafeOpen",open,
                    "sampleTick",Double.isFinite(age)?age:-1,"blinkPhaseTicks",Double.isFinite(phase)?phase:-1,
                    "headFullyInsideClip",contained,"eyeFaces",eyes,"headFaces",heads,"clip",clip));
        }
        return Map.of("passed",contexts.size()==2 && contexts.stream().allMatch(row->Boolean.TRUE.equals(row.get("passed"))),"contexts",contexts,"source",source);
    }
    private static boolean insideProjectedQuad(List<?> points,double x,double y) {
        double sign=0;
        for(int i=0;i<4;i++) {
            var a=diagnosticVector(points.get(i),2);var b=diagnosticVector(points.get((i+1)%4),2);if(a.isEmpty() || b.isEmpty())return false;
            double cross=(b.get(0)-a.get(0))*(y-a.get(1))-(b.get(1)-a.get(1))*(x-a.get(0));
            if(Math.abs(cross)>1e-7){if(sign!=0 && Math.signum(cross)!=sign)return false;sign=Math.signum(cross);}
        }
        return sign!=0;
    }
    /** Read actual framebuffer PNG pixels after the asynchronous screenshot has been written. */
    static Map<String,Object> defaultPngVisibilityProof(Path path,Map<String,Object> proof,int scaledWidth,int scaledHeight,
                                                       int windowScaleFactor,int framebufferWidth,int framebufferHeight) throws Exception {
        if(!Files.isRegularFile(path))return Map.of("pending",true);
        byte[] bytes=Files.readAllBytes(path);java.awt.image.BufferedImage image;
        try {image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));}
        catch(java.io.IOException incomplete){return Map.of("pending",true,"readError",incomplete.toString());}
        if(image==null)return Map.of("pending",true);
        if(!(proof.get("defaultProjectedVisibility") instanceof Map<?,?> projected) || !(projected.get("source") instanceof Map<?,?> source)
                || !(source.get("whiteSourcePixels") instanceof List<?> sourcePixels))return Map.of("passed",false,"reason","Missing original default eye/clip evidence");
        int minimumSource=sourcePixels.stream().mapToInt(value->{int color=((Number)value).intValue();return Math.min(color>>16&255,Math.min(color>>8&255,color&255));}).min().orElse(255);
        int threshold=(int)Math.floor(minimumSource*.45),maximumChannelSpread=24;
        // GuiRenderer projects using framebuffer dimensions / the real integer GUI scale, rather than ceil-scaled window dimensions.
        boolean dimensionsMatch=windowScaleFactor>0 && image.getWidth()==framebufferWidth && image.getHeight()==framebufferHeight
                && Math.ceil((double)framebufferWidth/windowScaleFactor)==scaledWidth && Math.ceil((double)framebufferHeight/windowScaleFactor)==scaledHeight;
        if(!dimensionsMatch)return Map.of("passed",false,"reason","Screenshot framebuffer or GUI scale changed after preview capture");
        double sx=windowScaleFactor,sy=windowScaleFactor;
        List<Map<String,Object>> contexts=new ArrayList<>();
        for(Map<?,?> context:diagnosticRows(projected,"contexts")) {
            List<Map<String,Object>> eyes=new ArrayList<>();
            for(Map<?,?> face:diagnosticRows(context,"eyeFaces")) {
                if(!(face.get("points") instanceof List<?> points) || points.size()!=4)continue;
                double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
                for(Object value:points){var point=diagnosticVector(value,2);minX=Math.min(minX,point.get(0)*sx);maxX=Math.max(maxX,point.get(0)*sx);minY=Math.min(minY,point.get(1)*sy);maxY=Math.max(maxY,point.get(1)*sy);}
                int tested=0,white=0;List<List<Integer>> samples=new ArrayList<>();
                for(int y=Math.max(0,(int)Math.floor(minY));y<Math.min(image.getHeight(),(int)Math.ceil(maxY));y++)
                    for(int x=Math.max(0,(int)Math.floor(minX));x<Math.min(image.getWidth(),(int)Math.ceil(maxX));x++) {
                        if(!insideProjectedQuad(points,(x+.5)/sx,(y+.5)/sy))continue;
                        tested++;int color=image.getRGB(x,y),r=color>>16&255,g=color>>8&255,b=color&255;
                        if(Math.min(r,Math.min(g,b))>=threshold && Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=maximumChannelSpread) {
                            white++;if(samples.size()<16)samples.add(List.of(x,y,color));
                        }
                    }
                eyes.add(Map.of("points",points,"testedPixels",tested,"visibleNeutralEyePixels",white,"pixelSamples",samples));
            }
            contexts.add(Map.of("context",context.get("context"),"eyes",eyes,"passed",eyes.size()==2 && eyes.stream().allMatch(row->diagnosticNumber(row,"visibleNeutralEyePixels")>0)));
        }
        var result=new LinkedHashMap<String,Object>();result.put("passed",Boolean.TRUE.equals(projected.get("passed")) && contexts.size()==2 && contexts.stream().allMatch(row->Boolean.TRUE.equals(row.get("passed"))));
        result.put("screenshot",path.toAbsolutePath().toString());result.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("width",image.getWidth());result.put("height",image.getHeight());result.put("scaledWidth",scaledWidth);result.put("scaledHeight",scaledHeight);
        result.put("windowScaleFactor",windowScaleFactor);result.put("framebufferWidth",framebufferWidth);result.put("framebufferHeight",framebufferHeight);
        result.put("minimumWhiteChannel",threshold);result.put("maximumWhiteChannelSpread",maximumChannelSpread);result.put("contexts",contexts);return result;
    }
    private Map<String,Object> readWineFoxSource(String id) throws Exception {
        var model=BuiltinYsmModels.find(id).orElseThrow();String path=model.resourceRoot()+"ysm.json";byte[] raw;
        try(var input=BuiltinYsmModels.class.getResourceAsStream(path)){if(input==null)throw new IllegalStateException("Missing registered WineFox manifest: "+path);raw=input.readAllBytes();}
        var manifest=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();
        String geometryPath=model.resourceRoot()+manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonObject("model").get("main").getAsString();
        byte[] geometry;try(var input=BuiltinYsmModels.class.getResourceAsStream(geometryPath)){if(input==null)throw new IllegalStateException("Missing authored WineFox geometry: "+geometryPath);geometry=input.readAllBytes();}
        var main=JsonParser.parseString(new String(geometry,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        List<String> bones=main.getAsJsonArray("bones").asList().stream().map(value->value.getAsJsonObject().get("name").getAsString()).distinct().toList();
        var source=new LinkedHashMap<String,Object>();source.put("resourceRoot",model.resourceRoot());source.put("registryModelId",model.id());source.put("registryLabel",model.label());
        source.put("manifest",path);source.put("manifestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        source.put("spec",manifest.get("spec").getAsInt());source.put("declaredFiles",manifest.getAsJsonObject("files").deepCopy());
        source.put("primaryGeometry",geometryPath);source.put("primaryGeometrySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometry)));
        source.put("primaryBoneNames",bones);source.put("registryClassOrigin",classJarOrigin(BuiltinYsmModels.class));
        source.put("modelRendererClassOrigin",classJarOrigin(ModelRenderer.class));source.put("expectedClientJarSha256",artifactProof.get("clientArtifactSha256"));
        source.put("guiPreview",guiPreviewSource(id));source.put("shape",guiPreviewSource(id).get("shape"));return source;
    }
    private static List<Map<?,?>> diagnosticRows(Map<?,?> diagnostics,String key) {
        return diagnostics.get(key) instanceof List<?> values?values.stream().filter(Map.class::isInstance).<Map<?,?>>map(value->(Map<?,?>)value).toList():List.of();
    }
    private boolean wineFoxUiTick(MinecraftClient client) throws Exception {
        String id=WINE_FOX_IDS.get(wineFoxIndex);
        if(wineFoxStep==0 && client.currentScreen instanceof AnimationWheelScreen wheel) {
            if(interactionCoordinatePress(wheel,button->button.getMessage().getString().equals("⚙")))return false;
            return false;
        }
        if(wineFoxStep==0 && client.currentScreen instanceof PlayerModelScreen hub) {
            if(!"client".equals(hub.diagnostics().get("mode")))return false;
            var search=hub.children().stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).findFirst().orElseThrow();
            if(!search.getText().equals(id))search.setText(id);
            Map<?,?> card=diagnosticRows(hub.galleryDiagnostics(),"cards").stream().filter(row->id.equals(row.get("modelId"))).findFirst().orElse(Map.of());
            if(card.isEmpty())return false;
            wineFoxDraftProfile=runtime.localAppearance();wineFoxPreviewBaseline=(long)diagnosticNumber(hub.previewDiagnostics(),"emittedVertices");
            if(!interactionCoordinateClick(hub,diagnosticNumber(card,"x")+diagnosticNumber(card,"width")/2,diagnosticNumber(card,"y")+diagnosticNumber(card,"height")/2))return false;
            if(!id.equals(hub.selectedModelId()))throw new IllegalStateException("Actual WineFox card callback selected another ID");
            wineFoxSource=readWineFoxSource(id);wineFoxStep=1;return false;
        }
        if(wineFoxStep==1 && client.currentScreen instanceof PlayerModelScreen hub) {
            Map<String,Object> gallery=hub.galleryDiagnostics();Map<String,Object> actual=hub.previewDiagnostics();
            Map<String,Object> guiSource=guiPreviewSource(id);String prefix=id+":";
            Map<?,?> draftDraw=diagnosticRows(actual,"draws").stream().filter(row->String.valueOf(row.get("key")).startsWith(prefix)
                    && cardNativeContext(row,guiSource)).findFirst().orElse(Map.of());
            if(draftDraw.isEmpty())return false;
            String key=String.valueOf(draftDraw.get("key"));
            String hash=key.substring(prefix.length());if(!hash.matches("[a-f0-9]{64}"))return false;
            Map<?,?> card=diagnosticRows(gallery,"cards").stream().filter(row->id.equals(row.get("modelId"))).findFirst().orElse(Map.of());
            if(!Boolean.TRUE.equals(card.get("loaded")) || !Boolean.TRUE.equals(card.get("drawn")) || !Boolean.TRUE.equals(card.get("selected"))
                    || diagnosticNumber(actual,"emittedVertices")<=wineFoxPreviewBaseline || diagnosticNumber(actual,"frameVertices")<=0)return false;
            if(!wineFoxDraftProfile.equals(runtime.localAppearance()))throw new IllegalStateException("Browsing WineFox card applied a private model before use");
            var draft=new LinkedHashMap<String,Object>();draft.put("modelId",id);draft.put("hash",hash);draft.put("selectedCard",card);
            draft.put("actualDraws",List.of(draftDraw));draft.put("profileBeforeUse",wineFoxDraftProfile);draft.put("profileUnchanged",true);
            draft.put("leftPreviewBeforeUse",gallery.getOrDefault("leftPreview",Map.of()));
            draft.put("emittedVertices",diagnosticNumber(actual,"emittedVertices"));draft.put("baselineEmittedVertices",wineFoxPreviewBaseline);
            wineFoxHash=hash;var preview=new LinkedHashMap<String,Object>();preview.put("modelId",id);preview.put("hash",hash);
            preview.put("draftCallback",true);preview.put("profileBeforeUse",wineFoxDraftProfile);preview.put("draftPreview",draft);wineFoxPreview=preview;
            Map<String,Object> renderer=ModelRenderer.diagnostics();
            wineFoxFrameBaseline=(long)diagnosticNumber(renderer,"extractedFrames");wineFoxDrawBaseline=(long)diagnosticNumber(renderer,"drawnBatches");
            if(!interactionCoordinatePress(hub,button->button.getMessage().getString().equals("使用模型")))return false;
            wineFoxPreview=new LinkedHashMap<>(wineFoxPreview);wineFoxPreview.put("useCallback",true);
            wineFoxStep=2;wineFoxWorldSamples=0;wineFoxWorldFrames.clear();wineFoxLastFrame=-1;wineFoxInstance="";
            hub.close();client.setScreen(null);client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);return false;
        }
        if(wineFoxStep==2) {
            var binding=own(client);Map<String,Object> renderer=ModelRenderer.diagnostics();long frame=(long)diagnosticNumber(renderer,"extractedFrames");
            Map<?,?> shape=(Map<?,?>)wineFoxSource.get("shape");
            ModelRenderer.FrameModel body=renderer.get("models") instanceof List<?> values?values.stream().filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                    .filter(value->client.player.getUuidAsString().equals(value.owner()) && wineFoxHash.equals(value.hash()) && "local-self".equals(value.motionSource()) && value.vertices()>0).findFirst().orElse(null):null;
            boolean ready=client.currentScreen==null && binding!=null && id.equals(runtime.localAppearance().modelId()) && runtime.localAppearance().enabled()
                    && binding.instance().startsWith("local-self:") && wineFoxHash.equals(binding.assetHash()) && body!=null && frame>wineFoxFrameBaseline
                    && diagnosticNumber(renderer,"drawnBatches")>wineFoxDrawBaseline && !runtime.serverBridgeConnected() && !runtime.hasOwnServerDisguise()
                    && runtime.requestPacketsSent()==requestBaseline && body.instance().equals(binding.instance()) && body.nativeYsm()
                    && Math.abs(body.modelScaleX()-diagnosticNumber(shape,"heightScale"))<.00001
                    && Math.abs(body.modelScaleY()-diagnosticNumber(shape,"widthScale"))<.00001
                    && Math.abs(body.modelScaleZ()-diagnosticNumber(shape,"heightScale"))<.00001
                    && Math.abs(body.bindingScale()-binding.scale())<.00001 && Math.abs(body.initialYOffset()-.01)<.00001;
            if(!ready){wineFoxWorldSamples=0;return false;}
            if(wineFoxInstance.isEmpty())wineFoxInstance=binding.instance();
            if(!wineFoxInstance.equals(binding.instance())){wineFoxWorldSamples=0;return false;}
            if(frame!=wineFoxLastFrame) {
                wineFoxLastFrame=frame;wineFoxWorldSamples++;
                wineFoxWorldFrames.add(Map.of("extractedFrame",frame,"vertices",body.vertices(),"drawnBatches",diagnosticNumber(renderer,"drawnBatches"),
                        "owner",binding.owner().toString(),"instance",binding.instance(),"hash",binding.assetHash(),"sampleTick",binding.serverTick(),"body",body));
            }
            if(wineFoxWorldSamples<3)return false;
            Map<String,Object> world=new LinkedHashMap<>();world.put("owner",binding.owner().toString());world.put("instance",binding.instance());world.put("hash",binding.assetHash());
            world.put("extractedFrame",frame);world.put("baselineFrame",wineFoxFrameBaseline);world.put("vertices",body.vertices());
            world.put("drawnBatches",diagnosticNumber(renderer,"drawnBatches"));world.put("baselineDrawnBatches",wineFoxDrawBaseline);
            world.put("frames",List.copyOf(wineFoxWorldFrames));world.put("modelRendererClassOrigin",wineFoxSource.get("modelRendererClassOrigin"));
            world.put("nativeYsm",body.nativeYsm());world.put("sourceShape",shape);world.put("modelScale",Map.of("x",body.modelScaleX(),"y",body.modelScaleY(),"z",body.modelScaleZ()));
            world.put("bindingScale",body.bindingScale());world.put("initialYOffset",body.initialYOffset());
            boolean sourceBones=wineFoxSource.get("primaryBoneNames") instanceof List<?> names && names.stream().allMatch(name->binding.model().hasBone(String.valueOf(name)));
            world.put("primarySourceBonesPresent",sourceBones);
            Map<?,?> registryOrigin=(Map<?,?>)wineFoxSource.get("registryClassOrigin"),rendererOrigin=(Map<?,?>)wineFoxSource.get("modelRendererClassOrigin");
            boolean jar=Boolean.TRUE.equals(registryOrigin.get("fromJar")) && Boolean.TRUE.equals(rendererOrigin.get("fromJar"))
                    && artifactProof.get("clientArtifactSha256").equals(registryOrigin.get("sha256")) && artifactProof.get("clientArtifactSha256").equals(rendererOrigin.get("sha256"));
            wineFoxWorld=Map.copyOf(world);wineFoxClassesCurrent=jar;
            interactionObservation(client,"winefox-"+id+"-game",Map.of("modelId",id,"source",wineFoxSource,"assetHash",wineFoxHash,
                    "draftPreview",wineFoxPreview.get("draftPreview"),"world",wineFoxWorld,"sourceClassesFromTestedJar",jar),true);
            wineFoxGameScreenshot=screenshots.getLast();wineFoxStep=3;requestInteractionWheel(client);return false;
        }
        if(wineFoxStep==3) {
            if(client.currentScreen instanceof AnimationWheelScreen wheel) {interactionCoordinatePress(wheel,button->button.getMessage().getString().equals("⚙"));return false;}
            if(client.currentScreen instanceof PlayerModelScreen hub) {
                var search=hub.children().stream().filter(TextFieldWidget.class::isInstance).map(TextFieldWidget.class::cast).findFirst().orElseThrow();
                search.setText(id);wineFoxPreviewBaseline=(long)diagnosticNumber(hub.previewDiagnostics(),"emittedVertices");wineFoxStep=4;
            }
            return false;
        }
        if(wineFoxStep==4 && client.currentScreen instanceof PlayerModelScreen hub) {
            Map<String,Object> gallery=hub.galleryDiagnostics(),actual=hub.previewDiagnostics();String key=id+":"+wineFoxHash;
            var binding=own(client);
            if(binding==null || !wineFoxInstance.equals(binding.instance()) || !wineFoxHash.equals(binding.assetHash())
                    || !id.equals(runtime.localAppearance().modelId()) || runtime.requestPacketsSent()!=requestBaseline)return false;
            if(!(gallery.get("leftPreview") instanceof Map<?,?> left) || !id.equals(left.get("modelId")) || !key.equals(left.get("key")) || !Boolean.TRUE.equals(left.get("drawn")))return false;
            Map<?,?> card=diagnosticRows(gallery,"cards").stream().filter(row->id.equals(row.get("modelId"))).findFirst().orElse(Map.of());
            Map<String,Object> contexts=nativePreviewContextProof(client,actual,key,guiPreviewSource(id));
            if(!Boolean.TRUE.equals(contexts.get("passed")) || !Boolean.TRUE.equals(card.get("loaded")) || !Boolean.TRUE.equals(card.get("drawn"))
                    || !Boolean.TRUE.equals(card.get("selected")) || diagnosticNumber(actual,"emittedVertices")<=wineFoxPreviewBaseline)return false;
            List<Map<?,?>> draws=diagnosticRows(actual,"draws").stream().filter(row->key.equals(row.get("key")) && matureNativeDraw(row)).toList();
            var preview=new LinkedHashMap<String,Object>(wineFoxPreview);preview.put("selectedCard",card);preview.put("leftPreview",left);
            preview.put("vertices",diagnosticNumber(actual,"frameVertices"));preview.put("actualDraws",draws);preview.put("nativeContextProof",contexts);
            preview.put("emittedVertices",diagnosticNumber(actual,"emittedVertices"));preview.put("baselineEmittedVertices",wineFoxPreviewBaseline);
            preview.put("ownerCapturedAfterActualUse",true);wineFoxPreview=preview;
            var evidence=new LinkedHashMap<String,Object>();evidence.put("modelId",id);evidence.put("source",wineFoxSource);evidence.put("assetHash",wineFoxHash);
            evidence.put("preview",wineFoxPreview);evidence.put("world",wineFoxWorld);evidence.put("sourceClassesFromTestedJar",wineFoxClassesCurrent);
            interactionObservation(client,"winefox-"+id+"-home",Map.of("modelId",id,"source",wineFoxSource,"preview",wineFoxPreview,"gallery",gallery),true);
            wineFoxHomeScreenshot=screenshots.getLast();evidence.put("screenshots",List.of(wineFoxHomeScreenshot,wineFoxGameScreenshot));
            wineFoxModels.add(Map.copyOf(evidence));check(WINE_FOX_CHECKS.get(wineFoxIndex),wineFoxClassesCurrent
                            && Boolean.TRUE.equals(wineFoxWorld.get("primarySourceBonesPresent")) && Boolean.TRUE.equals(contexts.get("passed")),
                    "Real CARD draft leaves world unchanged; use callback creates current local body, then J/settings reopens actual OWNER inventory and independent author CARD cap previews: "+evidence);
            wineFoxStep=5;wineFoxContinueAt=stageTicks;return false;
        }
        if(wineFoxStep==5 && stageTicks-wineFoxContinueAt>=4) {
            if(++wineFoxIndex>=WINE_FOX_IDS.size())return true;
            wineFoxStep=0;requestInteractionWheel(client);
        }
        return false;
    }
    private static List<Map<?, ?>> interactionActions(AnimationWheelScreen wheel) {
        Object entries = wheel.diagnostics().get("actions");
        return entries instanceof List<?> list ? list.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value).toList() : List.of();
    }
    private static boolean referenceWheel(Map<String,Object> diagnostics) {
        if (!Boolean.TRUE.equals(diagnostics.get("upstreamRoulette")) || !Boolean.TRUE.equals(diagnostics.get("polygon"))
                || diagnosticNumber(diagnostics, "emittedVertices") <= 0
                || !(diagnostics.get("polygons") instanceof List<?> polygons) || polygons.isEmpty() || polygons.size() > 24) return false;
        for (Object entry : polygons) {
            if (!(entry instanceof Map<?, ?> polygon) || !(polygon.get("vertices") instanceof List<?> vertices) || vertices.size() != 4) return false;
            if (vertices.stream().anyMatch(value -> !(value instanceof List<?> point) || point.size() != 2 || point.stream().anyMatch(axis -> !(axis instanceof Number number) || !Double.isFinite(number.doubleValue())))) return false;
            double area = 0;
            for (int index = 0; index < 4; index++) {
                var point = (List<?>) vertices.get(index); var next = (List<?>) vertices.get((index + 1) % 4);
                area += ((Number) point.get(0)).doubleValue() * ((Number) next.get(1)).doubleValue()
                        - ((Number) next.get(0)).doubleValue() * ((Number) point.get(1)).doubleValue();
            }
            if (Math.abs(area) < 1) return false;
        }
        if (!(diagnostics.get("actions") instanceof List<?> actions) || actions.isEmpty() || actions.size() > 8) return false;
        long drawnActionSlots = polygons.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(row -> "action".equals(row.get("kind"))).map(row -> row.get("slot")).distinct().count();
        return drawnActionSlots == actions.size();
    }
    private static boolean referenceModelScreen(PlayerModelScreen hub, Map<String,Object> diagnostics) {
        boolean gallery = Boolean.TRUE.equals(diagnostics.get("galleryLayout")) || Boolean.TRUE.equals(diagnostics.get("upstreamGallery"));
        var buttons = hub.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast).filter(button -> button.visible).toList();
        boolean sources = buttons.stream().anyMatch(button -> button.getMessage().getString().startsWith("客户端") && button.getWidth() >= 90)
                && buttons.stream().anyMatch(button -> button.getMessage().getString().startsWith("服务器下发") && button.getWidth() >= 90);
        boolean actualGallery = diagnostics.get("gallery") instanceof Map<?, ?> evidence
                && "left-preview/right-search-model-cards".equals(evidence.get("layout"))
                && "0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85".equals(evidence.get("referenceRevision"))
                && evidence.get("leftPreview") instanceof Map<?, ?> preview && Boolean.TRUE.equals(preview.get("drawn"))
                && evidence.get("cards") instanceof List<?> cards && cards.stream().anyMatch(value -> value instanceof Map<?, ?> card && Boolean.TRUE.equals(card.get("drawn")));
        return gallery && actualGallery && sources && Boolean.FALSE.equals(diagnostics.get("actionGrid"))
                && hub.children().stream().anyMatch(TextFieldWidget.class::isInstance)
                && hub.children().stream().anyMatch(widget -> widget.getClass().getSimpleName().equals("ModelCard"));
    }
    private static boolean interactionPress(MinecraftClient client, Screen screen, java.util.function.Predicate<String> label) {
        for (int attempt = 0; attempt < 12; attempt++) {
            var button = screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                    .filter(widget -> widget.active && label.test(widget.getMessage().getString())).findFirst().orElse(null);
            if (button == null) return false;
            if (button.visible) { button.onPress(null); return true; }
            screen.mouseScrolled(client.getWindow().getScaledWidth() / 2d, client.getWindow().getScaledHeight() / 2d, 0, button.getY() > client.getWindow().getScaledHeight() / 2 ? -1 : 1);
        }
        return false;
    }
    private static boolean interactionControlsInBounds(MinecraftClient client, Screen screen) {
        int width = client.getWindow().getScaledWidth(), height = client.getWindow().getScaledHeight();
        return screen.children().stream().filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast).filter(widget -> widget.visible)
                .allMatch(widget -> widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= width && widget.getBottom() <= height);
    }
    private void interactionObservation(MinecraftClient client, String phase, Map<String,Object> diagnostics, boolean screenshot) throws Exception {
        observations.add(Map.of("stage", "interaction-ui", "phase", phase, "stageTick", stageTicks, "diagnostics", diagnostics,
                "profile", runtime.localAppearance(), "requestPacketsSent", runtime.requestPacketsSent()));
        if (screenshot) saveScreenshot(client, "interaction-" + phase);
    }
    private void prepareInteractionFixture() throws Exception {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path directory = runtime.localModelDirectory().toAbsolutePath().normalize();
        if (game.getParent() == null || !game.getParent().getFileName().toString().equals("meplayeractions-standalone-e2e") || !directory.startsWith(game.resolve("config")))
            throw new IllegalStateException("Interaction fixture requires the disposable standalone clone: " + directory);
        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(game.resolve("config").toRealPath())) throw new IllegalStateException("Interaction directory escapes clone");
        JsonObject model = JsonParser.parseString(new String(YsmFolderModel.bundledDefault(), StandardCharsets.UTF_8)).getAsJsonObject();
        new ArrayList<>(model.keySet()).stream().filter(key -> key.startsWith("ysm_")).forEach(model::remove);
        var animations = new com.google.gson.JsonArray();
        for (int index = 0; index < 17; index++) { var clip = new JsonObject(); clip.addProperty("name", String.format(java.util.Locale.ROOT,"memory_%02d",index)); clip.addProperty("length",2); clip.addProperty("loop","loop"); animations.add(clip); }
        model.add("animations", animations); byte[] bytes = GSON.toJson(model).getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("UI-Interaction-Memory.bbmodel");
        if (Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(directory.toRealPath()) || !java.util.Arrays.equals(bytes,Files.readAllBytes(file))))
            throw new IllegalStateException("Refusing to overwrite unrelated interaction fixture: " + file);
        Files.write(file,bytes); interactionFixture=file;
    }
    private void cleanupInteractions(MinecraftClient client) throws Exception {
        runtime.stopLocal(); client.setScreen(null);
        if (interactionOriginalProfile != null) {
            runtime.updateLocalAppearance(interactionOriginalProfile); runtime.updateWheelPreferences(interactionOriginalWheel);
            runtime.options.localActionLocked=interactionOriginalActionLock; runtime.options.enabled=interactionOriginalEnabled;
            runtime.options.showSelf=interactionOriginalShowSelf; runtime.options.save();
        }
        if (interactionFixture != null) Files.deleteIfExists(interactionFixture);
        check("standaloneInteractionCleanup", interactionOriginalProfile != null && interactionOriginalProfile.equals(runtime.localAppearance())
                && interactionOriginalWheel.equals(runtime.wheelPreferences()) && runtime.options.localActionLocked==interactionOriginalActionLock
                && runtime.options.enabled==interactionOriginalEnabled && runtime.options.showSelf==interactionOriginalShowSelf
                && (interactionFixture==null || !Files.exists(interactionFixture)) && runtime.requestPacketsSent()==requestBaseline,
                "Original saved profile/options restored and only the disposable memory fixture removed");
    }
    private Map<String,Object> interactionDelivery() {
        var relevant=checks.stream().filter(row->INTERACTION_REQUIRED.contains(row.get("name"))).toList();
        boolean passed=interactionCompleted && INTERACTION_REQUIRED.stream().allMatch(name->relevant.stream().anyMatch(row->name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("passed"))))
                && relevant.stream().allMatch(row->Boolean.TRUE.equals(row.get("passed")));
        return Map.of("scope","interactions","passed",passed,"requiredChecks",INTERACTION_REQUIRED,"checks",relevant,"observations",observations,
                "wineFoxModels",List.copyOf(wineFoxModels));
    }

    private static String uuidNbt(java.util.UUID uuid) {
        long most = uuid.getMostSignificantBits(), least = uuid.getLeastSignificantBits();
        return "[I;" + (int) (most >>> 32) + "," + (int) most + "," + (int) (least >>> 32) + "," + (int) least + "]";
    }
    private void beginComponentFixture(MinecraftClient client, String name) {
        componentEntityUuid = java.util.UUID.randomUUID(); componentFixtureUuids.add(componentEntityUuid);
        if (componentOriginalPosition == null) componentOriginalPosition = client.player.getEntityPos();
        Map<?, ?> components = (Map<?, ?>) ModelRenderer.diagnostics().get("components");
        componentSubmissionsBaseline = ((Number) components.get("submittedComponents")).longValue();
        componentVerticesBaseline = ((Number) components.get("emittedComponentVertices")).longValue();
        componentProof = Map.of(); client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        boolean vehicle = name.equals("vehicle-component");
        var position = componentOriginalPosition.add(vehicle ? 0 : 1.6, vehicle ? .1 : 1, vehicle ? 0 : 1.5);
        String coordinates = String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", position.x, position.y, position.z);
        String nbt = "{UUID:" + uuidNbt(componentEntityUuid) + ",Tags:[\"" + componentFixtureTag + "\"],Invulnerable:1b"
                + (vehicle ? "" : ",Owner:" + uuidNbt(client.player.getUuid()) + ",NoGravity:1b") + "}";
        String command = "summon minecraft:" + (vehicle ? "oak_boat" : "arrow") + " " + coordinates + " " + nbt;
        if (command.length() > 256) throw new IllegalStateException("Component fixture command exceeds Minecraft's 256-character limit");
        client.getNetworkHandler().sendChatCommand(command);
        if (vehicle) client.getNetworkHandler().sendChatCommand("ride MPATest mount @e[type=minecraft:oak_boat,tag=" + componentFixtureTag + ",limit=1]");
    }
    private void cleanupComponentFixtures(MinecraftClient client) {
        if (client.getNetworkHandler() == null || client.player == null) return;
        if (client.player.hasVehicle()) client.getNetworkHandler().sendChatCommand("ride MPATest dismount");
        client.getNetworkHandler().sendChatCommand("kill @e[tag=" + componentFixtureTag + "]");
        if (componentOriginalPosition != null) client.getNetworkHandler().sendChatCommand(String.format(java.util.Locale.ROOT,
                "tp MPATest %.6f %.6f %.6f 0 0", componentOriginalPosition.x, componentOriginalPosition.y, componentOriginalPosition.z));
    }
    private Map<String, Object> componentProof(MinecraftClient client, String name) {
        String kind = name.equals("vehicle-component") ? "vehicle" : "projectile";
        net.minecraft.entity.Entity entity = null;
        for (var candidate : client.world.getEntities()) if (candidate.getUuid().equals(componentEntityUuid)) { entity = candidate; break; }
        boolean nativeOwner = entity != null && (kind.equals("vehicle") ? client.player.getVehicle() == entity && entity.hasPassenger(client.player)
                : entity instanceof net.minecraft.entity.projectile.ProjectileEntity projectile && projectile.getOwner() == client.player);
        var binding = own(client); Map<?, ?> components = (Map<?, ?>) ModelRenderer.diagnostics().get("components");
        Map<?, ?> draw = ((List<?>) components.get("submittedComponentDraws")).stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(row -> componentEntityUuid.toString().equals(row.get("entity"))).findFirst().orElse(Map.of());
        var source = runtime.localModelProfile().components().stream().filter(value -> value.kind().equals(kind) && value.hash().equals(draw.get("hash")))
                .findFirst().orElse(null);
        String expectedSource = kind.equals("vehicle") ? "models/boat.json" : "models/arrow.json";
        boolean authorAsset = source != null && source.metadata().has("model") && expectedSource.equals(source.metadata().get("model").getAsString());
        boolean current = nativeOwner && binding != null && binding.instance().startsWith("local-self:") && binding.assetHash().equals(modelHash)
                && PROFILE.equals(runtime.localAppearance()) && client.player.getUuid().toString().equals(draw.get("owner"))
                && binding.instance().equals(draw.get("instance")) && kind.equals(draw.get("kind")) && authorAsset;
        boolean emitted = !draw.isEmpty() && diagnosticNumber(draw, "emittedVertices") > 0 && diagnosticNumber(draw, "vertices") > 0
                && ((Number) components.get("submittedComponents")).longValue() > componentSubmissionsBaseline
                && ((Number) components.get("emittedComponentVertices")).longValue() > componentVerticesBaseline
                && ((List<?>) components.get("activeReplacements")).contains(componentEntityUuid.toString());
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("passed", current && emitted); proof.put("nativeOwnerObserved", nativeOwner); proof.put("currentBindingAndAuthorAsset", current);
        proof.put("actualNativeEntityUuid", entity == null ? "" : entity.getUuidAsString());
        proof.put("nativeType", entity == null ? "" : net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        proof.put("nativeEntityId", entity == null ? -1 : entity.getId()); proof.put("expectedUuid", componentEntityUuid.toString());
        proof.put("originalSource", expectedSource); proof.put("nativeEntityDraw", draw); proof.put("actualVerticesEmitted", emitted);
        proof.put("sourceComponentMetadata", source == null ? Map.of() : source.metadata());
        proof.put("renderer", components); return Map.copyOf(proof);
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

    private static byte[] builtinResource(String relative) throws Exception {
        try (var input = LocalAppearanceHarness.class.getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/" + relative)) {
            if (input == null) throw new IllegalStateException("Missing real bundled preview source: " + relative);
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("Bundled preview source size: " + relative);
            return bytes;
        }
    }

    private void readBuiltinPreviewEvidence() throws Exception {
        byte[] geometryBytes = builtinResource("models/main.json"), animationBytes = builtinResource("animations/main.animation.json");
        byte[] manifestBytes = builtinResource("ysm.json"), textureBytes = builtinResource("textures/default.png");
        JsonObject geometry = JsonParser.parseString(new String(geometryBytes, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject animations = JsonParser.parseString(new String(animationBytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
        JsonObject manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Map<String, Object>> whiteEyeFaces = new ArrayList<>();
        List<String> eyeBones = new ArrayList<>();
        for (var value : geometry.getAsJsonArray("bones")) {
            JsonObject bone = value.getAsJsonObject(); String name = bone.get("name").getAsString();
            if (List.of("RightEyelid", "LeftEyelid", "RightEyesBase", "LeftEyesBase", "RightPupil", "LeftPupil").contains(name)
                    && bone.has("cubes") && !bone.getAsJsonArray("cubes").isEmpty()) eyeBones.add(name);
            if (!List.of("RightEyelid", "LeftEyelid").contains(name)) continue;
            JsonObject face = bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv").getAsJsonObject("north");
            var uv = face.getAsJsonArray("uv"); var size = face.getAsJsonArray("uv_size");
            double u = uv.get(0).getAsDouble(), v = uv.get(1).getAsDouble();
            whiteEyeFaces.add(Map.of("bone", name, "sourcePixelBounds", List.of(u, v, u + size.get(0).getAsDouble(), v + size.get(1).getAsDouble())));
        }
        boolean sourceBlink = animations.has("pre_parallel1");
        if (sourceBlink) for (String side : List.of("RightEyelid", "LeftEyelid")) {
            JsonObject scales = animations.getAsJsonObject("pre_parallel1").getAsJsonObject("bones").getAsJsonObject(side).getAsJsonObject("scale");
            sourceBlink &= scales.getAsJsonObject("0.0").getAsJsonArray("post").get(1).getAsDouble() == 1
                    && scales.getAsJsonObject("0.0833").getAsJsonArray("post").get(1).getAsDouble() == 0
                    && scales.getAsJsonObject("0.2917").getAsJsonArray("post").get(1).getAsDouble() == 1;
        }
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(textureBytes));
        boolean whitePixels = image != null && image.getWidth() == 128 && image.getHeight() == 128;
        List<String> actualEyePixels = new ArrayList<>();
        if (whitePixels) for (int y = 42; y < 44; y++) for (int x = 23; x < 25; x++) {
            int color = image.getRGB(x, y); actualEyePixels.add(Integer.toHexString(color));
            whitePixels &= (color >>> 24) == 255 && (color >> 16 & 255) >= 240 && (color >> 8 & 255) >= 240 && (color & 255) >= 240;
        }
        boolean sensitiveEdges = image != null && (image.getRGB(25, 42) >>> 24) == 0
                && image.getRGB(23, 44) != image.getRGB(23, 43);
        var converted = BbModel.parse(YsmFolderModel.bundledDefault(runtime.options.defaultBlueTexture));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "Actual loaded mod JAR's OpenYSM manifest, original geometry/animation/texture; not preview diagnostic labels");
        evidence.put("geometrySha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometryBytes)));
        evidence.put("animationSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(animationBytes)));
        evidence.put("textureSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(textureBytes)));
        evidence.put("previewAnimation", manifest.getAsJsonObject("properties").get("preview_animation").getAsString());
        JsonObject properties = manifest.getAsJsonObject("properties");
        evidence.put("disablePreviewRotation", properties.get("disable_preview_rotation").getAsBoolean());
        evidence.put("guiNoLighting", properties.get("gui_no_lighting").getAsBoolean());
        Map<String, Object> decorations = new LinkedHashMap<>();
        for (String role : List.of("background", "foreground")) {
            String path = properties.get("gui_" + role).getAsString(); byte[] png = builtinResource(path);
            var decoration = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            decorations.put(role, Map.of("path", path, "width", decoration.getWidth(), "height", decoration.getHeight(),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))));
        }
        evidence.put("decorations", Map.copyOf(decorations));
        evidence.put("authoredEnterClipPresent", animations.has("enter")); evidence.put("eyeBones", eyeBones);
        evidence.put("whiteEyeFaces", whiteEyeFaces); evidence.put("textureWidth", geometry.getAsJsonObject("description").get("texture_width").getAsInt());
        evidence.put("textureHeight", geometry.getAsJsonObject("description").get("texture_height").getAsInt());
        evidence.put("whitePixelArgb", actualEyePixels); evidence.put("transparentRightNeighbour", image == null ? -1 : image.getRGB(25, 42) >>> 24);
        evidence.put("lowerNeighbourArgb", image == null ? "missing" : Integer.toHexString(image.getRGB(23, 44)));
        evidence.put("naturalBlinkOpensAndCloses", sourceBlink); evidence.put("convertedAnimations", converted.animations());
        builtinPreviewAsset = evidence;
        check("standaloneBuiltinPreviewSourceEvidence", "gui".equals(evidence.get("previewAnimation")) && !animations.has("enter")
                        && converted.animations().containsAll(List.of("gui", "idle", "pre_parallel1")) && sourceBlink
                        && eyeBones.size() == 6 && whiteEyeFaces.size() == 2 && whitePixels && sensitiveEdges,
                "Real source has gui + idle and a natural 4-second blink; OWNER native layers and CARD cap use independent source clocks. Eye white UV must not sample neighbouring transparency/skin: " + evidence);
    }

    private static double diagnosticNumber(Map<?, ?> row, String key) {
        return row.get(key) instanceof Number number ? number.doubleValue() : Double.NaN;
    }
    private static List<Map<?, ?>> defaultPreviewDraws(Map<String, Object> diagnostics) {
        if (!(diagnostics.get("draws") instanceof List<?> draws)) return List.of();
        return draws.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value)
                .filter(draw -> String.valueOf(draw.get("key")).startsWith(PROFILE.modelId() + ":")).toList();
    }
    private static double viewportArea(Map<?, ?> draw) {
        return diagnosticNumber(draw, "width") * diagnosticNumber(draw, "height");
    }
    private static Map<?, ?> mainPreviewDraw(List<Map<?, ?>> draws) {
        return draws.stream().filter(draw->"OWNER".equals(draw.get("context"))).findFirst()
                .orElseGet(()->draws.stream().max(java.util.Comparator.comparingDouble(LocalAppearanceHarness::viewportArea)).orElse(Map.of()));
    }
    private static List<Double> diagnosticVector(Object value, int size) {
        if (!(value instanceof List<?> vector) || vector.size() != size || vector.stream().anyMatch(item -> !(item instanceof Number))) return List.of();
        return vector.stream().map(item -> ((Number) item).doubleValue()).toList();
    }
    private Map<String, Object> whiteEyeProof(Map<?, ?> draw) {
        int visible = 0; double maximumUvError = 0;
        List<Double> centers = new ArrayList<>(), areas = new ArrayList<>();
        boolean mappingValid = true;
        if (draw.get("faces") instanceof List<?> faces) for (Object value : faces) {
            if (!(value instanceof Map<?, ?> face) || !(face.get("sourceUv") instanceof List<?> sourceUvs)
                    || !(face.get("atlasUv") instanceof List<?> atlasUvs) || !(face.get("points") instanceof List<?> points)
                    || sourceUvs.size() != 4 || atlasUvs.size() != 4 || points.size() != 4) { mappingValid = false; continue; }
            List<Double> atlas = diagnosticVector(face.get("atlas"), 6);
            if (atlas.isEmpty() || atlas.get(2) != 128 || atlas.get(3) != 128 || atlas.get(4) <= 0 || atlas.get(5) <= 0) {
                mappingValid = false; continue;
            }
            double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
            double maxU = Double.NEGATIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
            List<List<Double>> projected = new ArrayList<>();
            for (int corner = 0; corner < 4; corner++) {
                List<Double> source = diagnosticVector(sourceUvs.get(corner), 2), mapped = diagnosticVector(atlasUvs.get(corner), 2);
                List<Double> point = diagnosticVector(points.get(corner), 2);
                if (source.isEmpty() || mapped.isEmpty() || point.isEmpty()) { mappingValid = false; continue; }
                double pixelU = source.get(0) * atlas.get(2), pixelV = source.get(1) * atlas.get(3);
                minU = Math.min(minU, pixelU); maxU = Math.max(maxU, pixelU);
                minV = Math.min(minV, pixelV); maxV = Math.max(maxV, pixelV);
                double errorU = Math.abs(mapped.get(0) * atlas.get(4) - atlas.get(0) - pixelU);
                double errorV = Math.abs(mapped.get(1) * atlas.get(5) - atlas.get(1) - pixelV);
                maximumUvError = Math.max(maximumUvError, Math.max(errorU, errorV));
                mappingValid &= Double.isFinite(errorU) && Double.isFinite(errorV) && errorU < .001 && errorV < .001;
                projected.add(point);
            }
            // The authored two front white-eye faces both occupy exactly this source rectangle.
            // Comparing numeric consumer atlas UVs catches the old half-pixel/width-1 shrink.
            boolean authoredWhite = Math.abs(minU - 23) < .001 && Math.abs(maxU - 25) < .001
                    && Math.abs(minV - 42) < .001 && Math.abs(maxV - 44) < .001;
            if (!authoredWhite || projected.size() != 4) continue;
            double twiceArea = 0;
            for (int corner = 0; corner < 4; corner++) {
                var a = projected.get(corner); var b = projected.get((corner + 1) % 4);
                twiceArea += a.get(0) * b.get(1) - b.get(0) * a.get(1);
            }
            double area = Math.abs(twiceArea) * .5;
            if (Double.isFinite(area) && area > .001) {
                visible++; areas.add(area); centers.add(projected.stream().mapToDouble(point -> point.get(0)).average().orElse(0));
            }
        }
        boolean bothEyes = visible == 2 && centers.size() == 2 && Math.abs(centers.get(0) - centers.get(1)) > .1;
        return Map.of("numericAtlasMappingValid", mappingValid, "maximumSourcePixelError", maximumUvError,
                "visibleWhiteEyeFrontQuads", visible, "bothEyesSeparated", bothEyes, "projectedAreas", areas, "centersX", centers);
    }
    private Map<String, Object> authorPreviewProof(Map<?, ?> draw) {
        boolean owner="OWNER".equals(draw.get("context"));
        Map<String,Object> source;
        try { source=guiPreviewSource(PROFILE.modelId()); }
        catch(Exception failure) { return Map.of("fixedAuthorCamera",false,"sourceFlagsMatch",false,"actualBackgroundModelForegroundOrder",false,"error",failure.toString()); }
        var player=MinecraftClient.getInstance().player;
        boolean fixedCamera=owner?player!=null && ownerNativeContext(draw,String.valueOf(source.get("animation")),player.getHeight(),player.getScale())
                :cardNativeContext(draw,source);
        boolean flags = Boolean.FALSE.equals(builtinPreviewAsset.get("guiNoLighting")) && Boolean.FALSE.equals(draw.get("noLighting"));
        boolean layers = diagnosticNumber(draw, "entryProgress") >= .999 && diagnosticNumber(draw, "decorationQuads") == (owner?0:2)
                && draw.get("decorations") instanceof List<?> rows && rows.size() == (owner?0:2);
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (draw.get("decorations") instanceof List<?> rows) for (Object value : rows) {
            if (!(value instanceof Map<?, ?> decoration)) { layers = false; continue; }
            String role = String.valueOf(decoration.get("role"));
            Map<?, ?> decorationSource = ((Map<?, ?>) builtinPreviewAsset.getOrDefault("decorations", Map.of())).get(role) instanceof Map<?, ?> found ? found : Map.of();
            List<Double> atlas = diagnosticVector(decoration.get("atlas"), 6);
            boolean valid = decorationSource.get("path") != null && decorationSource.get("path").equals(draw.get(role))
                    && atlas.size() == 6 && atlas.get(2) == ((Number) decorationSource.get("width")).doubleValue()
                    && atlas.get(3) == ((Number) decorationSource.get("height")).doubleValue() && atlas.get(2) == 52 && atlas.get(3) == 90
                    && diagnosticNumber(decoration, "color") == -1
                    && diagnosticNumber(decoration, "order") == (role.equals("background") ? 0 : diagnosticNumber(draw, "quads") - 1);
            List<List<Double>> corners = List.of(List.of(0d, 0d), List.of(0d, 1d), List.of(1d, 1d), List.of(1d, 0d));
            double error = 0;
            if (decoration.get("atlasUv") instanceof List<?> uvs && uvs.size() == 4
                    && decoration.get("points") instanceof List<?> points && points.size() == 4 && atlas.size() == 6) {
                for (int i = 0; i < 4; i++) {
                    List<Double> uv = diagnosticVector(uvs.get(i), 2), point = diagnosticVector(points.get(i), 2);
                    if (uv.isEmpty() || point.isEmpty()) { valid = false; continue; }
                    error = Math.max(error, Math.abs(uv.get(0) * atlas.get(4) - atlas.get(0) - corners.get(i).get(0) * atlas.get(2)));
                    error = Math.max(error, Math.abs(uv.get(1) * atlas.get(5) - atlas.get(1) - corners.get(i).get(1) * atlas.get(3)));
                    valid &= Math.abs(point.get(0) - diagnosticNumber(draw, "x") - corners.get(i).get(0) * diagnosticNumber(draw, "width")) < .001
                            && Math.abs(point.get(1) - diagnosticNumber(draw, "y") - corners.get(i).get(1) * diagnosticNumber(draw, "height")) < .001;
                }
                valid &= error < .001;
            } else valid = false;
            layers &= valid; evidence.add(Map.of("role", role, "valid", valid, "sourcePixelError", error));
        }
        boolean shaded = draw.get("faces") instanceof List<?> faces && faces.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .anyMatch(face -> face.get("color") instanceof Number color && (color.intValue() >>> 24) == 255 && (color.intValue() >> 16 & 255) < 255);
        return Map.of("fixedAuthorCamera", fixedCamera, "sourceFlagsMatch", flags && shaded, "actualBackgroundModelForegroundOrder", layers,
                "context",String.valueOf(draw.get("context")),"decorations", evidence, "actualModelSoftShading", shaded);
    }
    private boolean dragPreview(Screen screen, Map<?, ?> draw) {
        double x = diagnosticNumber(draw, "x") + diagnosticNumber(draw, "width") * .5;
        double y = diagnosticNumber(draw, "y") + diagnosticNumber(draw, "height") * .5;
        Click click = new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        boolean clicked = screen.mouseClicked(click, false);
        screen.mouseDragged(click, 40, 25); screen.mouseReleased(click);
        return clicked;
    }
    private boolean currentPreviewEyes(Map<String, Object> diagnostics) {
        List<Map<?, ?>> draws = defaultPreviewDraws(diagnostics);
        if (draws.size() < 2 || !previewRendered(diagnostics)) return false;
        Map<?, ?> main = mainPreviewDraw(draws);
        return diagnosticNumber(main, "entryProgress") >= .999 && Boolean.TRUE.equals(whiteEyeProof(main).get("bothEyesSeparated"))
                && Boolean.TRUE.equals(whiteEyeProof(main).get("numericAtlasMappingValid"))
                && draws.stream().filter(draw -> draw != main).anyMatch(draw -> Boolean.TRUE.equals(whiteEyeProof(draw).get("bothEyesSeparated"))
                        && Boolean.TRUE.equals(whiteEyeProof(draw).get("numericAtlasMappingValid")));
    }
    private boolean singlePreviewEyes(Map<String, Object> diagnostics) {
        Map<?, ?> main = mainPreviewDraw(defaultPreviewDraws(diagnostics));
        Map<String, Object> eyes = whiteEyeProof(main);
        return previewRendered(diagnostics) && diagnosticNumber(main, "entryProgress") >= .999
                && Boolean.TRUE.equals(eyes.get("bothEyesSeparated")) && Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid"));
    }
    private void previewRegressionTick(MinecraftClient client) {
        if (!(client.currentScreen instanceof LocalAppearanceScreen gallery)) return;
        Map<String, Object> diagnostics = gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128);
        List<Map<?, ?>> draws = defaultPreviewDraws(diagnostics);
        if (draws.isEmpty() || !previewRendered(diagnostics)) return;
        Map<?, ?> main = mainPreviewDraw(draws);
        double age = diagnosticNumber(main, "sampleTick"), start = diagnosticNumber(main, "startedAtTick");
        double progress = diagnosticNumber(main, "entryProgress");
        if (Double.isNaN(previewFirstStart)) previewFirstStart = start;
        previewClockValid &= Double.isFinite(age) && Double.isFinite(start) && age >= 0 && age + .001 >= previewPreviousAge
                && Math.abs(start - previewFirstStart) < .001 && diagnosticNumber(main, "startCount") == 1;
        previewPreviousAge = age; previewMaximumAge = Math.max(previewMaximumAge, age); previewMainSamples++;
        previewEntrySeen |= progress > 0 && progress < 1;
        previewEntryCompleted |= progress >= .999 && age >= 12;
        if (main.get("faces") instanceof List<?> faces) for (Object value : faces) if (value instanceof Map<?, ?> face && face.get("color") instanceof Number color) {
            double bounded = Math.clamp(progress, 0d, 1d);
            int expectedAlpha = Boolean.TRUE.equals(main.get("nativeCamera"))?255:(int) Math.round(255 * bounded * bounded * (3 - 2 * bounded));
            previewEntryAlphaValid &= Math.abs((color.intValue() >>> 24) - expectedAlpha) <= 1;
        }
        previewLayersValid &= main.get("layers") instanceof List<?> layers && !layers.contains("gui")
                && main.get("controllerSlots") instanceof List<?> slots && !slots.isEmpty() && !slots.contains("player.cap");
        Map<String, Object> mainEyes = whiteEyeProof(main);
        previewAtlasValid &= Boolean.TRUE.equals(mainEyes.get("numericAtlasMappingValid"));
        boolean mature = progress >= .999 && age >= 12;
        if (!galleryDragAttempted && mature && age >= 18) {
            galleryDragAttempted = dragPreview(gallery, main);
            uiEvent("drag-author-fixed-preview", Map.of("clicked", galleryDragAttempted, "requestedDx", 40, "requestedDy", 25));
        }
        if (mature) {
            previewMatureSamples++;
            previewMainEyesSeen |= Boolean.TRUE.equals(mainEyes.get("bothEyesSeparated"));
        }
        if (previewSameSelectionRequested) previewAfterSameSelectionSamples++;
        if (!previewSameSelectionRequested && mature && age >= 24) {
            String label = runtime.localModels().stream().filter(model -> model.id().equals(PROFILE.modelId())).map(ClientRuntime.Action::label).findFirst().orElse("");
            boolean pressed = pressButton(gallery, label);
            check("standaloneGallerySameSelectionCallback", pressed && gallery.selectedModelId().equals(PROFILE.modelId()) && PROFILE.equals(runtime.localAppearance()),
                    "Click the real already-selected default model card once; this must preserve its preview clock and the private world profile");
            uiEvent("select-same-default-card", Map.of("sampleTickBefore", age, "startedAtTickBefore", start));
            previewSameSelectionRequested = true;
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<?, ?> card : draws) {
            if (card == main) continue;
            previewCardSamples++;
            double cardAge=diagnosticNumber(card,"sampleTick"),cardStart=diagnosticNumber(card,"startedAtTick");
            if(Double.isNaN(previewCardFirstStart))previewCardFirstStart=cardStart;
            previewSharedClockValid &= "CARD".equals(card.get("context")) && Double.isFinite(cardAge) && cardAge+.001>=previewCardPreviousAge
                    && Math.abs(cardStart-previewCardFirstStart)<.001 && diagnosticNumber(card,"startCount")==1;
            previewCardPreviousAge=cardAge;
            previewLayersValid &= List.of("gui").equals(card.get("layers")) && List.of("player.cap").equals(card.get("controllerSlots"));
            Map<String, Object> eyes = whiteEyeProof(card);
            previewAtlasValid &= Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid"));
            if (diagnosticNumber(card,"entryProgress")>=.999 && cardAge>=12) previewCardEyesSeen |= Boolean.TRUE.equals(eyes.get("bothEyesSeparated"));
            cards.add(Map.of("draw", card, "eyeEvidence", eyes));
        }
        if (previewRegressionSamples.size() < 100) previewRegressionSamples.add(Map.of("stageTick", stageTicks,
                "emittedVertices", diagnostics.get("emittedVertices"), "main", main, "mainEyeEvidence", mainEyes, "cards", cards));
        if (!previewEntryScreenshot && progress >= .6 && progress <= .9) {
            saveScreenshot(client, "settings-ui-entry"); previewEntryScreenshot = true;
        }
        previewRegressionVerified = previewEntrySeen && previewEntryCompleted && previewClockValid && previewSharedClockValid
                && previewEntryAlphaValid && previewLayersValid && previewAtlasValid && previewMainEyesSeen && previewCardEyesSeen && previewEntryScreenshot
                && previewMainSamples >= 25 && previewCardSamples >= 15 && previewMatureSamples >= 12 && previewMaximumAge >= 24
                && previewSameSelectionRequested && previewAfterSameSelectionSamples >= 6;
    }

    private void saveScreenshot(MinecraftClient client, String name) {
        String filename = String.format("%02d-%s.png", stage + 1, name);
        screenshots.add("screenshots/" + filename);
        client.inGameHud.getChatHud().clear(false);
        ScreenshotRecorder.saveScreenshot(output.toFile(), filename, client.getFramebuffer(), 1, message -> {
            client.execute(()->completedScreenshots.add("screenshots/"+filename));LOG.info("Standalone screenshot {}", filename);
        });
    }
    private void uiEvent(String action, Object detail) {
        uiEvents.add(Map.of("stage", stages.get(stage), "stageTick", stageTicks, "action", action, "detail", detail));
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
        if (age == 16) settingsDragAttempted = dragPreview(screen, mainPreviewDraw(defaultPreviewDraws(screen.previewDiagnostics())));
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
    private void wheelUiTick(MinecraftClient client) throws Exception {
        if (client.currentScreen instanceof ModelConfigScreen config) { wheelConfigTick(client, config); return; }
        if (!(client.currentScreen instanceof AnimationWheelScreen screen)) return;
        if (uiStartedAt < 0) {
            wheelAuthorIds = new ArrayList<>(runtime.localModelProfile().extraAnimations().keySet());
            int index = -1;
            for (int i = 0; i < wheelAuthorIds.size(); i++) if (wheelAuthorIds.get(i).equals("extra1")) { index = i; break; }
            if (index < 0) return;
            uiStartedAt = stageTicks; wheelActionId = wheelAuthorIds.get(index); wheelSlot = index % 8;
            boolean locked = pressButton(screen, "保持打开：关");
            boolean paged = true;
            for (int i = 0; i < index / 8; i++) paged &= pressButton(screen, "下一页");
            JsonObject source = JsonParser.parseString(new String(builtinResource("ysm.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> sourceIds = new ArrayList<>(source.getAsJsonObject("properties").getAsJsonObject("extra_animation").keySet());
            check("standaloneWheelPageAndLock", locked && paged && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && wheelAuthorIds.equals(sourceIds)
                            && ((List<?>) screen.diagnostics().get("visibleActionIds")).equals(sourceIds.subList(index / 8 * 8, Math.min(sourceIds.size(), index / 8 * 8 + 8)))
                            && ((List<?>) screen.diagnostics().get("visibleActionIds")).get(wheelSlot).equals(wheelActionId),
                    "Actual keep-open callback and authored extra-animation order from original JAR manifest; default has eight entries and one page, not the generic sorted movement inventory; wheel=" + screen.diagnostics());
        }
        int age = stageTicks - uiStartedAt;
        if (age >= 0 && age <= 30) configBefore.add(worldVertices(client));
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
        if (age == 8) {
            check("standaloneWheelActionLockIndependent", pressButton(screen, wheelOriginalActionLock ? "动作锁定：开" : "动作锁定：关")
                            && runtime.localActionLocked() != wheelOriginalActionLock && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && PROFILE.equals(runtime.localAppearance()) && runtime.requestPacketsSent() == requestBaseline,
                    "Action-lock product callback changes the separate native-motion cancellation preference while the menu keep-open flag stays on");
        }
        if (age == 9) check("standaloneWheelActionLockRestored", pressButton(screen, wheelOriginalActionLock ? "动作锁定：关" : "动作锁定：开")
                        && runtime.localActionLocked() == wheelOriginalActionLock && Boolean.TRUE.equals(screen.diagnostics().get("locked")),
                "Independent action-lock preference restored through its actual button");
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
        }
        if (age == 34) {
            int configSlot = wheelAuthorIds.indexOf("extra0");
            var diagnostics = screen.diagnostics();
            double radius = ((Number) diagnostics.get("innerRadius")).doubleValue()
                    + (((Number) diagnostics.get("outerRadius")).doubleValue() - ((Number) diagnostics.get("innerRadius")).doubleValue()) * .24;
            double angle = -Math.PI / 2 + configSlot * Math.PI * 2 / 8;
            double x = ((Number) diagnostics.get("centerX")).doubleValue() + Math.cos(angle) * radius;
            double y = ((Number) diagnostics.get("centerY")).doubleValue() + Math.sin(angle) * radius;
            screen.mouseMoved(x, y);
            boolean clicked = screen.mouseClicked(new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
            check("standaloneWheelAuthorConfigEntry", configSlot >= 0 && "#extra_config".equals(runtime.localModelProfile().extraAnimations().get("extra0"))
                            && clicked && client.currentScreen instanceof ModelConfigScreen,
                    "Actual inner-ring click follows source extra0 → #extra_config; it opens author configuration rather than plays a guessed animation");
        }
        if (wheelConfigVerified) wheelVerified = true;
    }
    private int worldVertices(MinecraftClient client) {
        return ((List<?>) ModelRenderer.diagnostics().get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).filter(model -> model.owner().equals(client.player.getUuid().toString()))
                .mapToInt(ModelRenderer.FrameModel::vertices).sum();
    }
    private void wheelConfigTick(MinecraftClient client, ModelConfigScreen screen) {
        var diagnostics = screen.diagnostics();
        if (!(diagnostics.get("forms") instanceof List<?> forms) || forms.size() != 1) return;
        Map<?, ?> form = (Map<?, ?>) forms.getFirst();
        if (configStartedAt < 0) {
            configStartedAt = stageTicks; configOriginalValue = ((Number) form.get("value")).doubleValue();
            var sourceForm = runtime.localModelProfile().extraAnimationButtons().get(0).getAsJsonObject().getAsJsonArray("config_forms").get(0).getAsJsonObject();
            check("standaloneAuthorConfigSource", diagnostics.get("group").equals("extra_config") && form.get("key").equals("extra_config:0")
                            && form.get("kind").equals("CHECKBOX") && sourceForm.get("type").getAsString().equals("checkbox")
                            && sourceForm.get("value").getAsString().equals("v.roaming.red_bow_headdress"),
                    "Real configuration widget maps to the original YSM checkbox expression; screen=" + diagnostics + "; source=" + sourceForm);
            uiEvent("author-config-open", diagnostics); saveScreenshot(client, "wheel-author-config-ui");
        }
        int age = stageTicks - configStartedAt;
        if (age == 1) check("standaloneAuthorConfigDraftPrivate", pressButton(screen, configOriginalValue > 0 ? "✓ 开启" : "× 关闭")
                        && runtime.localModelVariables(PROFILE.modelId()).get("variable.roaming.red_bow_headdress") == configOriginalValue
                        && ((Number) screen.diagnostics().get("pendingScripts")).intValue() == 1 && PROFILE.equals(runtime.localAppearance()),
                "Actual checkbox callback stages a script without mutating the active model before Apply");
        if (age == 2) check("standaloneAuthorConfigApplyPrivate", pressButton(screen, "应用并保存")
                        && runtime.localModelVariables(PROFILE.modelId()).get("variable.roaming.red_bow_headdress") == (configOriginalValue > 0 ? 0d : 1d)
                        && runtime.requestPacketsSent() == requestBaseline && PROFILE.equals(runtime.localAppearance()),
                "Actual Apply button executes and saves the source checkbox script; no C2S request");
        if (age >= 3 && age <= 15) configChanged.add(worldVertices(client));
        if (age == 15) check("standaloneAuthorConfigChangesWorldGeometry", maximum(configBefore) > 0 && maximum(configChanged) > 0
                        && (configOriginalValue > 0 ? maximum(configChanged) < maximum(configBefore) : maximum(configChanged) > maximum(configBefore)),
                "Real native world mesh changes with author headwear variable; before=" + configBefore + "; changed=" + configChanged);
        if (age == 16) pressButton(screen, configOriginalValue > 0 ? "× 关闭" : "✓ 开启");
        if (age == 17) check("standaloneAuthorConfigRestoreCallback", pressButton(screen, "应用并保存")
                        && runtime.localModelVariables(PROFILE.modelId()).get("variable.roaming.red_bow_headdress") == configOriginalValue,
                "Actual author configuration callback restores original value");
        if (age >= 18 && age <= 31) configRestored.add(worldVertices(client));
        if (age == 32) {
            check("standaloneAuthorConfigRestoredGeometry", maximum(configBefore) > 0 && maximum(configRestored) == maximum(configBefore)
                            && PROFILE.equals(runtime.localAppearance()) && runtime.requestPacketsSent() == requestBaseline,
                    "Real restored geometry and private profile; original=" + configBefore + "; restored=" + configRestored);
            uiEvent("author-config-restored", diagnostics); screen.close();
            check("standaloneAuthorConfigCloseCallback", client.currentScreen instanceof AnimationWheelScreen,
                    "Product configuration Close returns to the existing keep-open wheel");
            wheelConfigVerified = true;
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
        if (!(client.currentScreen instanceof PlayerModelScreen screen)) return;
        if (!uiActionPressed && stageTicks >= 5) {
            String sourceLabel = runtime.localModelProfile().extraAnimations().get("extra1");
            String label = sourceLabel == null || sourceLabel.isEmpty() ? "extra1" : runtime.localModelProfile().localized(client.options.language, sourceLabel, sourceLabel);
            var button = screen.children().stream().filter(ButtonWidget.class::isInstance).map(ButtonWidget.class::cast)
                    .filter(widget -> widget.getY() == 100 && widget.active && widget.getMessage().getString().equals(label)).findFirst().orElse(null);
            if (button != null && runtime.localModelProfile().extraAnimations().containsKey("extra1")) {
                uiActionId = "extra1"; uiActionPressed = true;
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
            if (name.equals("settings-ui") || name.equals("ui-reload-ui")) {
                Map<String, Object> eyeDiagnostics = gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128);
                observation.put("eyeAtlasDraws", eyeDiagnostics);
                check(name.equals("settings-ui") ? "standaloneGalleryMainAndThumbnailEyes" : "standaloneGalleryEyesAfterReload",
                        currentPreviewEyes(eyeDiagnostics),
                        "Actual emitted main and thumbnail quads have two separated, positive-area front white-eye faces; consumer atlas UVs preserve original 23..25/42..44 source pixel boundaries. Natural source blink may omit eyes briefly; screenshot selects an open-eye mature frame: " + eyeDiagnostics);
                List<Map<String, Object>> authorProofs = defaultPreviewDraws(eyeDiagnostics).stream().map(this::authorPreviewProof).toList();
                observation.put("authorPreviewProofs", authorProofs);
                check(name.equals("settings-ui") ? "standaloneGalleryAuthorGuiLayers" : "standaloneGalleryAuthorGuiLayersAfterReload",
                        authorProofs.size() >= 2 && authorProofs.stream().allMatch(proof -> Boolean.TRUE.equals(proof.get("actualBackgroundModelForegroundOrder"))
                                && Boolean.TRUE.equals(proof.get("sourceFlagsMatch")) && Boolean.TRUE.equals(proof.get("fixedAuthorCamera"))),
                        "Actual OWNER uses native inventory animation/camera without decorations; CARD uses author cap/camera and original 52x90 background/model/foreground UV order: " + authorProofs);
            }
            if (name.equals("settings-ui")) {
                check("standaloneGalleryAuthorRotationLocked", galleryDragAttempted
                                && defaultPreviewDraws(gallery.previewDiagnostics()).stream().allMatch(draw -> Boolean.TRUE.equals(authorPreviewProof(draw).get("fixedAuthorCamera"))),
                        "Actual owner mouse drag is allowed by the native inventory camera; source disable_preview_rotation applies only to CARD, which remains at 0/0");
                observation.put("previewRegressionSamples", List.copyOf(previewRegressionSamples));
                check("standaloneGalleryFirstEntryProgressesOnce", previewEntrySeen && previewEntryCompleted && previewEntryAlphaValid && previewEntryScreenshot,
                        "Independent preview age advances through 12 ticks once; source-native geometry stays opaque without extra fade/scale. The early-clock screenshot is retained.");
                check("standaloneGalleryPreviewClockStable", previewClockValid && previewSharedClockValid && previewLayersValid
                                && previewSameSelectionRequested && previewAfterSameSelectionSamples >= 6 && previewMaximumAge >= 24,
                        "Default OWNER and CARD maintain independent stable startedAtTick/startCount=1 through ordinary frames and a real same-card click; owner age="
                                + previewMaximumAge + "; main/card samples=" + previewMainSamples + "/" + previewCardSamples
                                + "; after repeated selection=" + previewAfterSameSelectionSamples + "; native OWNER layers and CARD player.cap GUI are actually sampled");
                check("standaloneGalleryEyeAtlasSourceBounds", previewAtlasValid && previewMainEyesSeen && previewCardEyesSeen && previewMatureSamples >= 12,
                        "Independent original geometry/PNG pixel evidence versus numeric source and actual consumer atlas UVs (within .001 source pixel); both eye fronts observed in large preview and thumbnail across mature samples=" + previewMatureSamples);
            }
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
            Map<String, Object> eyeDiagnostics = settings.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128);
            Map<?, ?> main = mainPreviewDraw(defaultPreviewDraws(eyeDiagnostics));
            Map<String, Object> eyes = whiteEyeProof(main); observation.put("eyeAtlasDraws", eyeDiagnostics);
            check("standaloneModelSettingsEyes", previewRendered(eyeDiagnostics) && diagnosticNumber(main, "entryProgress") >= .999
                            && Boolean.TRUE.equals(eyes.get("bothEyesSeparated")) && Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid")),
                    "Settings page shares the actual source-pixel-correct preview renderer; two default white-eye front quads are submitted with positive projected area: " + eyes);
            Map<String, Object> author = authorPreviewProof(main); observation.put("authorPreviewProof", author);
            check("standaloneModelSettingsAuthorGuiLayers", Boolean.TRUE.equals(author.get("actualBackgroundModelForegroundOrder"))
                            && Boolean.TRUE.equals(author.get("sourceFlagsMatch")) && Boolean.TRUE.equals(author.get("fixedAuthorCamera")),
                    "Actual settings OWNER native inventory projection has normal animation and no card-only GUI decorations: " + author);
            check("standaloneModelSettingsAuthorRotationLocked", settingsDragAttempted && Boolean.TRUE.equals(author.get("fixedAuthorCamera")),
                    "Real settings OWNER drag callback retains the native inventory camera; card-only author rotation policy is not applied to the owner");
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
        } else if (screen instanceof PlayerModelScreen) {
            check("standaloneActionsUiScreen", screen instanceof PlayerModelScreen, screen == null ? "No screen" : screen.getClass().getName());
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
            case "first-person" -> {
                check("standaloneFirstPersonNativeArm", binding != null && binding.instance().startsWith("local-self:")
                            && client.options.getPerspective().isFirstPerson() && ((Number) renderer.get("firstPersonSelfSkipped")).intValue() > 0
                            && !runtime.shouldHideFirstPersonArm() && runtime.requestPacketsSent() == requestBaseline,
                    "Actual first-person extraction skips private body while native held-item/use/map rendering remains allowed; custom YSM arms replace only prepared arm commands; renderer=" + renderer);
                Map<?, ?> components = (Map<?, ?>) renderer.get("components");
                boolean actualArmDraw = ((List<?>) components.get("submittedArmDraws")).stream().filter(Map.class::isInstance).map(Map.class::cast)
                        .anyMatch(draw -> client.player.getUuid().toString().equals(draw.get("owner")) && binding != null
                                && binding.instance().equals(draw.get("instance")) && "fp_arm".equals(draw.get("kind"))
                                && ((Number) draw.get("emittedVertices")).longValue() > 0);
                check("standaloneFirstPersonYsmArmMesh", ((Number) components.get("submittedArms")).longValue() > firstPersonArmsBaseline
                                && ((Number) components.get("emittedArmVertices")).longValue() > firstPersonArmVerticesBaseline && actualArmDraw
                                && ((List<?>) components.get("arms")).size() == 2 && runtime.requestPacketsSent() == requestBaseline,
                        "Actual author fp_arm geometry writes vertices through a matching private-owner arm command; submitted baseline="
                                + firstPersonArmsBaseline + "; vertex baseline=" + firstPersonArmVerticesBaseline + "; components=" + components);
            }
            case "model-effects" -> {
                mergeEffectsChecks();
                boolean screenshotSaved = effectsScreenshot && Files.isRegularFile(output.resolve(effectsScreenshotPath));
                check("standaloneYsmEffectsActiveScreenshot", screenshotSaved,
                        "Real framebuffer captured while actual custom SoundManager playback and live native particles were active; path=" + effectsScreenshotPath);
                Map<String, Object> proof = new LinkedHashMap<>(effects.diagnostics());
                proof.put("passed", effects.passed() && screenshotSaved); proof.put("checks", effects.checks());
                proof.put("activeScreenshot", effectsScreenshotPath); effectsReport = Map.copyOf(proof);
                observation.put("modelEffects", effectsReport);
                boolean cleaned = !client.player.hasVehicle();
                for (var entity : client.world.getEntities()) cleaned &= !componentFixtureUuids.contains(entity.getUuid());
                check("standaloneComponentFixtureCleanup", cleaned, "Only acceptance's unique-tag boat/arrow entities removed; exact native UUIDs=" + componentFixtureUuids);
            }
            case "vehicle-component", "projectile-component" -> {
                String prefix = name.equals("vehicle-component") ? "standaloneVehicleComponent" : "standaloneProjectileComponent";
                observation.put("componentProof", componentProof);
                check(prefix + "ActualOwnerAndBinding", Boolean.TRUE.equals(componentProof.get("currentBindingAndAuthorAsset")),
                        "Real summoned native UUID/rider or projectile owner is the private default profile owner; current binding and original author component agree: " + componentProof);
                check(prefix + "ActualDraw", Boolean.TRUE.equals(componentProof.get("actualVerticesEmitted")),
                        "Matching native entity component writes positive VertexConsumer vertices and replaces exactly that native render state: " + componentProof);
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
        saveScreenshot(client, name);
    }

    private void check(String name, boolean passed, String detail) {
        checks.add(Map.of("name", name, "passed", passed, "detail", detail)); LOG.info("Standalone check {}={} {}", name, passed, detail);
    }
    private void finish(MinecraftClient client) {
        if (finished) return;
        finished = true;
        try {
            if (effects != null) { effects.close(); mergeEffectsChecks(); }
            if (!componentFixtureUuids.isEmpty()) cleanupComponentFixtures(client);
            if (interactions) cleanupInteractions(client);
            check("standaloneAllStagesCaptured", interactions ? interactionCompleted && !observations.isEmpty() : observations.size() == stages.size(), "Captured stages=" + observations.stream().map(row -> row.get("stage")).toList());
            check("standaloneBridgeStayedDisconnected", !runtime.serverBridgeConnected(), runtime.status().toString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", "standalone-no-server-plugins"); result.put("passed", checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
            result.put("minecraft", "1.21.11"); result.put("clientVersion", FabricLoader.getInstance().getModContainer("meplayeractions").orElseThrow().getMetadata().getVersion().getFriendlyString()); result.put("runtimeServerConnected", runtime.serverBridgeConnected());
            result.put("serverChannelAvailable", client.getNetworkHandler() != null && ClientPlayNetworking.canSend(ActionPayload.ID));
            result.put("testedClientSha256", artifactProof.get("clientArtifactSha256")); result.put("artifactProof", artifactProof);
            result.put("startedAtMillis", startedMillis); result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
            result.put("savedProfilePath", runtime.localAppearanceSettingsPath().toString());
            result.put("galleryFixture", galleryFixture); result.put("uiEvents", uiEvents);
            result.put("validationProfile", validationProfile);
            if (interactions) result.put("interactionDelivery", interactionDelivery());
            else result.put("guiPreviewRegression", guiPreviewRegression());
            result.put("modelEffects", effectsReport);
            Files.createDirectories(output);
            Files.writeString(output.resolve("results.json"), GSON.toJson(result), StandardCharsets.UTF_8);
        } catch (Exception failure) { LOG.error("Cannot save standalone results", failure); }
        client.scheduleStop();
    }
    private void mergeEffectsChecks() {
        if (effects == null || effectsChecksMerged) return;
        checks.addAll(effects.checks().stream().filter(candidate -> checks.stream().noneMatch(existing ->
                existing.get("name").equals(candidate.get("name")))).toList());
        effectsChecksMerged = effects.complete();
    }
    private Map<String, Object> guiPreviewRegression() {
        List<String> required = List.of("standaloneBuiltinPreviewSourceEvidence", "standaloneGallerySameSelectionCallback",
                "standaloneGalleryFirstEntryProgressesOnce", "standaloneGalleryPreviewClockStable", "standaloneGalleryEyeAtlasSourceBounds",
                "standaloneGalleryMainAndThumbnailEyes", "standaloneModelSettingsEyes", "standaloneGalleryEyesAfterReload",
                "standaloneGalleryAuthorGuiLayers", "standaloneGalleryAuthorRotationLocked", "standaloneModelSettingsAuthorGuiLayers",
                "standaloneModelSettingsAuthorRotationLocked", "standaloneGalleryAuthorGuiLayersAfterReload");
        var relevant = checks.stream().filter(row -> required.contains(row.get("name"))).toList();
        boolean passed = required.stream().allMatch(name -> relevant.stream().anyMatch(row -> name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("passed"))))
                && relevant.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        return Map.of("passed", passed, "requiredChecks", required, "checks", relevant,
                "builtinAssetEvidence", builtinPreviewAsset, "frameSamples", previewRegressionSamples,
                "entryMeaning", "Independent native OWNER and Dummy CARD clocks; OWNER uses native motion and Inventory70, CARD stops main and routes authored GUI to cap/30, without added YSM fade/scale");
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
