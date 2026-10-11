package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.MEPlayerActionsClient;
import com.simmc.meplayeractions.client.WheelPreferences;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.network.ActionPayload;
import com.simmc.meplayeractions.client.ui.AnimationWheelScreen;
import com.simmc.meplayeractions.client.ui.LocalAppearanceScreen;
import com.simmc.meplayeractions.client.ui.ModelConfigScreen;
import com.simmc.meplayeractions.client.ui.ModelSettingsScreen;
import com.simmc.meplayeractions.client.ui.PlayerModelScreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

/** Opt-in, isolated no-server-plugin acceptance; never writes the main A/B phase or reports. */
public final class LocalAppearanceHarness
{
    private static final Logger LOG = LoggerFactory.getLogger("MEPlayerActions/StandaloneE2E");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> FULL_STAGES = List.of("native", "model", "transform", "first-person",
            "settings-ui", "gallery-browse-ui", "model-settings-ui", "wheel-ui", "local-actions-ui", "action", "stop",
            "vehicle-component", "projectile-component", "model-effects", "save-reload", "resource-reload",
            "ui-reload-ui", "disable");
    private static final LocalAppearanceSettings PROFILE = new LocalAppearanceSettings(true, "openysm_default", .65f,
            .25, .35, -.2);
    private final ClientRuntime runtime;
    private final String validationProfile = System.getProperty("meplayeractions.standalone.focus", "full");
    private final boolean interactions = this.validationProfile.equals("interactions");
    private final List<String> stages = this.interactions ? List.of("interaction-ui") : FULL_STAGES;
    private static final String INTERACTION_MODEL = "local:UI-Interaction-Memory.bbmodel";
    private static final List<String> INTERACTION_REQUIRED = List.of("standaloneInteractionJOnlyBinding",
            "standaloneInteractionJOpensWheel", "standaloneInteractionReferenceWheel",
            "standaloneInteractionSingleSettings", "standaloneInteractionSettingsOpensModelScreen",
            "standaloneInteractionSources", "standaloneInteractionReferenceModelScreen",
            "standaloneInteractionClientGallery", "standaloneInteractionClientModelSettings",
            "standaloneInteractionAdvancedPreferences", "standaloneInteractionPreferencePersistence",
            "standaloneInteractionInlineAuthorForms", "standaloneInteractionLocalActionNoServerRequest",
            "standaloneInteractionWheelMemory", "standaloneInteractionNoServerAuthority",
            "standaloneInteractionControlsInBounds", "standaloneInteractionCleanup",
            "standaloneWineFoxTaishoMaidGuiAndWorld", "standaloneWineFoxNewYearGuiAndWorld",
            "standaloneWineFoxAstronautGuiAndWorld");
    private static final List<String> WINE_FOX_IDS = List.of(BuiltinYsmModels.TAISHO_MAID_ID,
            BuiltinYsmModels.NEW_YEAR_ID, BuiltinYsmModels.ASTRONAUT_ID);
    private static final List<String> WINE_FOX_CHECKS = List.of("standaloneWineFoxTaishoMaidGuiAndWorld",
            "standaloneWineFoxNewYearGuiAndWorld", "standaloneWineFoxAstronautGuiAndWorld");
    private int wineFoxIndex, wineFoxStep, wineFoxWorldSamples, wineFoxContinueAt;
    private long wineFoxPreviewBaseline, wineFoxFrameBaseline, wineFoxDrawBaseline, wineFoxLastFrame = -1;
    private String wineFoxHash = "", wineFoxInstance = "", wineFoxHomeScreenshot = "", wineFoxGameScreenshot = "";
    private LocalAppearanceSettings wineFoxDraftProfile;
    private Map<String, Object> wineFoxSource = Map.of(), wineFoxPreview = Map.of(), wineFoxWorld = Map.of();
    private boolean wineFoxClassesCurrent;
    private final List<Map<String, Object>> wineFoxModels = new ArrayList<>(), wineFoxWorldFrames = new ArrayList<>();
    private static final Map<String, Map<String, Object>> GUI_PREVIEW_SOURCES = new LinkedHashMap<>();
    private int interactionStep, interactionStepAt;
    private boolean interactionCompleted, interactionControlsValid = true, interactionCardSelected,
            interactionHoverSelected;
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
    private String interactionHomeScreenshot = "";
    private Map<String, Object> interactionHomeEvidence = Map.of();

    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/standalone-e2e"))
            .toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>(), observations = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final Set<String> completedScreenshots = new HashSet<>();
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
    private final List<Integer> configBefore = new ArrayList<>(), configChanged = new ArrayList<>(),
            configRestored = new ArrayList<>();
    private List<String> galleryFirstPage = List.of();
    private final List<Map<String, Object>> uiEvents = new ArrayList<>();
    private final List<Integer> headdressBefore = new ArrayList<>(), headdressChanged = new ArrayList<>(),
            headdressRestored = new ArrayList<>();
    private Map<String, Object> galleryFixture = Map.of();
    private Map<String, Object> builtinPreviewAsset = Map.of();
    private final List<Map<String, Object>> previewRegressionSamples = new ArrayList<>();
    private boolean previewEntrySeen, previewEntryCompleted, previewClockValid = true, previewSharedClockValid = true;
    private boolean previewAtlasValid = true, previewLayersValid = true, previewMainEyesSeen, previewCardEyesSeen;
    private boolean previewEntryScreenshot, previewRegressionVerified, previewSameSelectionRequested,
            previewEntryAlphaValid = true;
    private int previewMainSamples, previewCardSamples, previewMatureSamples;
    private int previewAfterSameSelectionSamples;
    private double previewFirstStart = Double.NaN, previewPreviousAge = -1, previewMaximumAge;
    private double previewCardFirstStart = Double.NaN, previewCardPreviousAge = -1;
    private boolean galleryDragAttempted, settingsDragAttempted;
    private ModelEffectsHarness effects;
    private boolean effectsScreenshot, effectsChecksMerged;
    private String effectsScreenshotPath = "";
    private Map<String, Object> effectsReport = Map.of();
    private final String componentFixtureTag = "mpac_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<UUID> componentFixtureUuids = new ArrayList<>();
    private UUID componentEntityUuid;
    private long componentSubmissionsBaseline, componentVerticesBaseline;
    private Map<String, Object> componentProof = Map.of();
    private Vec3d componentOriginalPosition;

    private LocalAppearanceHarness(ClientRuntime runtime)
    {
        this.runtime = runtime;
    }

    public static void register(ClientRuntime runtime)
    {
        LocalAppearanceHarness harness = new LocalAppearanceHarness(runtime);
        ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
        LOG.info("Isolated standalone local appearance acceptance; outputs {}", harness.output);
    }

    private void tick(MinecraftClient client)
    {
        if (this.finished)
        {
            return;
        }

        this.ticks++;
        try
        {
            if (System.nanoTime() - this.startedNanos > 240_000_000_000L)
            {
                check("standaloneCompletion", false,
                        "240 second timeout; stage=" + (this.stage < 0 ? "join" : this.stages.get(this.stage)));
                finish(client);
                return;
            }
            if (!this.connecting)
            {
                if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null && this.ticks > 40)
                {
                    check("standaloneProductionJarLoaded", Boolean.TRUE.equals(this.artifactProof.get("clientFromJar")),
                            this.artifactProof.toString());
                    String address = System.getProperty("meplayeractions.e2e.server", "127.0.0.1:25592");
                    ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse(address),
                            new ServerInfo("MPA standalone empty-plugin Paper", address, ServerInfo.ServerType.OTHER),
                            false, null);
                    this.connecting = true;
                }
                return;
            }
            if (client.world == null || client.player == null || client.getNetworkHandler() == null)
            {
                return;
            }

            client.options.pauseOnLostFocus = false;
            boolean uiStage = this.stage >= 0 && this.stages.get(this.stage).endsWith("-ui");
            if (!uiStage && client.currentScreen != null)
            {
                client.setScreen(null);
            }

            this.clearWorldTicks = client.getOverlay() == null ? this.clearWorldTicks + 1 : 0;
            if (!this.configured)
            {
                if (this.clearWorldTicks < 15)
                {
                    return;
                }

                this.configured = true;
                if (this.interactions)
                {
                    this.interactionOriginalProfile = this.runtime.localAppearance();
                    this.interactionOriginalWheel = this.runtime.wheelPreferences();
                    this.interactionOriginalActionLock = this.runtime.localActionLocked();
                    this.interactionOriginalEnabled = this.runtime.options.enabled;
                    this.interactionOriginalShowSelf = this.runtime.options.showSelf;
                }
                if (!this.runtime.options.enabled)
                {
                    this.runtime.toggleEnabled();
                }

                this.runtime.options.showSelf = true;
                this.runtime.disableLocalAppearance();
                client.player.getAbilities().flying = false;
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                client.options.getGamma().setValue(1.0);
                client.getNetworkHandler().sendChatCommand("tp MPATest 0.5 -60 0.5 0 0");
                client.getNetworkHandler().sendChatCommand("time set day");
                this.requestBaseline = this.runtime.requestPacketsSent();
                check("standaloneNoServerChannel", !ClientPlayNetworking.canSend(ActionPayload.ID),
                        "Normal Minecraft multiplayer is connected; MPA payload channel is absent");
                check("standaloneNoServerBridge", !this.runtime.serverBridgeConnected(),
                        this.runtime.status().toString());
                next(client);
                return;
            }
            if (ClientPlayNetworking.canSend(ActionPayload.ID) || this.runtime.serverBridgeConnected())
            {
                check("standaloneNoServerBridge", false,
                        "Unexpected MPA server channel/handshake on standalone server");
                finish(client);
                return;
            }
            this.stageTicks++;
            String name = this.stages.get(this.stage);
            if (this.interactions)
            {
                interactionUiTick(client);
                return;
            }
            if (name.equals("save-reload"))
            {
                savedProfileTick();
            }

            if (name.equals("local-actions-ui"))
            {
                localActionUiTick(client);
            }

            if (name.equals("gallery-browse-ui"))
            {
                galleryUiTick(client);
            }

            if (name.equals("model-settings-ui"))
            {
                modelSettingsUiTick(client);
            }

            if (name.equals("wheel-ui"))
            {
                wheelUiTick(client);
            }

            if (name.equals("settings-ui"))
            {
                previewRegressionTick(client);
            }

            if (name.equals("vehicle-component") || name.equals("projectile-component"))
            {
                this.componentProof = componentProof(client, name);
            }

            if (name.equals("model-effects"))
            {
                this.effects.tick(client);
                if (!this.effectsScreenshot && Boolean.TRUE.equals(this.effects.diagnostics().get("effectsActive")))
                {
                    saveScreenshot(client, "model-effects-active");
                    this.effectsScreenshot = true;
                    this.effectsScreenshotPath = this.screenshots.getLast();
                }
            }
            ClientRuntime.RenderBinding binding = own(client);
            Map<String, Object> renderer = ModelRenderer.diagnostics();
            boolean ready;
            if (name.equals("native") || name.equals("disable"))
            {
                ready = binding == null && !this.runtime.shouldHidePlayer(client.player.getUuid())
                        && ((List<?>) renderer.get("models")).isEmpty();
            }

            else
            {
                ready = binding != null && binding.instance().startsWith("local-self:") && visible(renderer, binding);
            }

            if (name.equals("first-person"))
            {
                ready = binding != null && binding.instance().startsWith("local-self:")
                        && ((Number) renderer.get("firstPersonSelfSkipped")).intValue() > 0
                        && client.options.getPerspective().isFirstPerson();
            }

            if (name.equals("resource-reload"))
            {
                ready &= this.reload != null && this.reload.isDone() && !this.reload.isCompletedExceptionally()
                        && this.clearWorldTicks >= 10;
                if (ready && this.readyAt < 0)
                {
                    this.reloadFrameBaseline = ((Number) renderer.get("extractedFrames")).longValue();
                }

                if (this.readyAt >= 0)
                {
                    ready &= ((Number) renderer.get("extractedFrames")).longValue() >= this.reloadFrameBaseline + 2;
                }

            }
            if (name.equals("save-reload"))
            {
                ready &= this.savedDisabledReload && this.savedEnabledReload;
            }

            if (name.equals("model-effects"))
            {
                ready &= this.effects.complete() && PROFILE.equals(this.runtime.localAppearance()) && binding != null
                        && binding.assetHash().equals(this.modelHash);
            }

            if (name.equals("vehicle-component") || name.equals("projectile-component"))
            {
                ready &= Boolean.TRUE.equals(this.componentProof.get("passed"));
            }

            if (name.equals("settings-ui") || name.equals("gallery-browse-ui") || name.equals("ui-reload-ui"))

            {
                ready &= client.currentScreen instanceof LocalAppearanceScreen gallery
                        && previewRendered(gallery.previewDiagnostics())
                        && gallery.loadedPreviewCount() >= gallery.visibleModelIds().size();
            }

            if (name.equals("gallery-browse-ui"))
            {
                ready &= this.galleryVerified;
            }

            if (name.equals("settings-ui"))
            {
                ready &= this.previewRegressionVerified;
            }

            if (name.equals("settings-ui") || name.equals("ui-reload-ui"))
            {
                ready &= client.currentScreen instanceof LocalAppearanceScreen gallery
                        && currentPreviewEyes(gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128));
            }

            if (name.equals("model-settings-ui"))
            {
                ready &= client.currentScreen instanceof ModelSettingsScreen settings
                        && previewRendered(settings.previewDiagnostics()) && this.settingsVerified
                        && singlePreviewEyes(settings.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128));
            }

            if (name.equals("wheel-ui"))
            {
                ready &= client.currentScreen instanceof AnimationWheelScreen && this.wheelVerified;
            }

            if (name.equals("local-actions-ui"))
            {
                ready &= client.currentScreen instanceof PlayerModelScreen && this.uiActionVerified;
            }

            if (ready && this.readyAt < 0)
            {
                this.readyAt = this.stageTicks;
            }

            int delay = name.equals("action") ? 12 : 20;
            if (!this.captured && ready && this.readyAt >= 0 && this.stageTicks >= this.readyAt + delay)
            {
                capture(client, name, binding, renderer);
            }

            if (this.captured && this.stageTicks >= this.readyAt + (name.equals("action") ? 35 : 45))
            {
                next(client);
                return;
            }
            if (this.stageTicks > 240)
            {
                check(name + "StandaloneReady", false, "No stable local appearance state; "
                        + this.runtime.localAppearanceStatus() + "; renderer=" + renderer);
                if (!this.captured)
                {
                    capture(client, name, binding, renderer);
                }

                next(client);
            }
        }
        catch (Exception failure)
        {
            check("standaloneHarness", false, failure.toString());
            LOG.error("Standalone acceptance failed", failure);
            finish(client);
        }
    }

    private void next(MinecraftClient client) throws Exception
    {
        if (this.stage >= 0 && (this.stages.get(this.stage).equals("vehicle-component")
                || this.stages.get(this.stage).equals("projectile-component")))
        {
            cleanupComponentFixtures(client);
        }

        if (this.stage >= 0 && this.stages.get(this.stage).equals("model-effects") && this.effects != null)
        {
            this.effects.close();
            mergeEffectsChecks();
        }
        if (this.stage >= 0 && this.stages.get(this.stage).endsWith("-ui"))
        {
            Screen closing = client.currentScreen;
            if (closing != null)
            {
                closing.close();
            }

            check(this.stages.get(this.stage) + "StandaloneCloseCallback",
                    closing != null && client.currentScreen != closing
                            && PROFILE.equals(this.runtime.localAppearance()),
                    "Actual Screen.close callback; active appearance=" + this.runtime.localAppearance());
        }
        this.stage++;
        this.stageTicks = 0;
        this.readyAt = -1;
        this.captured = false;
        this.uiStartedAt = -1;
        if (this.stage >= this.stages.size())
        {
            finish(client);
            return;
        }
        String name = this.stages.get(this.stage);
        LOG.info("Standalone stage {}", name);
        client.setScreen(null);
        switch (name)
        {
        case "interaction-ui" ->
        {
            prepareInteractionFixture();
            this.runtime.updateLocalAppearance(PROFILE);
            this.runtime.updateWheelPreferences(this.runtime.wheelPreferences()
                    .withSource(WheelPreferences.Source.CLIENT).withPage(WheelPreferences.Source.CLIENT, 0));
            this.interactionStep = 0;
            this.interactionStepAt = 0;
            requestInteractionWheel(client);
        }
        case "model" ->
        {
            check("standaloneBundledModelsAvailable",
                    this.runtime.localModels().stream().anyMatch(model -> model.id().equals("openysm_default")),
                    this.runtime.localModels().toString());
            this.runtime.selectLocalModel("openysm_default");
        }
        case "transform" -> this.runtime.updateLocalAppearance(PROFILE);
        case "first-person" ->
        {
            this.firstPersonArmsBaseline = ((Number) ((Map<?, ?>) ModelRenderer.diagnostics().get("components"))
                    .get("submittedArms")).longValue();
            this.firstPersonArmVerticesBaseline = ((Number) ((Map<?, ?>) ModelRenderer.diagnostics().get("components"))
                    .get("emittedArmVertices")).longValue();
            client.options.setPerspective(Perspective.FIRST_PERSON);
        }
        case "settings-ui" ->
        {
            client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            readBuiltinPreviewEvidence();
            prepareGalleryFixtures();
            client.setScreen(new LocalAppearanceScreen(this.runtime));
        }
        case "gallery-browse-ui" ->
        {
            this.galleryVerified = false;
            ClientRuntime.RenderBinding binding = own(client);
            this.galleryBindingInstance = binding == null ? "" : binding.instance();
            this.originalFavorite = this.runtime.options.isFavorite(GALLERY_DRAFT_ID);
            client.setScreen(new LocalAppearanceScreen(this.runtime));
        }
        case "model-settings-ui" ->
        {
            this.settingsVerified = false;
            this.originalHeaddress = this.runtime.options.defaultHeaddress;
            this.headdressBefore.clear();
            this.headdressChanged.clear();
            this.headdressRestored.clear();
            client.setScreen(new ModelSettingsScreen(this.runtime, PROFILE.modelId(), null));
        }
        case "wheel-ui" ->
        {
            this.wheelVerified = false;
            this.wheelActionId = "";
            this.wheelSlot = -1;
            this.wheelConfigVerified = false;
            this.configStartedAt = -1;
            this.wheelOriginalActionLock = this.runtime.localActionLocked();
            this.configBefore.clear();
            this.configChanged.clear();
            this.configRestored.clear();
            this.wheelPose = client.player.getPose().name() + ":" + client.player.isSleeping() + ":"
                    + client.player.hasVehicle();
            client.setScreen(new AnimationWheelScreen(this.runtime, true, null));
        }
        case "local-actions-ui" ->
        {
            this.uiActionPressed = false;
            this.uiActionVerified = false;
            this.uiActionId = "";
            client.setScreen(new PlayerModelScreen(this.runtime, null));
        }
        case "action" -> check("standaloneLocalActionAccepted", this.runtime.playLocal("extra1"),
                this.runtime.localActions().toString());
        case "stop" -> this.runtime.stopLocal();
        case "vehicle-component", "projectile-component" -> beginComponentFixture(client, name);
        case "model-effects" -> this.effects = new ModelEffectsHarness(this.runtime);
        case "save-reload" ->
        {
            this.savedJson = Files.readString(this.runtime.localAppearanceSettingsPath(), StandardCharsets.UTF_8);
            JsonObject document = JsonParser.parseString(this.savedJson).getAsJsonObject();
            LocalAppearanceSettings persisted = GSON.fromJson(document.get("localAppearance"),
                    LocalAppearanceSettings.class);
            check("standaloneProfileSaved", PROFILE.equals(persisted),
                    "Saved JSON localAppearance=" + document.get("localAppearance"));
            this.savedDisabledReload = false;
            this.savedEnabledReload = false;
        }
        case "resource-reload" -> this.reload = client.reloadResources();
        case "ui-reload-ui" -> client.setScreen(new LocalAppearanceScreen(this.runtime));
        case "disable" -> this.runtime.disableLocalAppearance();
        default ->
            {
            }
        }
    }

    /** New entry/source/remembered UI acceptance only; historical asset and renderer stages stay above. */
    private void interactionUiTick(MinecraftClient client) throws Exception
    {
        if (this.stageTicks > 900)
        {
            check("standaloneInteractionCompletion", false, "UI step timeout=" + this.interactionStep
                    + "; Wine index/step=" + this.wineFoxIndex + "/" + this.wineFoxStep + "; screen="
                    + (client.currentScreen == null ? "world" : client.currentScreen.getClass().getName())
                    + "; preview="
                    + (client.currentScreen instanceof PlayerModelScreen hub ? hub.previewDiagnostics() : Map.of()));
            finish(client);
            return;
        }
        if (this.stageTicks - this.interactionStepAt < 4)
        {
            return;
        }

        Screen current = client.currentScreen;
        if (current != null)
        {
            this.interactionControlsValid &= interactionControlsInBounds(client, current);
        }

        switch (this.interactionStep)
        {
        case 0 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel) || own(client) == null
                    || !this.runtime.localModelProfile().isYsm()
                    || diagnosticNumber(wheel.diagnostics(), "emittedVertices") <= 0)
            {
                return;
            }

            List<KeyBinding> keys = Arrays.stream(client.options.allKeys)
                    .filter(key -> key.getId().startsWith("key.meplayeractions.")).toList();
            check("standaloneInteractionJOnlyBinding",
                    keys.size() == 1 && keys.getFirst() == MEPlayerActionsClient.actionWheelKey
                            && "key.meplayeractions.action_wheel".equals(keys.getFirst().getId())
                            && KeyBindingHelper.getBoundKeyOf(keys.getFirst()).getCode() == GLFW.GLFW_KEY_J,
                    "Actual registered bindings=" + keys.stream()
                            .map(key -> key.getId() + ":" + KeyBindingHelper.getBoundKeyOf(key).getCode()).toList());
            check("standaloneInteractionJOpensWheel", number(wheel.diagnostics(), "releaseKey") == GLFW.GLFW_KEY_J,
                    "KeyBinding.onKeyPressed drove the registered END_CLIENT_TICK route, not Screen construction");
            check("standaloneInteractionReferenceWheel", referenceWheel(wheel.diagnostics()),
                    "Actual reference polygon wheel=" + wheel.diagnostics());
            List<?> controls = (List<?>) wheel.diagnostics().get("controls");
            check("standaloneInteractionSingleSettings",
                    controls.stream().filter(Map.class::isInstance).map(Map.class::cast)
                            .filter(row -> "settings".equals(row.get("id"))).count() == 1
                            && Boolean.FALSE.equals(wheel.diagnostics().get("scopeButtonsVisible"))
                            && !wheel.children().stream().filter(ButtonWidget.class::isInstance)
                                    .map(ButtonWidget.class::cast)
                                    .anyMatch(button -> button.getMessage().getString().contains("本地动作")
                                            || button.getMessage().getString().contains("服务器动作")),
                    "One settings gear; source and preferences belong to the model page; controls=" + controls);
            interactionObservation(client, "j-wheel", wheel.diagnostics(), true);
            if (!interactionPress(client, wheel, label -> label.equals("⚙")))
            {
                throw new IllegalStateException("Wheel settings callback missing");
            }

            advanceInteraction();
        }
        case 1 ->
        {
            if (!(current instanceof PlayerModelScreen hub))
            {
                return;
            }

            Map<String, Object> diagnostics = hub.diagnostics();
            if (!(diagnostics.get("leftPreview") instanceof Map<?, ?> preview)
                    || !Boolean.TRUE.equals(preview.get("drawn")))
            {
                return;
            }

            Map<String, Object> source = guiPreviewSource(PROFILE.modelId());
            Map<String, Object> contexts = nativePreviewContextProof(client,
                    nativePreviewDiagnostics(hub, PROFILE.modelId()), String.valueOf(preview.get("key")), source);
            if (!Boolean.TRUE.equals(contexts.get("passed")))
            {
                return;
            }

            if (this.interactionHomeScreenshot.isEmpty())
            {
                LinkedHashMap<String, Object> evidence = new LinkedHashMap<String, Object>(diagnostics);
                evidence.put("nativePreviewSource", source);
                evidence.put("nativeContextProof", contexts);
                evidence.put("scaledWidth", client.getWindow().getScaledWidth());
                evidence.put("scaledHeight", client.getWindow().getScaledHeight());
                evidence.put("windowScaleFactor", client.getWindow().getScaleFactor());
                evidence.put("framebufferWidth", client.getWindow().getFramebufferWidth());
                evidence.put("framebufferHeight", client.getWindow().getFramebufferHeight());
                this.interactionHomeEvidence = evidence;
                interactionObservation(client, "model-home", evidence, true);
                this.interactionHomeScreenshot = this.screenshots.getLast();
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> capturedContext = (Map<String, Object>) this.interactionHomeEvidence
                    .get("nativeContextProof");
            if (!this.completedScreenshots.contains(this.interactionHomeScreenshot))
            {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> pixels = this.interactionHomeEvidence
                    .get("defaultVisibilityPixels") instanceof Map<?, ?> cached && cached.containsKey("passed")
                            ? (Map<String, Object>) cached
                            : defaultPngVisibilityProof(this.output.resolve(this.interactionHomeScreenshot),
                                    capturedContext,
                                    ((Number) this.interactionHomeEvidence.get("scaledWidth")).intValue(),
                                    ((Number) this.interactionHomeEvidence.get("scaledHeight")).intValue(),
                                    ((Number) this.interactionHomeEvidence.get("windowScaleFactor")).intValue(),
                                    ((Number) this.interactionHomeEvidence.get("framebufferWidth")).intValue(),
                                    ((Number) this.interactionHomeEvidence.get("framebufferHeight")).intValue());
            this.interactionHomeEvidence.put("defaultVisibilityPixels", pixels);
            if (!Boolean.TRUE.equals(pixels.get("passed")))
            {
                return;
            }

            check("standaloneInteractionSettingsOpensModelScreen",
                    "client".equals(diagnostics.get("mode"))
                            && Boolean.TRUE.equals(diagnostics.get("localModelControlsVisible")),
                    "Actual wheel settings gear opens unified client model page=" + diagnostics);
            check("standaloneInteractionReferenceModelScreen",
                    referenceModelScreen(hub, diagnostics) && Boolean.TRUE.equals(contexts.get("passed")),
                    "Actual source tabs/search/cards, depth-rendered owner/card cameras, unclipped head and real PNG white-eye pixels="
                            + this.interactionHomeEvidence);
            if (!interactionPress(client, hub, label -> label.startsWith("服务器下发")))
            {
                throw new IllegalStateException("Server source tab missing");
            }

            advanceInteraction();
        }
        case 2 ->
        {
            if (!(current instanceof PlayerModelScreen hub))
            {
                return;
            }

            Map<String, Object> diagnostics = hub.diagnostics();
            check("standaloneInteractionSources",
                    "server".equals(diagnostics.get("mode"))
                            && Boolean.FALSE.equals(diagnostics.get("localModelControlsVisible"))
                            && Boolean.FALSE.equals(diagnostics.get("actionGrid")),
                    "Unavailable server page can be browsed without a private action grid=" + diagnostics);
            check("standaloneInteractionNoServerAuthority",
                    !this.runtime.serverBridgeConnected() && !this.runtime.hasOwnServerDisguise()
                            && this.runtime.requestPacketsSent() == this.requestBaseline
                            && !ClientPlayNetworking.canSend(ActionPayload.ID),
                    "Actual server-page callback grants no server model, transport or request capability");
            interactionObservation(client, "unavailable-server-page", diagnostics, true);
            if (!interactionPress(client, hub, label -> label.startsWith("客户端")))
            {
                throw new IllegalStateException("Client source tab missing");
            }

            advanceInteraction();
        }
        case 3 ->
        {
            if (!(current instanceof PlayerModelScreen hub))
            {
                return;
            }

            if (!Boolean.TRUE.equals(hub.diagnostics().get("advanced")))
            {
                if (!interactionPress(client, hub, label -> label.startsWith("轮盘选项") || label.equals("⚙")))
                {
                    throw new IllegalStateException("Advanced options callback missing");
                }

                return;
            }
            boolean keepCallback = interactionPress(client, hub, label -> label.startsWith("选择后保持轮盘"));
            if (!this.runtime.wheelPreferences().keepOpen())
            {
                keepCallback &= interactionPress(client, hub, label -> label.startsWith("选择后保持轮盘"));
            }

            boolean lockCallback = interactionPress(client, hub, label -> label.startsWith("移动时保留本地动作"));
            check("standaloneInteractionAdvancedPreferences",
                    keepCallback && lockCallback && this.runtime.wheelPreferences().keepOpen()
                            && this.runtime.localActionLocked() != this.interactionOriginalActionLock
                            && Boolean.TRUE.equals(hub.diagnostics().get("advanced")),
                    "Actual advanced controls change separate keep-open/action-lock options=" + hub.diagnostics());
            ClientOptions persisted = new ClientOptions(this.runtime.localAppearanceSettingsPath());
            check("standaloneInteractionPreferencePersistence",
                    persisted.wheelPreferences().equals(this.runtime.wheelPreferences())
                            && persisted.localActionLocked == this.runtime.localActionLocked(),
                    "Fresh Options read of actual saved settings=" + persisted.wheelPreferences());
            interactionObservation(client, "advanced-options", hub.diagnostics(), true);
            hub.close();
            advanceInteraction();
        }
        case 4 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel))
            {
                return;
            }

            List<?> slots = (List<?>) wheel.diagnostics().get("configSlots");
            Map gear = slots.stream().filter(Map.class::isInstance).map(Map.class::cast).findFirst().orElse(null);
            if (gear == null)
            {
                throw new IllegalStateException("Default author configuration gear missing");
            }

            this.interactionFormWheel = wheel;
            this.interactionAuthorGroup = String.valueOf(gear.get("group")).replaceFirst("^#", "");
            wheel.mouseMoved(((Number) gear.get("x")).doubleValue(), ((Number) gear.get("y")).doubleValue());
            wheel.mouseClicked(new Click(((Number) gear.get("x")).doubleValue(), ((Number) gear.get("y")).doubleValue(),
                    new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
            advanceInteraction();
        }
        case 5 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel))
            {
                return;
            }

            Object panelValue = wheel.diagnostics().get("formPanel");
            Map<?, ?> panel = panelValue instanceof Map<?, ?> value ? value : Map.of();
            check("standaloneInteractionInlineAuthorForms",
                    Boolean.TRUE.equals(panel.get("visible")) && panel.get("forms") instanceof List<?> forms
                            && !forms.isEmpty()
                            && forms.stream()
                                    .anyMatch(value -> value instanceof Map<?, ?> form
                                            && form.get("controls") instanceof List<?> controls
                                            && controls.stream()
                                                    .anyMatch(item -> item instanceof Map<?, ?> control
                                                            && Boolean.TRUE.equals(control.get("visible"))
                                                            && Boolean.TRUE.equals(control.get("active"))))
                            && current == this.interactionFormWheel
                            && this.interactionAuthorGroup
                                    .equals(String.valueOf(panel.get("group")).replaceFirst("^#", ""))
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual authored gear opens forms on the same roulette screen=" + panel);
            interactionObservation(client, "inline-author-forms", wheel.diagnostics(), true);
            Map<?, ?> action = interactionActions(wheel).stream().filter(row -> "extra1".equals(row.get("id")))
                    .findFirst().orElseThrow();
            this.interactionAction = "extra1";
            wheel.beginHoldSelection();
            wheel.mouseMoved(((Number) action.get("x")).doubleValue(), ((Number) action.get("y")).doubleValue());
            this.interactionHoverSelected = number(wheel.diagnostics(), "hovered") == number(action, "slot");
            wheel.keyPressed(new KeyInput(GLFW.GLFW_KEY_1 + number(action, "slot"), 0, 0));
            advanceInteraction();
        }
        case 6 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel))
            {
                return;
            }

            ClientRuntime.RenderBinding binding = own(client);
            boolean manual = binding != null && binding.layers().stream().anyMatch(
                    layer -> layer.layer().equals("manual") && layer.animation().equals(this.interactionAction));
            this.interactionActionStart = binding == null ? -1
                    : binding.layers().stream()
                            .filter(layer -> layer.layer().equals("manual")
                                    && layer.animation().equals(this.interactionAction))
                            .mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
            wheel.keyReleased(new KeyInput(GLFW.GLFW_KEY_J, 0, 0));
            wheel.keyReleased(new KeyInput(GLFW.GLFW_KEY_J, 0, 0));
            check("standaloneInteractionLocalActionNoServerRequest",
                    manual && this.interactionHoverSelected && this.interactionActionStart >= 0
                            && this.runtime.requestPacketsSent() == this.requestBaseline
                            && client.currentScreen == wheel && this.runtime.wheelPreferences().keepOpen()
                            && Boolean.TRUE.equals(wheel.diagnostics().get("releaseConsumed")),
                    "Actual numeric callback selects local extra1 without server requests; release is consumed");
            interactionObservation(client, "local-numeric-action", wheel.diagnostics(), true);
            wheel.close();
            requestInteractionWheel(client);
            advanceInteraction();
        }
        case 7 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel))
            {
                return;
            }

            if (!interactionPress(client, wheel, label -> label.equals("⚙")))
            {
                throw new IllegalStateException("Reopened wheel settings missing");
            }

            advanceInteraction();
        }
        case 8 ->
        {
            if (!(current instanceof PlayerModelScreen hub))
            {
                return;
            }

            if (!this.interactionCardSelected)
            {
                TextFieldWidget search = hub.children().stream().filter(TextFieldWidget.class::isInstance)
                        .map(TextFieldWidget.class::cast).findFirst().orElse(null);
                if (search == null)
                {
                    throw new IllegalStateException("Unified gallery search missing");
                }

                search.setText("UI-Interaction-Memory");
                this.interactionCardSelected = interactionPress(client, hub,
                        label -> label.contains("UI-Interaction-Memory"));
                if (!this.interactionCardSelected)
                {
                    return;
                }

            }
            if (!interactionPress(client, hub, label -> label.equals("使用模型")))
            {
                return;
            }

            advanceInteraction();
        }
        case 9 ->
        {
            if (!(current instanceof PlayerModelScreen hub)
                    || !INTERACTION_MODEL.equals(this.runtime.localAppearance().modelId()) || own(client) == null)
            {
                return;
            }

            check("standaloneInteractionClientGallery",
                    this.interactionCardSelected && this.runtime.localAppearance().enabled()
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual search/card/use callbacks selected the disposable multi-page model="
                            + this.runtime.localAppearance());
            interactionObservation(client, "gallery-use-model", hub.diagnostics(), true);
            if (!interactionPress(client, hub,
                    label -> label.equals("设置") || label.startsWith("详情") || label.startsWith("外观设置")))
            {
                throw new IllegalStateException("Selected-model detail settings missing");
            }

            advanceInteraction();
        }
        case 10 ->
        {
            if (!(current instanceof ModelSettingsScreen settings))
            {
                return;
            }

            TextFieldWidget field = settings.children().stream().filter(TextFieldWidget.class::isInstance)
                    .map(TextFieldWidget.class::cast).filter(widget -> widget.getMessage().getString().equals("位置 X"))
                    .findFirst().orElseThrow();
            this.interactionOriginalX = this.runtime.localAppearance().offsetX();
            field.setText(Double.toString(this.interactionOriginalX + .125));
            boolean saved = interactionPress(client, settings, label -> label.equals("保存"));
            check("standaloneInteractionClientModelSettings", saved
                    && INTERACTION_MODEL.equals(settings.selectedModelId())
                    && Math.abs(this.runtime.localAppearance().offsetX() - this.interactionOriginalX - .125) < .000001
                    && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual selected-model detail X field/save callback is private=" + this.runtime.localAppearance());
            field.setText(Double.toString(this.interactionOriginalX));
            interactionPress(client, settings, label -> label.equals("保存"));
            interactionObservation(client, "model-detail-settings",
                    Map.of("selectedModelId", settings.selectedModelId(), "profile", this.runtime.localAppearance()),
                    true);
            settings.close();
            advanceInteraction();
        }
        case 11 ->
        {
            if (!(current instanceof PlayerModelScreen hub))
            {
                return;
            }

            hub.close();
            advanceInteraction();
        }
        case 12 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel) || number(wheel.diagnostics(), "pageCount") < 2)
            {
                return;
            }

            wheel.keyPressed(new KeyInput(GLFW.GLFW_KEY_RIGHT, 0, 0));
            this.interactionMemoryPage = number(wheel.diagnostics(), "page");
            this.interactionMemorySource = String.valueOf(wheel.diagnostics().get("source"));
            interactionObservation(client, "wheel-next-page", wheel.diagnostics(), true);
            wheel.close();
            requestInteractionWheel(client);
            advanceInteraction();
        }
        case 13 ->
        {
            if (!(current instanceof AnimationWheelScreen wheel))
            {
                return;
            }

            ClientOptions persisted = new ClientOptions(this.runtime.localAppearanceSettingsPath());
            check("standaloneInteractionWheelMemory",
                    this.interactionMemoryPage > 0 && number(wheel.diagnostics(), "page") == this.interactionMemoryPage
                            && this.interactionMemorySource.equals(wheel.diagnostics().get("source"))
                            && "CLIENT".equals(this.interactionMemorySource)
                            && persisted.wheelPreferences().clientPage() == this.interactionMemoryPage
                            && persisted.wheelPreferences().keepOpen()
                            && Boolean.TRUE.equals(wheel.diagnostics().get("keepOpen"))
                            && persisted.localActionLocked == this.runtime.localActionLocked(),
                    "Real next-page callback + close + registered J reopen restore nonzero source/page/preferences="
                            + wheel.diagnostics());
            interactionObservation(client, "wheel-memory-reopen", wheel.diagnostics(), true);
            if (!interactionCoordinatePress(wheel, button -> button.getMessage().getString().equals("⚙")))
            {
                return;
            }

            advanceInteraction();
        }
        case 14 ->
        {
            if (!wineFoxUiTick(client))
            {
                return;
            }

            check("standaloneInteractionControlsInBounds", this.interactionControlsValid,
                    "Visible initialized controls stayed within the actual scaled window throughout the new flow, including all three WineFox selections");
            this.interactionCompleted = true;
            finish(client);
        }
        default -> throw new IllegalStateException("Unknown interaction UI step=" + this.interactionStep);
        }
    }

    private void advanceInteraction()
    {
        this.interactionStep++;
        this.interactionStepAt = this.stageTicks;
    }

    private void requestInteractionWheel(MinecraftClient client)
    {
        client.setScreen(null);
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(MEPlayerActionsClient.actionWheelKey));
    }

    private static boolean interactionCoordinateClick(Screen screen, double x, double y)
    {
        if (!Double.isFinite(x) || !Double.isFinite(y))
        {
            return false;
        }

        screen.mouseMoved(x, y);
        Click click = new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        boolean accepted = screen.mouseClicked(click, false);
        screen.mouseReleased(click);
        return accepted;
    }

    private static boolean interactionCoordinatePress(Screen screen, Predicate<ButtonWidget> predicate)
    {
        ButtonWidget button = screen.children().stream().filter(ButtonWidget.class::isInstance)
                .map(ButtonWidget.class::cast)
                .filter(widget -> widget.visible && widget.active && predicate.test(widget)).findFirst().orElse(null);
        return button != null && interactionCoordinateClick(screen, button.getX() + button.getWidth() / 2d,
                button.getY() + button.getHeight() / 2d);
    }

    private static Map<String, Object> classJarOrigin(Class<?> type) throws Exception
    {
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
        boolean jar = Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
        return Map.of("class", type.getName(), "location", location.toString(), "path", path.toString(), "fromJar", jar,
                "sha256",
                jar ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
                        : "");
    }

    /** Read the author's actual declaration and clip from the loaded JAR, independent of GUI labels. */
    static Map<String, Object> guiPreviewSource(String id) throws Exception
    {
        if (GUI_PREVIEW_SOURCES.containsKey(id))
        {
            return GUI_PREVIEW_SOURCES.get(id);
        }

        BuiltinYsmModels.Model registered = BuiltinYsmModels.find(id).orElseThrow();
        String manifestPath = registered.resourceRoot() + "ysm.json";
        byte[] manifestBytes = registeredResource(manifestPath);
        JsonObject manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject properties = manifest.getAsJsonObject("properties");
        String animation = properties.has("preview_animation") ? properties.get("preview_animation").getAsString() : "";
        String animationPath = registered.resourceRoot() + manifest.getAsJsonObject("files").getAsJsonObject("player")
                .getAsJsonObject("animation").get("main").getAsString();
        byte[] animationBytes = registeredResource(animationPath);
        JsonObject clips = JsonParser.parseString(new String(animationBytes, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("animations");
        JsonObject clip = clips.has(animation) ? clips.getAsJsonObject(animation) : new JsonObject();
        String loop = !clip.has("loop") ? "ONCE"
                : (clip.get("loop").isJsonPrimitive() && clip.get("loop").getAsJsonPrimitive().isBoolean()
                        ? (clip.get("loop").getAsBoolean() ? "LOOP" : "ONCE")
                        : ("hold_on_last_frame".equals(clip.get("loop").getAsString()) ? "HOLD" : "ONCE"));
        LinkedHashMap<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("modelId", id);
        source.put("manifest", manifestPath);
        source.put("manifestSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
        source.put("properties", properties.deepCopy());
        source.put("animationResource", animationPath);
        source.put("animationSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(animationBytes)));
        source.put("animation", animation);
        source.put("loop", loop);
        source.put("length", clip.has("animation_length") ? clip.get("animation_length").getAsDouble() : 0d);
        source.put("clipPresent", clips.has(animation));
        source.put("disableCardRotation", properties.has("disable_preview_rotation")
                && properties.get("disable_preview_rotation").getAsBoolean());
        source.put("background",
                properties.has("gui_background") ? properties.get("gui_background").getAsString() : "");
        source.put("foreground",
                properties.has("gui_foreground") ? properties.get("gui_foreground").getAsString() : "");
        double authorHeight = properties.has("height_scale") ? properties.get("height_scale").getAsDouble() : .7;
        double authorWidth = properties.has("width_scale") ? properties.get("width_scale").getAsDouble() : .7;
        source.put("shape",
                Map.of("heightScalePropertyPresent", properties.has("height_scale"), "widthScalePropertyPresent",
                        properties.has("width_scale"), "heightScale", authorHeight, "widthScale", authorWidth,
                        "modelScale", List.of(authorHeight, authorWidth, authorHeight), "initialYOffset", .01));
        if (id.equals(PROFILE.modelId()))
        {
            source.put("defaultVisual", defaultVisualSource(registered.resourceRoot(), manifest, clips));
        }

        Map<String, Object> frozen = Map.copyOf(source);
        GUI_PREVIEW_SOURCES.put(id, frozen);
        return frozen;
    }

    private static Map<String, Object> defaultVisualSource(String root, JsonObject manifest, JsonObject animations)
            throws Exception
    {
        String geometryPath = root + manifest.getAsJsonObject("files").getAsJsonObject("player")
                .getAsJsonObject("model").get("main").getAsString();
        byte[] geometryBytes = registeredResource(geometryPath);
        JsonObject geometry = JsonParser.parseString(new String(geometryBytes, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        int width = geometry.getAsJsonObject("description").get("texture_width").getAsInt(),
                height = geometry.getAsJsonObject("description").get("texture_height").getAsInt();
        LinkedHashMap<String, List<Double>> bounds = new LinkedHashMap<String, List<Double>>();
        List<List<Double>> headBounds = new ArrayList<>();
        double openAfter = 0;
        for (JsonElement value : geometry.getAsJsonArray("bones"))
        {
            JsonObject bone = value.getAsJsonObject();
            String name = bone.get("name").getAsString();
            if (!List.of("Head", "RightEyelid", "LeftEyelid").contains(name))
            {
                continue;
            }

            JsonObject face = bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv")
                    .getAsJsonObject("north");
            JsonArray uv = face.getAsJsonArray("uv");
            JsonArray size = face.getAsJsonArray("uv_size");
            bounds.put(name,
                    List.of(uv.get(0).getAsDouble(), uv.get(1).getAsDouble(),
                            uv.get(0).getAsDouble() + size.get(0).getAsDouble(),
                            uv.get(1).getAsDouble() + size.get(1).getAsDouble()));
            if (name.equals("Head"))
            {
                for (Map.Entry<String, JsonElement> entry : bone.getAsJsonArray("cubes").get(0).getAsJsonObject()
                        .getAsJsonObject("uv").entrySet())
                {
                    JsonArray headUv = entry.getValue().getAsJsonObject().getAsJsonArray("uv");
                    JsonArray headSize = entry.getValue().getAsJsonObject().getAsJsonArray("uv_size");
                    double u = headUv.get(0).getAsDouble(), v = headUv.get(1).getAsDouble(),
                            du = headSize.get(0).getAsDouble(), dv = headSize.get(1).getAsDouble();
                    headBounds.add(List.of(Math.min(u, u + du), Math.min(v, v + dv), Math.max(u, u + du),
                            Math.max(v, v + dv)));
                }
            }

            if (!name.equals("Head"))
            {
                JsonObject scales = animations.getAsJsonObject("pre_parallel1").getAsJsonObject("bones")
                        .getAsJsonObject(name).getAsJsonObject("scale");
                for (Map.Entry<String, JsonElement> entry : scales.entrySet())
                {
                    JsonObject keyframe = entry.getValue().getAsJsonObject();
                    if (keyframe.getAsJsonArray("post").get(1).getAsDouble() == 1)
                    {
                        openAfter = Math.max(openAfter, Double.parseDouble(entry.getKey()) * 20);
                    }

                }
            }
        }
        String texturePath = root + manifest.getAsJsonObject("files").getAsJsonObject("player")
                .getAsJsonArray("texture").get(0).getAsString();
        byte[] textureBytes = registeredResource(texturePath);
        BufferedImage texture = ImageIO.read(new ByteArrayInputStream(textureBytes));
        List<Double> eye = bounds.get("RightEyelid");
        List<Integer> white = new ArrayList<>();
        for (int y = eye.get(1).intValue(); y < eye.get(3); y++)
        {
            for (int x = eye.get(0).intValue(); x < eye.get(2); x++)
            {
                white.add(texture.getRGB(x, y));
            }

        }

        LinkedHashMap<String, Object> proof = new LinkedHashMap<String, Object>();
        proof.put("geometry", geometryPath);
        proof.put("geometrySha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometryBytes)));
        proof.put("texture", texturePath);
        proof.put("textureSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(textureBytes)));
        proof.put("textureWidth", width);
        proof.put("textureHeight", height);
        proof.put("frontUvPixels", bounds);
        proof.put("whiteSourcePixels", white);
        proof.put("headUvPixels", headBounds);
        proof.put("blinkAnimation", "pre_parallel1");
        proof.put("blinkCycleTicks",
                animations.getAsJsonObject("pre_parallel1").get("animation_length").getAsDouble() * 20);
        proof.put("blinkSafeOpenStartTicks", Math.ceil(openAfter) + 2);
        proof.put("blinkSafeEndMarginTicks", 3);
        return proof;
    }

    private static byte[] registeredResource(String path) throws Exception
    {
        try (InputStream input = BuiltinYsmModels.class.getResourceAsStream(path))
        {
            if (input == null)
            {
                throw new IllegalStateException("Missing registered GUI source: " + path);
            }

            byte[] raw = input.readNBytes(8 * 1024 * 1024 + 1);
            if (raw.length == 0 || raw.length > 8 * 1024 * 1024)
            {
                throw new IllegalStateException("Invalid registered GUI source size: " + path);
            }

            return raw;
        }
    }

    private static boolean nearDiagnostic(Map<?, ?> draw, String name, double expected)
    {
        return Double.isFinite(diagnosticNumber(draw, name))
                && Math.abs(diagnosticNumber(draw, name) - expected) < .00001;
    }

    private static boolean matureNativeDraw(Map<?, ?> draw)
    {
        return diagnosticNumber(draw, "vertices") > 0 && diagnosticNumber(draw, "quads") > 0
                && diagnosticNumber(draw, "vertices") == diagnosticNumber(draw, "quads") * 4
                && diagnosticNumber(draw, "sampleTick") >= 12 && diagnosticNumber(draw, "entryProgress") >= .999
                && diagnosticNumber(draw, "startCount") == 1
                && Double.isFinite(diagnosticNumber(draw, "startedAtTick"));
    }

    private static boolean nativeScaleAndExecution(Map<?, ?> draw, Map<String, Object> source)
    {
        if (!(source.get("shape") instanceof Map<?, ?> shape) || !(draw.get("modelScale") instanceof Map<?, ?> scale)
                || !(draw.get("nativeBackend") instanceof Map<?, ?> backend)
                || !(draw.get("nativeExecution") instanceof Map<?, ?> execution))
        {
            return false;
        }

        List<Double> range = diagnosticVector(draw.get("modelVertexDepthRange"), 2);
        return nearDiagnostic(scale, "x", diagnosticNumber(shape, "heightScale"))
                && nearDiagnostic(scale, "y", diagnosticNumber(shape, "widthScale"))
                && nearDiagnostic(scale, "z", diagnosticNumber(shape, "heightScale"))
                && "minecraft-special-gui-offscreen".equals(backend.get("backend"))
                && "meplayeractions:pipeline/gui_model_native_depth".equals(backend.get("pipeline"))
                && "minecraft:core/position_tex_color".equals(backend.get("shader"))
                && "minecraft:core/position_tex_color".equals(backend.get("fragmentShader"))
                && "LEQUAL_DEPTH_TEST".equals(backend.get("depthTest"))
                && Boolean.TRUE.equals(backend.get("depthWrite")) && Boolean.TRUE.equals(backend.get("perVertexDepth"))
                && diagnosticNumber(execution, "renderCount") > 0
                && diagnosticNumber(execution,
                        "emittedVertices") == (diagnosticNumber(draw, "quads")
                                - diagnosticNumber(draw, "decorationQuads")) * 4
                                * diagnosticNumber(execution, "renderCount")
                && Boolean.TRUE.equals(execution.get("colorAttachment"))
                && Boolean.TRUE.equals(execution.get("depthAttachment")) && "RGBA8".equals(execution.get("colorFormat"))
                && "DEPTH32".equals(execution.get("depthFormat")) && diagnosticNumber(execution, "targetWidth") > 0
                && diagnosticNumber(execution, "targetHeight") > 0 && range.size() == 2 && Double.isFinite(range.get(0))
                && Double.isFinite(range.get(1)) && range.get(1) > range.get(0);
    }

    private static boolean ownerNativeContext(Map<?, ?> draw, String animation, double height, double scale)
    {
        return "OWNER".equals(draw.get("context")) && Boolean.TRUE.equals(draw.get("nativeCamera"))
                && "openysm-owner-inventory".equals(draw.get("cameraSource")) && matureNativeDraw(draw)
                && nearDiagnostic(draw, "displaySize", 70) && nearDiagnostic(draw, "pixelsPerBlock", 70)
                && nearDiagnostic(draw, "nativeEntityHeight", height)
                && nearDiagnostic(draw, "nativeEntityScale", scale)
                && nearDiagnostic(draw, "anchorY", height / scale * .5 + .0625)
                && Boolean.FALSE.equals(draw.get("rotationDisabled")) && Double.isFinite(diagnosticNumber(draw, "yaw"))
                && Double.isFinite(diagnosticNumber(draw, "pitch")) && "".equals(draw.get("background"))
                && "".equals(draw.get("foreground")) && nearDiagnostic(draw, "decorationQuads", 0)
                && draw.get("controllerSlots") instanceof List<?> slots && !slots.isEmpty()
                && !slots.contains("player.cap") && draw.get("layers") instanceof List<?> layers
                && !layers.contains(animation);
    }

    private static boolean cardNativeContext(Map<?, ?> draw, Map<String, Object> source)
    {
        boolean locked = Boolean.TRUE.equals(source.get("disableCardRotation"));
        String animation = String.valueOf(source.get("animation"));
        int decorations = (String.valueOf(source.get("background")).isEmpty() ? 0 : 1)
                + (String.valueOf(source.get("foreground")).isEmpty() ? 0 : 1);
        List<Map<?, ?>> requests = diagnosticRows(draw, "layerRequests");
        boolean actualCap = requests.size() == 1 && "player.cap".equals(requests.getFirst().get("layer"))
                && animation.equals(requests.getFirst().get("animation"))
                && source.get("loop").equals(requests.getFirst().get("loop"));
        return actualCap && nativeScaleAndExecution(draw, source) && "CARD".equals(draw.get("context"))
                && Boolean.TRUE.equals(draw.get("nativeCamera"))
                && "openysm-dummy-card".equals(draw.get("cameraSource")) && matureNativeDraw(draw)
                && nearDiagnostic(draw, "displaySize", 30) && nearDiagnostic(draw, "pixelsPerBlock", 30)
                && nearDiagnostic(draw, "nativeEntityHeight", 1.8) && nearDiagnostic(draw, "nativeEntityScale", 1)
                && nearDiagnostic(draw, "anchorY", .9 + (locked ? 5.5 / 30 : 0))
                && Boolean.valueOf(locked).equals(draw.get("rotationDisabled"))
                && nearDiagnostic(draw, "yaw", locked ? 0 : -20) && nearDiagnostic(draw, "pitch", locked ? 0 : -10)
                && source.get("background").equals(draw.get("background"))
                && source.get("foreground").equals(draw.get("foreground"))
                && nearDiagnostic(draw, "decorationQuads", decorations)
                && List.of("player.cap").equals(draw.get("controllerSlots"))
                && List.of(animation).equals(draw.get("layers"));
    }

    /** Both contexts must be real current submissions; their independent clocks need not be equal. */
    static Map<String, Object> nativePreviewContextProof(MinecraftClient client, Map<?, ?> diagnostics, String key,
            Map<String, Object> source)
    {
        List<Map<?, ?>> draws = diagnosticRows(diagnostics, "draws").stream().filter(row -> key.equals(row.get("key")))
                .toList();
        Map<?, ?> owner = draws.stream().filter(row -> "OWNER".equals(row.get("context"))).findFirst().orElse(Map.of());
        Map<?, ?> card = draws.stream().filter(row -> "CARD".equals(row.get("context"))).findFirst().orElse(Map.of());
        double height = client.player == null ? Double.NaN : client.player.getHeight(),
                scale = client.player == null ? Double.NaN : client.player.getScale();
        String animation = String.valueOf(source.get("animation"));
        boolean ownerValid = ownerNativeContext(owner, animation, height, scale)
                && nativeScaleAndExecution(owner, source), cardValid = cardNativeContext(card, source);
        Map<String, Object> visibility = source.get("defaultVisual") instanceof Map<?, ?> visual
                ? defaultProjectedVisibility(owner, card, visual)
                : Map.of();
        boolean visualValid = visibility.isEmpty() || Boolean.TRUE.equals(visibility.get("passed"));
        LinkedHashMap<String, Object> proof = new LinkedHashMap<String, Object>();
        proof.put("passed", Boolean.TRUE.equals(source.get("clipPresent")) && ownerValid && cardValid && visualValid);
        proof.put("owner", owner);
        proof.put("card", card);
        proof.put("ownerValid", ownerValid);
        proof.put("cardValid", cardValid);
        proof.put("actualOwnerNativeHeight", height);
        proof.put("actualOwnerNativeScale", scale);
        proof.put("sourcePreviewAnimation", animation);
        proof.put("sourcePreviewAnimationSha256", source.get("animationSha256"));
        proof.put("sourcePreviewAnimationLoop", source.get("loop"));
        proof.put("sourcePreviewAnimationLength", source.get("length"));
        proof.put("independentContexts", !owner.isEmpty() && !card.isEmpty() && owner != card);
        proof.put("sourceShape", source.get("shape"));
        proof.put("actualOwnerPlayerAgeTicks", client.player == null ? 0 : client.player.age);
        if (!visibility.isEmpty())
        {
            proof.put("defaultProjectedVisibility", visibility);
        }

        return proof;
    }

    static Map<String, Object> nativePreviewDiagnostics(PlayerModelScreen screen, String id) throws Exception
    {
        Map<String, Object> source = guiPreviewSource(id);
        if (!(source.get("defaultVisual") instanceof Map<?, ?> visual)
                || !(visual.get("frontUvPixels") instanceof Map<?, ?> regions))
        {
            return screen.previewDiagnostics();
        }

        List<Double> eye = diagnosticVector(regions.get("RightEyelid"), 4);
        float width = (float) diagnosticNumber(visual, "textureWidth"),
                height = (float) diagnosticNumber(visual, "textureHeight");
        Map<String, Object> diagnostics = screen.previewDiagnostics((float) (eye.get(0) / width),
                (float) (eye.get(1) / height), (float) (eye.get(2) / width), (float) (eye.get(3) / height));
        List<Map<String, Object>> headDiagnostics = new ArrayList<>();
        for (Object value : (List<?>) visual.get("headUvPixels"))
        {
            List<Double> head = diagnosticVector(value, 4);
            headDiagnostics.add(screen.previewDiagnostics((float) (head.get(0) / width), (float) (head.get(1) / height),
                    (float) (head.get(2) / width), (float) (head.get(3) / height)));
        }
        List<Map<String, Object>> draws = new ArrayList<>();
        for (Map<?, ?> draw : diagnosticRows(diagnostics, "draws"))
        {
            Map<String, Object> copy = new LinkedHashMap<>();
            draw.forEach((name, value) -> copy.put(String.valueOf(name), value));
            List<Map<?, ?>> heads = headDiagnostics.stream().flatMap(query -> diagnosticRows(query, "draws").stream())
                    .filter(row -> draw.get("key").equals(row.get("key"))
                            && draw.get("context").equals(row.get("context")))
                    .flatMap(row -> diagnosticRows(row, "faces").stream()).distinct().toList();
            copy.put("headFaces", heads);
            draws.add(copy);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>(diagnostics);
        result.put("draws", draws);
        return result;
    }

    private static boolean faceMatchesSource(Map<?, ?> face, List<Double> bounds, double width, double height)
    {
        if (!(face.get("sourceUv") instanceof List<?> uvs) || uvs.size() != 4
                || diagnosticVector(face.get("depths"), 4).size() != 4)
        {
            return false;
        }

        double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY,
                maxV = Double.NEGATIVE_INFINITY;
        for (Object value : uvs)
        {
            List<Double> uv = diagnosticVector(value, 2);
            if (uv.isEmpty())
            {
                return false;
            }

            minU = Math.min(minU, uv.get(0) * width);
            maxU = Math.max(maxU, uv.get(0) * width);
            minV = Math.min(minV, uv.get(1) * height);
            maxV = Math.max(maxV, uv.get(1) * height);
        }
        return Math.abs(minU - bounds.get(0)) < .001 && Math.abs(minV - bounds.get(1)) < .001
                && Math.abs(maxU - bounds.get(2)) < .001 && Math.abs(maxV - bounds.get(3)) < .001;
    }

    private static double projectedFaceArea(Map<?, ?> face)
    {
        if (!(face.get("points") instanceof List<?> points) || points.size() != 4)
        {
            return 0;
        }

        double area = 0;
        for (int index = 0; index < 4; index++)
        {
            List<Double> a = diagnosticVector(points.get(index), 2);
            List<Double> b = diagnosticVector(points.get((index + 1) % 4), 2);
            if (a.isEmpty() || b.isEmpty())
            {
                return 0;
            }

            area += a.get(0) * b.get(1) - b.get(0) * a.get(1);
        }
        return Math.abs(area) * .5;
    }

    private static boolean faceInsideClip(Map<?, ?> face, Map<?, ?> clip)
    {
        if (!(face.get("points") instanceof List<?> points) || points.size() != 4)
        {
            return false;
        }

        return points.stream().allMatch(value ->
        {
            List<Double> point = diagnosticVector(value, 2);
            return point.size() == 2 && Double.isFinite(point.get(0)) && Double.isFinite(point.get(1))
                    && point.get(0) >= diagnosticNumber(clip, "x")
                    && point.get(0) <= diagnosticNumber(clip, "x") + diagnosticNumber(clip, "width")
                    && point.get(1) >= diagnosticNumber(clip, "y")
                    && point.get(1) <= diagnosticNumber(clip, "y") + diagnosticNumber(clip, "height");
        });
    }

    private static Map<String, Object> defaultProjectedVisibility(Map<?, ?> owner, Map<?, ?> card, Map<?, ?> source)
    {
        if (!(source.get("frontUvPixels") instanceof Map<?, ?> regions))
        {
            return Map.of("passed", false);
        }

        List<Double> eyeUv = diagnosticVector(regions.get("RightEyelid"), 4);
        List<List<Double>> headUvs = ((List<?>) source.get("headUvPixels")).stream()
                .map(value -> diagnosticVector(value, 4)).toList();
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (Map<?, ?> draw : List.of(owner, card))
        {
            List<Map<?, ?>> eyes = diagnosticRows(draw, "faces").stream()
                    .filter(face -> faceMatchesSource(face, eyeUv, diagnosticNumber(source, "textureWidth"),
                            diagnosticNumber(source, "textureHeight")) && projectedFaceArea(face) > .25)
                    .toList();
            List<Map<?, ?>> heads = diagnosticRows(draw, "headFaces").stream()
                    .filter(face -> headUvs.stream().anyMatch(uv -> faceMatchesSource(face, uv,
                            diagnosticNumber(source, "textureWidth"), diagnosticNumber(source, "textureHeight"))))
                    .toList();
            Map<?, ?> clip = draw.get("clip") instanceof Map<?, ?> value ? value : Map.of();
            double age = diagnosticNumber(draw, "sampleTick"), cycle = diagnosticNumber(source, "blinkCycleTicks"),
                    phase = age % cycle;
            boolean open = Double.isFinite(phase) && phase >= diagnosticNumber(source, "blinkSafeOpenStartTicks")
                    && phase < cycle - diagnosticNumber(source, "blinkSafeEndMarginTicks");
            boolean contained = eyes.size() == 2 && heads.size() == 6
                    && eyes.stream().allMatch(face -> faceInsideClip(face, clip))
                    && heads.stream().allMatch(face -> faceInsideClip(face, clip));
            contexts.add(Map.of("context", String.valueOf(draw.get("context")), "passed", open && contained,
                    "sourceBlinkSafeOpen", open, "sampleTick", Double.isFinite(age) ? age : -1, "blinkPhaseTicks",
                    Double.isFinite(phase) ? phase : -1, "headFullyInsideClip", contained, "eyeFaces", eyes,
                    "headFaces", heads, "clip", clip));
        }
        return Map.of("passed",
                contexts.size() == 2 && contexts.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))),
                "contexts", contexts, "source", source);
    }

    private static boolean insideProjectedQuad(List<?> points, double x, double y)
    {
        double sign = 0;
        for (int i = 0; i < 4; i++)
        {
            List<Double> a = diagnosticVector(points.get(i), 2);
            List<Double> b = diagnosticVector(points.get((i + 1) % 4), 2);
            if (a.isEmpty() || b.isEmpty())
            {
                return false;
            }

            double cross = (b.get(0) - a.get(0)) * (y - a.get(1)) - (b.get(1) - a.get(1)) * (x - a.get(0));
            if (Math.abs(cross) > 1e-7)
            {
                if (sign != 0 && Math.signum(cross) != sign)
                {
                    return false;
                }
                sign = Math.signum(cross);
            }
        }
        return sign != 0;
    }

    /** Read actual framebuffer PNG pixels after the asynchronous screenshot has been written. */
    static Map<String, Object> defaultPngVisibilityProof(Path path, Map<String, Object> proof, int scaledWidth,
            int scaledHeight, int windowScaleFactor, int framebufferWidth, int framebufferHeight) throws Exception
    {
        if (!Files.isRegularFile(path))
        {
            return Map.of("pending", true);
        }

        byte[] bytes = Files.readAllBytes(path);
        BufferedImage image;
        try
        {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        }
        catch (IOException incomplete)
        {
            return Map.of("pending", true, "readError", incomplete.toString());
        }
        if (image == null)
        {
            return Map.of("pending", true);
        }

        if (!(proof.get("defaultProjectedVisibility") instanceof Map<?, ?> projected)
                || !(projected.get("source") instanceof Map<?, ?> source)
                || !(source.get("whiteSourcePixels") instanceof List<?> sourcePixels))
        {
            return Map.of("passed", false, "reason", "Missing original default eye/clip evidence");
        }

        int minimumSource = sourcePixels.stream().mapToInt(value ->
        {
            int color = ((Number) value).intValue();
            return Math.min(color >> 16 & 255, Math.min(color >> 8 & 255, color & 255));
        }).min().orElse(255);
        int threshold = (int) Math.floor(minimumSource * .45), maximumChannelSpread = 24;
        // GuiRenderer projects using framebuffer dimensions / the real integer GUI scale, rather than ceil-scaled window dimensions.
        boolean dimensionsMatch = windowScaleFactor > 0 && image.getWidth() == framebufferWidth
                && image.getHeight() == framebufferHeight
                && Math.ceil((double) framebufferWidth / windowScaleFactor) == scaledWidth
                && Math.ceil((double) framebufferHeight / windowScaleFactor) == scaledHeight;
        if (!dimensionsMatch)
        {
            return Map.of("passed", false, "reason",
                    "Screenshot framebuffer or GUI scale changed after preview capture");
        }

        double sx = windowScaleFactor, sy = windowScaleFactor;
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (Map<?, ?> context : diagnosticRows(projected, "contexts"))
        {
            List<Map<String, Object>> eyes = new ArrayList<>();
            for (Map<?, ?> face : diagnosticRows(context, "eyeFaces"))
            {
                if (!(face.get("points") instanceof List<?> points) || points.size() != 4)
                {
                    continue;
                }

                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
                        maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (Object value : points)
                {
                    List<Double> point = diagnosticVector(value, 2);
                    minX = Math.min(minX, point.get(0) * sx);
                    maxX = Math.max(maxX, point.get(0) * sx);
                    minY = Math.min(minY, point.get(1) * sy);
                    maxY = Math.max(maxY, point.get(1) * sy);
                }
                int tested = 0, white = 0;
                List<List<Integer>> samples = new ArrayList<>();
                for (int y = Math.max(0, (int) Math.floor(minY)); y < Math.min(image.getHeight(),
                        (int) Math.ceil(maxY)); y++)

                {
                    for (int x = Math.max(0, (int) Math.floor(minX)); x < Math.min(image.getWidth(),
                            (int) Math.ceil(maxX)); x++)
                    {
                        if (!insideProjectedQuad(points, (x + .5) / sx, (y + .5) / sy))
                        {
                            continue;
                        }

                        tested++;
                        int color = image.getRGB(x, y), r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                        if (Math.min(r, Math.min(g, b)) >= threshold
                                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= maximumChannelSpread)
                        {
                            white++;
                            if (samples.size() < 16)
                            {
                                samples.add(List.of(x, y, color));
                            }

                        }
                    }
                }

                eyes.add(Map.of("points", points, "testedPixels", tested, "visibleNeutralEyePixels", white,
                        "pixelSamples", samples));
            }
            contexts.add(Map.of("context", context.get("context"), "eyes", eyes, "passed", eyes.size() == 2
                    && eyes.stream().allMatch(row -> diagnosticNumber(row, "visibleNeutralEyePixels") > 0)));
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("passed", Boolean.TRUE.equals(projected.get("passed")) && contexts.size() == 2
                && contexts.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))));
        result.put("screenshot", path.toAbsolutePath().toString());
        result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("width", image.getWidth());
        result.put("height", image.getHeight());
        result.put("scaledWidth", scaledWidth);
        result.put("scaledHeight", scaledHeight);
        result.put("windowScaleFactor", windowScaleFactor);
        result.put("framebufferWidth", framebufferWidth);
        result.put("framebufferHeight", framebufferHeight);
        result.put("minimumWhiteChannel", threshold);
        result.put("maximumWhiteChannelSpread", maximumChannelSpread);
        result.put("contexts", contexts);
        return result;
    }

    private Map<String, Object> readWineFoxSource(String id) throws Exception
    {
        BuiltinYsmModels.Model model = BuiltinYsmModels.find(id).orElseThrow();
        String path = model.resourceRoot() + "ysm.json";
        byte[] raw;
        try (InputStream input = BuiltinYsmModels.class.getResourceAsStream(path))
        {
            if (input == null)
            {
                throw new IllegalStateException("Missing registered WineFox manifest: " + path);
            }
            raw = input.readAllBytes();
        }
        JsonObject manifest = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        String geometryPath = model.resourceRoot() + manifest.getAsJsonObject("files").getAsJsonObject("player")
                .getAsJsonObject("model").get("main").getAsString();
        byte[] geometry;
        try (InputStream input = BuiltinYsmModels.class.getResourceAsStream(geometryPath))
        {
            if (input == null)
            {
                throw new IllegalStateException("Missing authored WineFox geometry: " + geometryPath);
            }
            geometry = input.readAllBytes();
        }
        JsonObject main = JsonParser.parseString(new String(geometry, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        List<String> bones = main.getAsJsonArray("bones").asList().stream()
                .map(value -> value.getAsJsonObject().get("name").getAsString()).distinct().toList();
        LinkedHashMap<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("resourceRoot", model.resourceRoot());
        source.put("registryModelId", model.id());
        source.put("registryLabel", model.label());
        source.put("manifest", path);
        source.put("manifestSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        source.put("spec", manifest.get("spec").getAsInt());
        source.put("declaredFiles", manifest.getAsJsonObject("files").deepCopy());
        source.put("primaryGeometry", geometryPath);
        source.put("primaryGeometrySha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometry)));
        source.put("primaryBoneNames", bones);
        source.put("registryClassOrigin", classJarOrigin(BuiltinYsmModels.class));
        source.put("modelRendererClassOrigin", classJarOrigin(ModelRenderer.class));
        source.put("expectedClientJarSha256", this.artifactProof.get("clientArtifactSha256"));
        source.put("guiPreview", guiPreviewSource(id));
        source.put("shape", guiPreviewSource(id).get("shape"));
        return source;
    }

    private static List<Map<?, ?>> diagnosticRows(Map<?, ?> diagnostics, String key)
    {
        return diagnostics.get(key) instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value).toList()
                : List.of();
    }

    private boolean wineFoxUiTick(MinecraftClient client) throws Exception
    {
        String id = WINE_FOX_IDS.get(this.wineFoxIndex);
        if (this.wineFoxStep == 0 && client.currentScreen instanceof AnimationWheelScreen wheel)
        {
            if (interactionCoordinatePress(wheel, button -> button.getMessage().getString().equals("⚙")))
            {
                return false;
            }

            return false;
        }
        if (this.wineFoxStep == 0 && client.currentScreen instanceof PlayerModelScreen hub)
        {
            if (!"client".equals(hub.diagnostics().get("mode")))
            {
                return false;
            }

            TextFieldWidget search = hub.children().stream().filter(TextFieldWidget.class::isInstance)
                    .map(TextFieldWidget.class::cast).findFirst().orElseThrow();
            if (!search.getText().equals(id))
            {
                search.setText(id);
            }

            Map<?, ?> card = diagnosticRows(hub.galleryDiagnostics(), "cards").stream()
                    .filter(row -> id.equals(row.get("modelId"))).findFirst().orElse(Map.of());
            if (card.isEmpty())
            {
                return false;
            }

            this.wineFoxDraftProfile = this.runtime.localAppearance();
            this.wineFoxPreviewBaseline = (long) diagnosticNumber(hub.previewDiagnostics(), "emittedVertices");
            if (!interactionCoordinateClick(hub, diagnosticNumber(card, "x") + diagnosticNumber(card, "width") / 2,
                    diagnosticNumber(card, "y") + diagnosticNumber(card, "height") / 2))
            {
                return false;
            }

            if (!id.equals(hub.selectedModelId()))
            {
                throw new IllegalStateException("Actual WineFox card callback selected another ID");
            }

            this.wineFoxSource = readWineFoxSource(id);
            this.wineFoxStep = 1;
            return false;
        }
        if (this.wineFoxStep == 1 && client.currentScreen instanceof PlayerModelScreen hub)
        {
            Map<String, Object> gallery = hub.galleryDiagnostics();
            Map<String, Object> actual = hub.previewDiagnostics();
            Map<String, Object> guiSource = guiPreviewSource(id);
            String prefix = id + ":";
            Map<?, ?> draftDraw = diagnosticRows(actual, "draws").stream().filter(
                    row -> String.valueOf(row.get("key")).startsWith(prefix) && cardNativeContext(row, guiSource))
                    .findFirst().orElse(Map.of());
            if (draftDraw.isEmpty())
            {
                return false;
            }

            String key = String.valueOf(draftDraw.get("key"));
            String hash = key.substring(prefix.length());
            if (!hash.matches("[a-f0-9]{64}"))
            {
                return false;
            }

            Map<?, ?> card = diagnosticRows(gallery, "cards").stream().filter(row -> id.equals(row.get("modelId")))
                    .findFirst().orElse(Map.of());
            if (!Boolean.TRUE.equals(card.get("loaded")) || !Boolean.TRUE.equals(card.get("drawn"))
                    || !Boolean.TRUE.equals(card.get("selected"))
                    || diagnosticNumber(actual, "emittedVertices") <= this.wineFoxPreviewBaseline
                    || diagnosticNumber(actual, "frameVertices") <= 0)
            {
                return false;
            }

            if (!this.wineFoxDraftProfile.equals(this.runtime.localAppearance()))
            {
                throw new IllegalStateException("Browsing WineFox card applied a private model before use");
            }

            LinkedHashMap<String, Object> draft = new LinkedHashMap<String, Object>();
            draft.put("modelId", id);
            draft.put("hash", hash);
            draft.put("selectedCard", card);
            draft.put("actualDraws", List.of(draftDraw));
            draft.put("profileBeforeUse", this.wineFoxDraftProfile);
            draft.put("profileUnchanged", true);
            draft.put("leftPreviewBeforeUse", gallery.getOrDefault("leftPreview", Map.of()));
            draft.put("emittedVertices", diagnosticNumber(actual, "emittedVertices"));
            draft.put("baselineEmittedVertices", this.wineFoxPreviewBaseline);
            this.wineFoxHash = hash;
            LinkedHashMap<String, Object> preview = new LinkedHashMap<String, Object>();
            preview.put("modelId", id);
            preview.put("hash", hash);
            preview.put("draftCallback", true);
            preview.put("profileBeforeUse", this.wineFoxDraftProfile);
            preview.put("draftPreview", draft);
            this.wineFoxPreview = preview;
            Map<String, Object> renderer = ModelRenderer.diagnostics();
            this.wineFoxFrameBaseline = (long) diagnosticNumber(renderer, "extractedFrames");
            this.wineFoxDrawBaseline = (long) diagnosticNumber(renderer, "drawnBatches");
            if (!interactionCoordinatePress(hub, button -> button.getMessage().getString().equals("使用模型")))
            {
                return false;
            }

            this.wineFoxPreview = new LinkedHashMap<>(this.wineFoxPreview);
            this.wineFoxPreview.put("useCallback", true);
            this.wineFoxStep = 2;
            this.wineFoxWorldSamples = 0;
            this.wineFoxWorldFrames.clear();
            this.wineFoxLastFrame = -1;
            this.wineFoxInstance = "";
            hub.close();
            client.setScreen(null);
            client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            return false;
        }
        if (this.wineFoxStep == 2)
        {
            ClientRuntime.RenderBinding binding = own(client);
            Map<String, Object> renderer = ModelRenderer.diagnostics();
            long frame = (long) diagnosticNumber(renderer, "extractedFrames");
            Map<?, ?> shape = (Map<?, ?>) this.wineFoxSource.get("shape");
            ModelRenderer.FrameModel body = renderer.get("models") instanceof List<?> values ? values.stream()
                    .filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                    .filter(value -> client.player.getUuidAsString().equals(value.owner())
                            && this.wineFoxHash.equals(value.hash()) && "local-self".equals(value.motionSource())
                            && value.vertices() > 0)
                    .findFirst().orElse(null) : null;
            boolean ready = client.currentScreen == null && binding != null
                    && id.equals(this.runtime.localAppearance().modelId()) && this.runtime.localAppearance().enabled()
                    && binding.instance().startsWith("local-self:") && this.wineFoxHash.equals(binding.assetHash())
                    && body != null && frame > this.wineFoxFrameBaseline
                    && diagnosticNumber(renderer, "drawnBatches") > this.wineFoxDrawBaseline
                    && !this.runtime.serverBridgeConnected() && !this.runtime.hasOwnServerDisguise()
                    && this.runtime.requestPacketsSent() == this.requestBaseline
                    && body.instance().equals(binding.instance()) && body.nativeYsm()
                    && Math.abs(body.modelScaleX() - diagnosticNumber(shape, "heightScale")) < .00001
                    && Math.abs(body.modelScaleY() - diagnosticNumber(shape, "widthScale")) < .00001
                    && Math.abs(body.modelScaleZ() - diagnosticNumber(shape, "heightScale")) < .00001
                    && Math.abs(body.bindingScale() - binding.scale()) < .00001
                    && Math.abs(body.initialYOffset() - .01) < .00001;
            if (!ready)
            {
                this.wineFoxWorldSamples = 0;
                return false;
            }
            if (this.wineFoxInstance.isEmpty())
            {
                this.wineFoxInstance = binding.instance();
            }

            if (!this.wineFoxInstance.equals(binding.instance()))
            {
                this.wineFoxWorldSamples = 0;
                return false;
            }
            if (frame != this.wineFoxLastFrame)
            {
                this.wineFoxLastFrame = frame;
                this.wineFoxWorldSamples++;
                this.wineFoxWorldFrames.add(Map.of("extractedFrame", frame, "vertices", body.vertices(), "drawnBatches",
                        diagnosticNumber(renderer, "drawnBatches"), "owner", binding.owner().toString(), "instance",
                        binding.instance(), "hash", binding.assetHash(), "sampleTick", binding.serverTick(), "body",
                        body));
            }
            if (this.wineFoxWorldSamples < 3)
            {
                return false;
            }

            Map<String, Object> world = new LinkedHashMap<>();
            world.put("owner", binding.owner().toString());
            world.put("instance", binding.instance());
            world.put("hash", binding.assetHash());
            world.put("extractedFrame", frame);
            world.put("baselineFrame", this.wineFoxFrameBaseline);
            world.put("vertices", body.vertices());
            world.put("drawnBatches", diagnosticNumber(renderer, "drawnBatches"));
            world.put("baselineDrawnBatches", this.wineFoxDrawBaseline);
            world.put("frames", List.copyOf(this.wineFoxWorldFrames));
            world.put("modelRendererClassOrigin", this.wineFoxSource.get("modelRendererClassOrigin"));
            world.put("nativeYsm", body.nativeYsm());
            world.put("sourceShape", shape);
            world.put("modelScale", Map.of("x", body.modelScaleX(), "y", body.modelScaleY(), "z", body.modelScaleZ()));
            world.put("bindingScale", body.bindingScale());
            world.put("initialYOffset", body.initialYOffset());
            boolean sourceBones = this.wineFoxSource.get("primaryBoneNames") instanceof List<?> names
                    && names.stream().allMatch(name -> binding.model().hasBone(String.valueOf(name)));
            world.put("primarySourceBonesPresent", sourceBones);
            Map<?, ?> registryOrigin = (Map<?, ?>) this.wineFoxSource.get("registryClassOrigin"),
                    rendererOrigin = (Map<?, ?>) this.wineFoxSource.get("modelRendererClassOrigin");
            boolean jar = Boolean.TRUE.equals(registryOrigin.get("fromJar"))
                    && Boolean.TRUE.equals(rendererOrigin.get("fromJar"))
                    && this.artifactProof.get("clientArtifactSha256").equals(registryOrigin.get("sha256"))
                    && this.artifactProof.get("clientArtifactSha256").equals(rendererOrigin.get("sha256"));
            this.wineFoxWorld = Map.copyOf(world);
            this.wineFoxClassesCurrent = jar;
            interactionObservation(client, "winefox-" + id + "-game",
                    Map.of("modelId", id, "source", this.wineFoxSource, "assetHash", this.wineFoxHash, "draftPreview",
                            this.wineFoxPreview.get("draftPreview"), "world", this.wineFoxWorld,
                            "sourceClassesFromTestedJar", jar),
                    true);
            this.wineFoxGameScreenshot = this.screenshots.getLast();
            this.wineFoxStep = 3;
            requestInteractionWheel(client);
            return false;
        }
        if (this.wineFoxStep == 3)
        {
            if (client.currentScreen instanceof AnimationWheelScreen wheel)
            {
                interactionCoordinatePress(wheel, button -> button.getMessage().getString().equals("⚙"));
                return false;
            }
            if (client.currentScreen instanceof PlayerModelScreen hub)
            {
                TextFieldWidget search = hub.children().stream().filter(TextFieldWidget.class::isInstance)
                        .map(TextFieldWidget.class::cast).findFirst().orElseThrow();
                search.setText(id);
                this.wineFoxPreviewBaseline = (long) diagnosticNumber(hub.previewDiagnostics(), "emittedVertices");
                this.wineFoxStep = 4;
            }
            return false;
        }
        if (this.wineFoxStep == 4 && client.currentScreen instanceof PlayerModelScreen hub)
        {
            Map<String, Object> gallery = hub.galleryDiagnostics(), actual = hub.previewDiagnostics();
            String key = id + ":" + this.wineFoxHash;
            ClientRuntime.RenderBinding binding = own(client);
            if (binding == null || !this.wineFoxInstance.equals(binding.instance())
                    || !this.wineFoxHash.equals(binding.assetHash())
                    || !id.equals(this.runtime.localAppearance().modelId())
                    || this.runtime.requestPacketsSent() != this.requestBaseline)
            {
                return false;
            }

            if (!(gallery.get("leftPreview") instanceof Map<?, ?> left) || !id.equals(left.get("modelId"))
                    || !key.equals(left.get("key")) || !Boolean.TRUE.equals(left.get("drawn")))
            {
                return false;
            }

            Map<?, ?> card = diagnosticRows(gallery, "cards").stream().filter(row -> id.equals(row.get("modelId")))
                    .findFirst().orElse(Map.of());
            Map<String, Object> contexts = nativePreviewContextProof(client, actual, key, guiPreviewSource(id));
            if (!Boolean.TRUE.equals(contexts.get("passed")) || !Boolean.TRUE.equals(card.get("loaded"))
                    || !Boolean.TRUE.equals(card.get("drawn")) || !Boolean.TRUE.equals(card.get("selected"))
                    || diagnosticNumber(actual, "emittedVertices") <= this.wineFoxPreviewBaseline)
            {
                return false;
            }

            List<Map<?, ?>> draws = diagnosticRows(actual, "draws").stream()
                    .filter(row -> key.equals(row.get("key")) && matureNativeDraw(row)).toList();
            LinkedHashMap<String, Object> preview = new LinkedHashMap<String, Object>(this.wineFoxPreview);
            preview.put("selectedCard", card);
            preview.put("leftPreview", left);
            preview.put("vertices", diagnosticNumber(actual, "frameVertices"));
            preview.put("actualDraws", draws);
            preview.put("nativeContextProof", contexts);
            preview.put("emittedVertices", diagnosticNumber(actual, "emittedVertices"));
            preview.put("baselineEmittedVertices", this.wineFoxPreviewBaseline);
            preview.put("ownerCapturedAfterActualUse", true);
            this.wineFoxPreview = preview;
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("modelId", id);
            evidence.put("source", this.wineFoxSource);
            evidence.put("assetHash", this.wineFoxHash);
            evidence.put("preview", this.wineFoxPreview);
            evidence.put("world", this.wineFoxWorld);
            evidence.put("sourceClassesFromTestedJar", this.wineFoxClassesCurrent);
            interactionObservation(client, "winefox-" + id + "-home", Map.of("modelId", id, "source",
                    this.wineFoxSource, "preview", this.wineFoxPreview, "gallery", gallery), true);
            this.wineFoxHomeScreenshot = this.screenshots.getLast();
            evidence.put("screenshots", List.of(this.wineFoxHomeScreenshot, this.wineFoxGameScreenshot));
            this.wineFoxModels.add(Map.copyOf(evidence));
            check(WINE_FOX_CHECKS.get(this.wineFoxIndex),
                    this.wineFoxClassesCurrent
                            && Boolean.TRUE.equals(this.wineFoxWorld.get("primarySourceBonesPresent"))
                            && Boolean.TRUE.equals(contexts.get("passed")),
                    "Real CARD draft leaves world unchanged; use callback creates current local body, then J/settings reopens actual OWNER inventory and independent author CARD cap previews: "
                            + evidence);
            this.wineFoxStep = 5;
            this.wineFoxContinueAt = this.stageTicks;
            return false;
        }
        if (this.wineFoxStep == 5 && this.stageTicks - this.wineFoxContinueAt >= 4)
        {
            if (++this.wineFoxIndex >= WINE_FOX_IDS.size())
            {
                return true;
            }

            this.wineFoxStep = 0;
            requestInteractionWheel(client);
        }
        return false;
    }

    private static List<Map<?, ?>> interactionActions(AnimationWheelScreen wheel)
    {
        Object entries = wheel.diagnostics().get("actions");
        return entries instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value).toList()
                : List.of();
    }

    private static boolean referenceWheel(Map<String, Object> diagnostics)
    {
        if (!Boolean.TRUE.equals(diagnostics.get("upstreamRoulette"))
                || !Boolean.TRUE.equals(diagnostics.get("polygon"))
                || diagnosticNumber(diagnostics, "emittedVertices") <= 0
                || !(diagnostics.get("polygons") instanceof List<?> polygons) || polygons.isEmpty()
                || polygons.size() > 24)
        {
            return false;
        }

        for (Object entry : polygons)
        {
            if (!(entry instanceof Map<?, ?> polygon) || !(polygon.get("vertices") instanceof List<?> vertices)
                    || vertices.size() != 4)
            {
                return false;
            }

            if (vertices.stream().anyMatch(
                    value -> !(value instanceof List<?> point) || point.size() != 2 || point.stream().anyMatch(
                            axis -> !(axis instanceof Number number) || !Double.isFinite(number.doubleValue()))))
            {
                return false;
            }

            double area = 0;
            for (int index = 0; index < 4; index++)
            {
                List<?> point = (List<?>) vertices.get(index);
                List<?> next = (List<?>) vertices.get((index + 1) % 4);
                area += ((Number) point.get(0)).doubleValue() * ((Number) next.get(1)).doubleValue()
                        - ((Number) next.get(0)).doubleValue() * ((Number) point.get(1)).doubleValue();
            }
            if (Math.abs(area) < 1)
            {
                return false;
            }

        }
        if (!(diagnostics.get("actions") instanceof List<?> actions) || actions.isEmpty() || actions.size() > 8)
        {
            return false;
        }

        long drawnActionSlots = polygons.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(row -> "action".equals(row.get("kind"))).map(row -> row.get("slot")).distinct().count();
        return drawnActionSlots == actions.size();
    }

    private static boolean referenceModelScreen(PlayerModelScreen hub, Map<String, Object> diagnostics)
    {
        boolean gallery = Boolean.TRUE.equals(diagnostics.get("galleryLayout"))
                || Boolean.TRUE.equals(diagnostics.get("upstreamGallery"));
        List<ButtonWidget> buttons = hub.children().stream().filter(ButtonWidget.class::isInstance)
                .map(ButtonWidget.class::cast).filter(button -> button.visible).toList();
        boolean sources = buttons.stream()
                .anyMatch(button -> button.getMessage().getString().startsWith("客户端") && button.getWidth() >= 90)
                && buttons.stream().anyMatch(
                        button -> button.getMessage().getString().startsWith("服务器下发") && button.getWidth() >= 90);
        boolean actualGallery = diagnostics.get("gallery") instanceof Map<?, ?> evidence
                && "left-preview/right-search-model-cards".equals(evidence.get("layout"))
                && "0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85".equals(evidence.get("referenceRevision"))
                && evidence.get("leftPreview") instanceof Map<?, ?> preview && Boolean.TRUE.equals(preview.get("drawn"))
                && evidence.get("cards") instanceof List<?> cards && cards.stream()
                        .anyMatch(value -> value instanceof Map<?, ?> card && Boolean.TRUE.equals(card.get("drawn")));
        return gallery && actualGallery && sources && Boolean.FALSE.equals(diagnostics.get("actionGrid"))
                && hub.children().stream().anyMatch(TextFieldWidget.class::isInstance)
                && hub.children().stream().anyMatch(widget -> widget.getClass().getSimpleName().equals("ModelCard"));
    }

    private static boolean interactionPress(MinecraftClient client, Screen screen, Predicate<String> label)
    {
        for (int attempt = 0; attempt < 12; attempt++)
        {
            ButtonWidget button = screen.children().stream().filter(ButtonWidget.class::isInstance)
                    .map(ButtonWidget.class::cast)
                    .filter(widget -> widget.active && label.test(widget.getMessage().getString())).findFirst()
                    .orElse(null);
            if (button == null)
            {
                return false;
            }

            if (button.visible)
            {
                button.onPress(null);
                return true;
            }
            screen.mouseScrolled(client.getWindow().getScaledWidth() / 2d, client.getWindow().getScaledHeight() / 2d, 0,
                    button.getY() > client.getWindow().getScaledHeight() / 2 ? -1 : 1);
        }
        return false;
    }

    private static boolean interactionControlsInBounds(MinecraftClient client, Screen screen)
    {
        int width = client.getWindow().getScaledWidth(), height = client.getWindow().getScaledHeight();
        return screen.children().stream().filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast)
                .filter(widget -> widget.visible)
                .allMatch(widget -> widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0
                        && widget.getY() >= 0 && widget.getRight() <= width && widget.getBottom() <= height);
    }

    private void interactionObservation(MinecraftClient client, String phase, Map<String, Object> diagnostics,
            boolean screenshot) throws Exception
    {
        this.observations.add(Map.of("stage", "interaction-ui", "phase", phase, "stageTick", this.stageTicks,
                "diagnostics", diagnostics, "profile", this.runtime.localAppearance(), "requestPacketsSent",
                this.runtime.requestPacketsSent()));
        if (screenshot)
        {
            saveScreenshot(client, "interaction-" + phase);
        }

    }

    private void prepareInteractionFixture() throws Exception
    {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path directory = this.runtime.localModelDirectory().toAbsolutePath().normalize();
        if (game.getParent() == null
                || !game.getParent().getFileName().toString().equals("meplayeractions-standalone-e2e")
                || !directory.startsWith(game.resolve("config")))

        {
            throw new IllegalStateException(
                    "Interaction fixture requires the disposable standalone clone: " + directory);
        }

        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(game.resolve("config").toRealPath()))
        {
            throw new IllegalStateException("Interaction directory escapes clone");
        }

        JsonObject model = JsonParser.parseString(new String(YsmFolderModel.bundledDefault(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        new ArrayList<>(model.keySet()).stream().filter(key -> key.startsWith("ysm_")).forEach(model::remove);
        JsonArray animations = new JsonArray();
        for (int index = 0; index < 17; index++)
        {
            JsonObject clip = new JsonObject();
            clip.addProperty("name", String.format(Locale.ROOT, "memory_%02d", index));
            clip.addProperty("length", 2);
            clip.addProperty("loop", "loop");
            animations.add(clip);
        }
        model.add("animations", animations);
        byte[] bytes = GSON.toJson(model).getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("UI-Interaction-Memory.bbmodel");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(directory.toRealPath())
                || !Arrays.equals(bytes, Files.readAllBytes(file))))

        {
            throw new IllegalStateException("Refusing to overwrite unrelated interaction fixture: " + file);
        }

        Files.write(file, bytes);
        this.interactionFixture = file;
    }

    private void cleanupInteractions(MinecraftClient client) throws Exception
    {
        this.runtime.stopLocal();
        client.setScreen(null);
        if (this.interactionOriginalProfile != null)
        {
            this.runtime.updateLocalAppearance(this.interactionOriginalProfile);
            this.runtime.updateWheelPreferences(this.interactionOriginalWheel);
            this.runtime.options.localActionLocked = this.interactionOriginalActionLock;
            this.runtime.options.enabled = this.interactionOriginalEnabled;
            this.runtime.options.showSelf = this.interactionOriginalShowSelf;
            this.runtime.options.save();
        }
        if (this.interactionFixture != null)
        {
            Files.deleteIfExists(this.interactionFixture);
        }

        check("standaloneInteractionCleanup",
                this.interactionOriginalProfile != null
                        && this.interactionOriginalProfile.equals(this.runtime.localAppearance())
                        && this.interactionOriginalWheel.equals(this.runtime.wheelPreferences())
                        && this.runtime.options.localActionLocked == this.interactionOriginalActionLock
                        && this.runtime.options.enabled == this.interactionOriginalEnabled
                        && this.runtime.options.showSelf == this.interactionOriginalShowSelf
                        && (this.interactionFixture == null || !Files.exists(this.interactionFixture))
                        && this.runtime.requestPacketsSent() == this.requestBaseline,
                "Original saved profile/options restored and only the disposable memory fixture removed");
    }

    private Map<String, Object> interactionDelivery()
    {
        List<Map<String, Object>> relevant = this.checks.stream()
                .filter(row -> INTERACTION_REQUIRED.contains(row.get("name"))).toList();
        boolean passed = this.interactionCompleted
                && INTERACTION_REQUIRED.stream()
                        .allMatch(name -> relevant.stream().anyMatch(
                                row -> name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("passed"))))
                && relevant.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        return Map.of("scope", "interactions", "passed", passed, "requiredChecks", INTERACTION_REQUIRED, "checks",
                relevant, "observations", this.observations, "wineFoxModels", List.copyOf(this.wineFoxModels));
    }

    private static String uuidNbt(UUID uuid)
    {
        long most = uuid.getMostSignificantBits(), least = uuid.getLeastSignificantBits();
        return "[I;" + (int) (most >>> 32) + "," + (int) most + "," + (int) (least >>> 32) + "," + (int) least + "]";
    }

    private void beginComponentFixture(MinecraftClient client, String name)
    {
        this.componentEntityUuid = UUID.randomUUID();
        this.componentFixtureUuids.add(this.componentEntityUuid);
        if (this.componentOriginalPosition == null)
        {
            this.componentOriginalPosition = client.player.getEntityPos();
        }

        Map<?, ?> components = (Map<?, ?>) ModelRenderer.diagnostics().get("components");
        this.componentSubmissionsBaseline = ((Number) components.get("submittedComponents")).longValue();
        this.componentVerticesBaseline = ((Number) components.get("emittedComponentVertices")).longValue();
        this.componentProof = Map.of();
        client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        boolean vehicle = name.equals("vehicle-component");
        Vec3d position = this.componentOriginalPosition.add(vehicle ? 0 : 1.6, vehicle ? .1 : 1, vehicle ? 0 : 1.5);
        String coordinates = String.format(Locale.ROOT, "%.3f %.3f %.3f", position.x, position.y, position.z);
        String nbt = "{UUID:" + uuidNbt(this.componentEntityUuid) + ",Tags:[\"" + this.componentFixtureTag
                + "\"],Invulnerable:1b"
                + (vehicle ? "" : ",Owner:" + uuidNbt(client.player.getUuid()) + ",NoGravity:1b") + "}";
        String command = "summon minecraft:" + (vehicle ? "oak_boat" : "arrow") + " " + coordinates + " " + nbt;
        if (command.length() > 256)
        {
            throw new IllegalStateException("Component fixture command exceeds Minecraft's 256-character limit");
        }

        client.getNetworkHandler().sendChatCommand(command);
        if (vehicle)
        {
            client.getNetworkHandler().sendChatCommand(
                    "ride MPATest mount @e[type=minecraft:oak_boat,tag=" + this.componentFixtureTag + ",limit=1]");
        }

    }

    private void cleanupComponentFixtures(MinecraftClient client)
    {
        if (client.getNetworkHandler() == null || client.player == null)
        {
            return;
        }

        if (client.player.hasVehicle())
        {
            client.getNetworkHandler().sendChatCommand("ride MPATest dismount");
        }

        client.getNetworkHandler().sendChatCommand("kill @e[tag=" + this.componentFixtureTag + "]");
        if (this.componentOriginalPosition != null)
        {
            client.getNetworkHandler()
                    .sendChatCommand(String.format(Locale.ROOT, "tp MPATest %.6f %.6f %.6f 0 0",
                            this.componentOriginalPosition.x, this.componentOriginalPosition.y,
                            this.componentOriginalPosition.z));
        }

    }

    private Map<String, Object> componentProof(MinecraftClient client, String name)
    {
        String kind = name.equals("vehicle-component") ? "vehicle" : "projectile";
        Entity entity = null;
        for (Entity candidate : client.world.getEntities())
        {
            if (candidate.getUuid().equals(this.componentEntityUuid))
            {
                entity = candidate;
                break;
            }
        }

        boolean nativeOwner = entity != null
                && (kind.equals("vehicle") ? client.player.getVehicle() == entity && entity.hasPassenger(client.player)
                        : entity instanceof ProjectileEntity projectile && projectile.getOwner() == client.player);
        ClientRuntime.RenderBinding binding = own(client);
        Map<?, ?> components = (Map<?, ?>) ModelRenderer.diagnostics().get("components");
        Map<?, ?> draw = ((List<?>) components.get("submittedComponentDraws")).stream().filter(Map.class::isInstance)
                .map(Map.class::cast).filter(row -> this.componentEntityUuid.toString().equals(row.get("entity")))
                .findFirst().orElse(Map.of());
        YsmModelProfile.Component source = this.runtime.localModelProfile().components().stream()
                .filter(value -> value.kind().equals(kind) && value.hash().equals(draw.get("hash"))).findFirst()
                .orElse(null);
        String expectedSource = kind.equals("vehicle") ? "models/boat.json" : "models/arrow.json";
        boolean authorAsset = source != null && source.metadata().has("model")
                && expectedSource.equals(source.metadata().get("model").getAsString());
        boolean current = nativeOwner && binding != null && binding.instance().startsWith("local-self:")
                && binding.assetHash().equals(this.modelHash) && PROFILE.equals(this.runtime.localAppearance())
                && client.player.getUuid().toString().equals(draw.get("owner"))
                && binding.instance().equals(draw.get("instance")) && kind.equals(draw.get("kind")) && authorAsset;
        boolean emitted = !draw.isEmpty() && diagnosticNumber(draw, "emittedVertices") > 0
                && diagnosticNumber(draw, "vertices") > 0
                && ((Number) components.get("submittedComponents")).longValue() > this.componentSubmissionsBaseline
                && ((Number) components.get("emittedComponentVertices")).longValue() > this.componentVerticesBaseline
                && ((List<?>) components.get("activeReplacements")).contains(this.componentEntityUuid.toString());
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("passed", current && emitted);
        proof.put("nativeOwnerObserved", nativeOwner);
        proof.put("currentBindingAndAuthorAsset", current);
        proof.put("actualNativeEntityUuid", entity == null ? "" : entity.getUuidAsString());
        proof.put("nativeType", entity == null ? "" : Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        proof.put("nativeEntityId", entity == null ? -1 : entity.getId());
        proof.put("expectedUuid", this.componentEntityUuid.toString());
        proof.put("originalSource", expectedSource);
        proof.put("nativeEntityDraw", draw);
        proof.put("actualVerticesEmitted", emitted);
        proof.put("sourceComponentMetadata", source == null ? Map.of() : source.metadata());
        proof.put("renderer", components);
        return Map.copyOf(proof);
    }

    private void savedProfileTick() throws Exception
    {
        if (!this.savedDisabledReload && this.stageTicks >= 5)
        {
            JsonObject document = JsonParser.parseString(this.savedJson).getAsJsonObject();
            document.add("localAppearance", GSON.toJsonTree(new LocalAppearanceSettings(false, PROFILE.modelId(),
                    PROFILE.scale(), PROFILE.offsetX(), PROFILE.offsetY(), PROFILE.offsetZ())));
            Files.writeString(this.runtime.localAppearanceSettingsPath(), GSON.toJson(document),
                    StandardCharsets.UTF_8);
            this.runtime.reloadLocalAppearanceSettings();
            this.savedDisabledReload = true;
            check("standaloneSavedDisableReloaded",
                    !this.runtime.localAppearance().enabled() && this.runtime.renderBindings().stream()
                            .noneMatch(binding -> binding.instance().startsWith("local-self:")),
                    "External clone JSON disable is actually read, without using updateLocalAppearance");
        }
        if (this.savedDisabledReload && !this.savedEnabledReload && this.stageTicks >= 15)
        {
            Files.writeString(this.runtime.localAppearanceSettingsPath(), this.savedJson, StandardCharsets.UTF_8);
            this.runtime.reloadLocalAppearanceSettings();
            this.savedEnabledReload = true;
            check("standaloneSavedProfileReloaded", PROFILE.equals(this.runtime.localAppearance()),
                    "Reloaded model/scale/XYZ=" + this.runtime.localAppearance());
        }
    }

    private ClientRuntime.RenderBinding own(MinecraftClient client)
    {
        return this.runtime.renderBindings().stream().filter(binding -> binding.owner().equals(client.player.getUuid()))
                .findFirst().orElse(null);
    }

    private void prepareGalleryFixtures() throws Exception
    {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path directory = this.runtime.localModelDirectory().toAbsolutePath().normalize();
        // Copies exist only in the launcher's disposable standalone clone, never in a user's model library.
        if (game.getParent() == null
                || !game.getParent().getFileName().toString().equals("meplayeractions-standalone-e2e")
                || !directory.startsWith(game.resolve("config")))

        {
            throw new IllegalStateException(
                    "Gallery fixture requires the isolated standalone game/config directory: " + directory);
        }

        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(game.resolve("config").toRealPath()))

        {
            throw new IllegalStateException("Gallery fixture directory resolves outside the standalone clone");
        }

        byte[] raw = YsmFolderModel.bundledDefault();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        List<String> files = new ArrayList<>();
        for (int index = 1; index <= 12; index++)
        {
            Path file = directory.resolve(String.format("UI-Test-%02d.bbmodel", index));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(directory.toRealPath())
                    || !Arrays.equals(raw, Files.readAllBytes(file))))

            {
                throw new IllegalStateException("Refusing to overwrite an unrelated gallery fixture: " + file);
            }

            Files.write(file, raw);
            files.add(file.toString());
        }
        this.galleryFixture = Map.of("scope", "disposable standalone clone only", "source",
                "actual bundled OpenYSM model converted by YsmFolderModel", "sha256", hash, "files", files);
        check("standaloneGalleryFixturesAvailable", this.runtime.localModels().stream()
                .filter(model -> model.id().startsWith("local:UI-Test-")).count() == 12,
                this.galleryFixture.toString());
    }

    private static byte[] builtinResource(String relative) throws Exception
    {
        try (InputStream input = LocalAppearanceHarness.class
                .getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/" + relative))
        {
            if (input == null)
            {
                throw new IllegalStateException("Missing real bundled preview source: " + relative);
            }

            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024)
            {
                throw new IllegalStateException("Bundled preview source size: " + relative);
            }

            return bytes;
        }
    }

    private void readBuiltinPreviewEvidence() throws Exception
    {
        byte[] geometryBytes = builtinResource("models/main.json"),
                animationBytes = builtinResource("animations/main.animation.json");
        byte[] manifestBytes = builtinResource("ysm.json"), textureBytes = builtinResource("textures/default.png");
        JsonObject geometry = JsonParser.parseString(new String(geometryBytes, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject animations = JsonParser.parseString(new String(animationBytes, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("animations");
        JsonObject manifest = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<Map<String, Object>> whiteEyeFaces = new ArrayList<>();
        List<String> eyeBones = new ArrayList<>();
        for (JsonElement value : geometry.getAsJsonArray("bones"))
        {
            JsonObject bone = value.getAsJsonObject();
            String name = bone.get("name").getAsString();
            if (List.of("RightEyelid", "LeftEyelid", "RightEyesBase", "LeftEyesBase", "RightPupil", "LeftPupil")
                    .contains(name) && bone.has("cubes") && !bone.getAsJsonArray("cubes").isEmpty())
            {
                eyeBones.add(name);
            }

            if (!List.of("RightEyelid", "LeftEyelid").contains(name))
            {
                continue;
            }

            JsonObject face = bone.getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv")
                    .getAsJsonObject("north");
            JsonArray uv = face.getAsJsonArray("uv");
            JsonArray size = face.getAsJsonArray("uv_size");
            double u = uv.get(0).getAsDouble(), v = uv.get(1).getAsDouble();
            whiteEyeFaces.add(Map.of("bone", name, "sourcePixelBounds",
                    List.of(u, v, u + size.get(0).getAsDouble(), v + size.get(1).getAsDouble())));
        }
        boolean sourceBlink = animations.has("pre_parallel1");
        if (sourceBlink)
        {
            for (String side : List.of("RightEyelid", "LeftEyelid"))
            {
                JsonObject scales = animations.getAsJsonObject("pre_parallel1").getAsJsonObject("bones")
                        .getAsJsonObject(side).getAsJsonObject("scale");
                sourceBlink &= scales.getAsJsonObject("0.0").getAsJsonArray("post").get(1).getAsDouble() == 1
                        && scales.getAsJsonObject("0.0833").getAsJsonArray("post").get(1).getAsDouble() == 0
                        && scales.getAsJsonObject("0.2917").getAsJsonArray("post").get(1).getAsDouble() == 1;
            }
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(textureBytes));
        boolean whitePixels = image != null && image.getWidth() == 128 && image.getHeight() == 128;
        List<String> actualEyePixels = new ArrayList<>();
        if (whitePixels)
        {
            for (int y = 42; y < 44; y++)
            {
                for (int x = 23; x < 25; x++)
                {
                    int color = image.getRGB(x, y);
                    actualEyePixels.add(Integer.toHexString(color));
                    whitePixels &= (color >>> 24) == 255 && (color >> 16 & 255) >= 240 && (color >> 8 & 255) >= 240
                            && (color & 255) >= 240;
                }
            }

        }

        boolean sensitiveEdges = image != null && (image.getRGB(25, 42) >>> 24) == 0
                && image.getRGB(23, 44) != image.getRGB(23, 43);
        BbModel converted = BbModel.parse(YsmFolderModel.bundledDefault(this.runtime.options.defaultBlueTexture));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source",
                "Actual loaded mod JAR's OpenYSM manifest, original geometry/animation/texture; not preview diagnostic labels");
        evidence.put("geometrySha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geometryBytes)));
        evidence.put("animationSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(animationBytes)));
        evidence.put("textureSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(textureBytes)));
        evidence.put("previewAnimation", manifest.getAsJsonObject("properties").get("preview_animation").getAsString());
        JsonObject properties = manifest.getAsJsonObject("properties");
        evidence.put("disablePreviewRotation", properties.get("disable_preview_rotation").getAsBoolean());
        evidence.put("guiNoLighting", properties.get("gui_no_lighting").getAsBoolean());
        Map<String, Object> decorations = new LinkedHashMap<>();
        for (String role : List.of("background", "foreground"))
        {
            String path = properties.get("gui_" + role).getAsString();
            byte[] png = builtinResource(path);
            BufferedImage decoration = ImageIO.read(new ByteArrayInputStream(png));
            decorations.put(role, Map.of("path", path, "width", decoration.getWidth(), "height", decoration.getHeight(),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))));
        }
        evidence.put("decorations", Map.copyOf(decorations));
        evidence.put("authoredEnterClipPresent", animations.has("enter"));
        evidence.put("eyeBones", eyeBones);
        evidence.put("whiteEyeFaces", whiteEyeFaces);
        evidence.put("textureWidth", geometry.getAsJsonObject("description").get("texture_width").getAsInt());
        evidence.put("textureHeight", geometry.getAsJsonObject("description").get("texture_height").getAsInt());
        evidence.put("whitePixelArgb", actualEyePixels);
        evidence.put("transparentRightNeighbour", image == null ? -1 : image.getRGB(25, 42) >>> 24);
        evidence.put("lowerNeighbourArgb", image == null ? "missing" : Integer.toHexString(image.getRGB(23, 44)));
        evidence.put("naturalBlinkOpensAndCloses", sourceBlink);
        evidence.put("convertedAnimations", converted.animations());
        this.builtinPreviewAsset = evidence;
        check("standaloneBuiltinPreviewSourceEvidence",
                "gui".equals(evidence.get("previewAnimation")) && !animations.has("enter")
                        && converted.animations().containsAll(List.of("gui", "idle", "pre_parallel1")) && sourceBlink
                        && eyeBones.size() == 6 && whiteEyeFaces.size() == 2 && whitePixels && sensitiveEdges,
                "Real source has gui + idle and a natural 4-second blink; OWNER native layers and CARD cap use independent source clocks. Eye white UV must not sample neighbouring transparency/skin: "
                        + evidence);
    }

    private static double diagnosticNumber(Map<?, ?> row, String key)
    {
        return row.get(key) instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private static List<Map<?, ?>> defaultPreviewDraws(Map<String, Object> diagnostics)
    {
        if (!(diagnostics.get("draws") instanceof List<?> draws))
        {
            return List.of();
        }

        return draws.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value)
                .filter(draw -> String.valueOf(draw.get("key")).startsWith(PROFILE.modelId() + ":")).toList();
    }

    private static double viewportArea(Map<?, ?> draw)
    {
        return diagnosticNumber(draw, "width") * diagnosticNumber(draw, "height");
    }

    private static Map<?, ?> mainPreviewDraw(List<Map<?, ?>> draws)
    {
        return draws.stream().filter(draw -> "OWNER".equals(draw.get("context"))).findFirst().orElseGet(() -> draws
                .stream().max(Comparator.comparingDouble(LocalAppearanceHarness::viewportArea)).orElse(Map.of()));
    }

    private static List<Double> diagnosticVector(Object value, int size)
    {
        if (!(value instanceof List<?> vector) || vector.size() != size
                || vector.stream().anyMatch(item -> !(item instanceof Number)))
        {
            return List.of();
        }

        return vector.stream().map(item -> ((Number) item).doubleValue()).toList();
    }

    private Map<String, Object> whiteEyeProof(Map<?, ?> draw)
    {
        int visible = 0;
        double maximumUvError = 0;
        List<Double> centers = new ArrayList<>(), areas = new ArrayList<>();
        boolean mappingValid = true;
        if (draw.get("faces") instanceof List<?> faces)
        {
            for (Object value : faces)
            {
                if (!(value instanceof Map<?, ?> face) || !(face.get("sourceUv") instanceof List<?> sourceUvs)
                        || !(face.get("atlasUv") instanceof List<?> atlasUvs)
                        || !(face.get("points") instanceof List<?> points) || sourceUvs.size() != 4
                        || atlasUvs.size() != 4 || points.size() != 4)
                {
                    mappingValid = false;
                    continue;
                }
                List<Double> atlas = diagnosticVector(face.get("atlas"), 6);
                if (atlas.isEmpty() || atlas.get(2) != 128 || atlas.get(3) != 128 || atlas.get(4) <= 0
                        || atlas.get(5) <= 0)
                {
                    mappingValid = false;
                    continue;
                }
                double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
                double maxU = Double.NEGATIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
                List<List<Double>> projected = new ArrayList<>();
                for (int corner = 0; corner < 4; corner++)
                {
                    List<Double> source = diagnosticVector(sourceUvs.get(corner), 2),
                            mapped = diagnosticVector(atlasUvs.get(corner), 2);
                    List<Double> point = diagnosticVector(points.get(corner), 2);
                    if (source.isEmpty() || mapped.isEmpty() || point.isEmpty())
                    {
                        mappingValid = false;
                        continue;
                    }
                    double pixelU = source.get(0) * atlas.get(2), pixelV = source.get(1) * atlas.get(3);
                    minU = Math.min(minU, pixelU);
                    maxU = Math.max(maxU, pixelU);
                    minV = Math.min(minV, pixelV);
                    maxV = Math.max(maxV, pixelV);
                    double errorU = Math.abs(mapped.get(0) * atlas.get(4) - atlas.get(0) - pixelU);
                    double errorV = Math.abs(mapped.get(1) * atlas.get(5) - atlas.get(1) - pixelV);
                    maximumUvError = Math.max(maximumUvError, Math.max(errorU, errorV));
                    mappingValid &= Double.isFinite(errorU) && Double.isFinite(errorV) && errorU < .001
                            && errorV < .001;
                    projected.add(point);
                }
                // The authored two front white-eye faces both occupy exactly this source rectangle.
                // Comparing numeric consumer atlas UVs catches the old half-pixel/width-1 shrink.
                boolean authoredWhite = Math.abs(minU - 23) < .001 && Math.abs(maxU - 25) < .001
                        && Math.abs(minV - 42) < .001 && Math.abs(maxV - 44) < .001;
                if (!authoredWhite || projected.size() != 4)
                {
                    continue;
                }

                double twiceArea = 0;
                for (int corner = 0; corner < 4; corner++)
                {
                    List<Double> a = projected.get(corner);
                    List<Double> b = projected.get((corner + 1) % 4);
                    twiceArea += a.get(0) * b.get(1) - b.get(0) * a.get(1);
                }
                double area = Math.abs(twiceArea) * .5;
                if (Double.isFinite(area) && area > .001)
                {
                    visible++;
                    areas.add(area);
                    centers.add(projected.stream().mapToDouble(point -> point.get(0)).average().orElse(0));
                }
            }
        }

        boolean bothEyes = visible == 2 && centers.size() == 2 && Math.abs(centers.get(0) - centers.get(1)) > .1;
        return Map.of("numericAtlasMappingValid", mappingValid, "maximumSourcePixelError", maximumUvError,
                "visibleWhiteEyeFrontQuads", visible, "bothEyesSeparated", bothEyes, "projectedAreas", areas,
                "centersX", centers);
    }

    private Map<String, Object> authorPreviewProof(Map<?, ?> draw)
    {
        boolean owner = "OWNER".equals(draw.get("context"));
        Map<String, Object> source;
        try
        {
            source = guiPreviewSource(PROFILE.modelId());
        }
        catch (Exception failure)
        {
            return Map.of("fixedAuthorCamera", false, "sourceFlagsMatch", false, "actualBackgroundModelForegroundOrder",
                    false, "error", failure.toString());
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        boolean fixedCamera = owner
                ? player != null && ownerNativeContext(draw, String.valueOf(source.get("animation")),
                        player.getHeight(), player.getScale())
                : cardNativeContext(draw, source);
        boolean flags = Boolean.FALSE.equals(this.builtinPreviewAsset.get("guiNoLighting"))
                && Boolean.FALSE.equals(draw.get("noLighting"));
        boolean layers = diagnosticNumber(draw, "entryProgress") >= .999
                && diagnosticNumber(draw, "decorationQuads") == (owner ? 0 : 2)
                && draw.get("decorations") instanceof List<?> rows && rows.size() == (owner ? 0 : 2);
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (draw.get("decorations") instanceof List<?> rows)
        {
            for (Object value : rows)
            {
                if (!(value instanceof Map<?, ?> decoration))
                {
                    layers = false;
                    continue;
                }
                String role = String.valueOf(decoration.get("role"));
                Map<?, ?> decorationSource = ((Map<?, ?>) this.builtinPreviewAsset.getOrDefault("decorations",
                        Map.of())).get(role) instanceof Map<?, ?> found ? found : Map.of();
                List<Double> atlas = diagnosticVector(decoration.get("atlas"), 6);
                boolean valid = decorationSource.get("path") != null
                        && decorationSource.get("path").equals(draw.get(role)) && atlas.size() == 6
                        && atlas.get(2) == ((Number) decorationSource.get("width")).doubleValue()
                        && atlas.get(3) == ((Number) decorationSource.get("height")).doubleValue() && atlas.get(2) == 52
                        && atlas.get(3) == 90 && diagnosticNumber(decoration, "color") == -1
                        && diagnosticNumber(decoration,
                                "order") == (role.equals("background") ? 0 : diagnosticNumber(draw, "quads") - 1);
                List<List<Double>> corners = List.of(List.of(0d, 0d), List.of(0d, 1d), List.of(1d, 1d),
                        List.of(1d, 0d));
                double error = 0;
                if (decoration.get("atlasUv") instanceof List<?> uvs && uvs.size() == 4
                        && decoration.get("points") instanceof List<?> points && points.size() == 4
                        && atlas.size() == 6)
                {
                    for (int i = 0; i < 4; i++)
                    {
                        List<Double> uv = diagnosticVector(uvs.get(i), 2), point = diagnosticVector(points.get(i), 2);
                        if (uv.isEmpty() || point.isEmpty())
                        {
                            valid = false;
                            continue;
                        }
                        error = Math.max(error, Math
                                .abs(uv.get(0) * atlas.get(4) - atlas.get(0) - corners.get(i).get(0) * atlas.get(2)));
                        error = Math.max(error, Math
                                .abs(uv.get(1) * atlas.get(5) - atlas.get(1) - corners.get(i).get(1) * atlas.get(3)));
                        valid &= Math
                                .abs(point.get(0) - diagnosticNumber(draw, "x")
                                        - corners.get(i).get(0) * diagnosticNumber(draw, "width")) < .001
                                && Math.abs(point.get(1) - diagnosticNumber(draw, "y")
                                        - corners.get(i).get(1) * diagnosticNumber(draw, "height")) < .001;
                    }
                    valid &= error < .001;
                }
                else
                {
                    valid = false;
                }

                layers &= valid;
                evidence.add(Map.of("role", role, "valid", valid, "sourcePixelError", error));
            }
        }

        boolean shaded = draw.get("faces") instanceof List<?> faces && faces.stream().filter(Map.class::isInstance)
                .map(Map.class::cast).anyMatch(face -> face.get("color") instanceof Number color
                        && (color.intValue() >>> 24) == 255 && (color.intValue() >> 16 & 255) < 255);
        return Map.of("fixedAuthorCamera", fixedCamera, "sourceFlagsMatch", flags && shaded,
                "actualBackgroundModelForegroundOrder", layers, "context", String.valueOf(draw.get("context")),
                "decorations", evidence, "actualModelSoftShading", shaded);
    }

    private boolean dragPreview(Screen screen, Map<?, ?> draw)
    {
        double x = diagnosticNumber(draw, "x") + diagnosticNumber(draw, "width") * .5;
        double y = diagnosticNumber(draw, "y") + diagnosticNumber(draw, "height") * .5;
        Click click = new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        boolean clicked = screen.mouseClicked(click, false);
        screen.mouseDragged(click, 40, 25);
        screen.mouseReleased(click);
        return clicked;
    }

    private boolean currentPreviewEyes(Map<String, Object> diagnostics)
    {
        List<Map<?, ?>> draws = defaultPreviewDraws(diagnostics);
        if (draws.size() < 2 || !previewRendered(diagnostics))
        {
            return false;
        }

        Map<?, ?> main = mainPreviewDraw(draws);
        return diagnosticNumber(main, "entryProgress") >= .999
                && Boolean.TRUE.equals(whiteEyeProof(main).get("bothEyesSeparated"))
                && Boolean.TRUE.equals(whiteEyeProof(main).get("numericAtlasMappingValid"))
                && draws.stream().filter(draw -> draw != main)
                        .anyMatch(draw -> Boolean.TRUE.equals(whiteEyeProof(draw).get("bothEyesSeparated"))
                                && Boolean.TRUE.equals(whiteEyeProof(draw).get("numericAtlasMappingValid")));
    }

    private boolean singlePreviewEyes(Map<String, Object> diagnostics)
    {
        Map<?, ?> main = mainPreviewDraw(defaultPreviewDraws(diagnostics));
        Map<String, Object> eyes = whiteEyeProof(main);
        return previewRendered(diagnostics) && diagnosticNumber(main, "entryProgress") >= .999
                && Boolean.TRUE.equals(eyes.get("bothEyesSeparated"))
                && Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid"));
    }

    private void previewRegressionTick(MinecraftClient client)
    {
        if (!(client.currentScreen instanceof LocalAppearanceScreen gallery))
        {
            return;
        }

        Map<String, Object> diagnostics = gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128, 44f / 128);
        List<Map<?, ?>> draws = defaultPreviewDraws(diagnostics);
        if (draws.isEmpty() || !previewRendered(diagnostics))
        {
            return;
        }

        Map<?, ?> main = mainPreviewDraw(draws);
        double age = diagnosticNumber(main, "sampleTick"), start = diagnosticNumber(main, "startedAtTick");
        double progress = diagnosticNumber(main, "entryProgress");
        if (Double.isNaN(this.previewFirstStart))
        {
            this.previewFirstStart = start;
        }

        this.previewClockValid &= Double.isFinite(age) && Double.isFinite(start) && age >= 0
                && age + .001 >= this.previewPreviousAge && Math.abs(start - this.previewFirstStart) < .001
                && diagnosticNumber(main, "startCount") == 1;
        this.previewPreviousAge = age;
        this.previewMaximumAge = Math.max(this.previewMaximumAge, age);
        this.previewMainSamples++;
        this.previewEntrySeen |= progress > 0 && progress < 1;
        this.previewEntryCompleted |= progress >= .999 && age >= 12;
        if (main.get("faces") instanceof List<?> faces)
        {
            for (Object value : faces)
            {
                if (value instanceof Map<?, ?> face && face.get("color") instanceof Number color)
                {
                    double bounded = Math.clamp(progress, 0d, 1d);
                    int expectedAlpha = Boolean.TRUE.equals(main.get("nativeCamera")) ? 255
                            : (int) Math.round(255 * bounded * bounded * (3 - 2 * bounded));
                    this.previewEntryAlphaValid &= Math.abs((color.intValue() >>> 24) - expectedAlpha) <= 1;
                }
            }

        }

        this.previewLayersValid &= main.get("layers") instanceof List<?> layers && !layers.contains("gui")
                && main.get("controllerSlots") instanceof List<?> slots && !slots.isEmpty()
                && !slots.contains("player.cap");
        Map<String, Object> mainEyes = whiteEyeProof(main);
        this.previewAtlasValid &= Boolean.TRUE.equals(mainEyes.get("numericAtlasMappingValid"));
        boolean mature = progress >= .999 && age >= 12;
        if (!this.galleryDragAttempted && mature && age >= 18)
        {
            this.galleryDragAttempted = dragPreview(gallery, main);
            uiEvent("drag-author-fixed-preview",
                    Map.of("clicked", this.galleryDragAttempted, "requestedDx", 40, "requestedDy", 25));
        }
        if (mature)
        {
            this.previewMatureSamples++;
            this.previewMainEyesSeen |= Boolean.TRUE.equals(mainEyes.get("bothEyesSeparated"));
        }
        if (this.previewSameSelectionRequested)
        {
            this.previewAfterSameSelectionSamples++;
        }

        if (!this.previewSameSelectionRequested && mature && age >= 24)
        {
            String label = this.runtime.localModels().stream().filter(model -> model.id().equals(PROFILE.modelId()))
                    .map(ClientRuntime.Action::label).findFirst().orElse("");
            boolean pressed = pressButton(gallery, label);
            check("standaloneGallerySameSelectionCallback",
                    pressed && gallery.selectedModelId().equals(PROFILE.modelId())
                            && PROFILE.equals(this.runtime.localAppearance()),
                    "Click the real already-selected default model card once; this must preserve its preview clock and the private world profile");
            uiEvent("select-same-default-card", Map.of("sampleTickBefore", age, "startedAtTickBefore", start));
            this.previewSameSelectionRequested = true;
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<?, ?> card : draws)
        {
            if (card == main)
            {
                continue;
            }

            this.previewCardSamples++;
            double cardAge = diagnosticNumber(card, "sampleTick"), cardStart = diagnosticNumber(card, "startedAtTick");
            if (Double.isNaN(this.previewCardFirstStart))
            {
                this.previewCardFirstStart = cardStart;
            }

            this.previewSharedClockValid &= "CARD".equals(card.get("context")) && Double.isFinite(cardAge)
                    && cardAge + .001 >= this.previewCardPreviousAge
                    && Math.abs(cardStart - this.previewCardFirstStart) < .001
                    && diagnosticNumber(card, "startCount") == 1;
            this.previewCardPreviousAge = cardAge;
            this.previewLayersValid &= List.of("gui").equals(card.get("layers"))
                    && List.of("player.cap").equals(card.get("controllerSlots"));
            Map<String, Object> eyes = whiteEyeProof(card);
            this.previewAtlasValid &= Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid"));
            if (diagnosticNumber(card, "entryProgress") >= .999 && cardAge >= 12)
            {
                this.previewCardEyesSeen |= Boolean.TRUE.equals(eyes.get("bothEyesSeparated"));
            }

            cards.add(Map.of("draw", card, "eyeEvidence", eyes));
        }
        if (this.previewRegressionSamples.size() < 100)
        {
            this.previewRegressionSamples.add(Map.of("stageTick", this.stageTicks, "emittedVertices",
                    diagnostics.get("emittedVertices"), "main", main, "mainEyeEvidence", mainEyes, "cards", cards));
        }

        if (!this.previewEntryScreenshot && progress >= .6 && progress <= .9)
        {
            saveScreenshot(client, "settings-ui-entry");
            this.previewEntryScreenshot = true;
        }
        this.previewRegressionVerified = this.previewEntrySeen && this.previewEntryCompleted && this.previewClockValid
                && this.previewSharedClockValid && this.previewEntryAlphaValid && this.previewLayersValid
                && this.previewAtlasValid && this.previewMainEyesSeen && this.previewCardEyesSeen
                && this.previewEntryScreenshot && this.previewMainSamples >= 25 && this.previewCardSamples >= 15
                && this.previewMatureSamples >= 12 && this.previewMaximumAge >= 24 && this.previewSameSelectionRequested
                && this.previewAfterSameSelectionSamples >= 6;
    }

    private void saveScreenshot(MinecraftClient client, String name)
    {
        String filename = String.format("%02d-%s.png", this.stage + 1, name);
        this.screenshots.add("screenshots/" + filename);
        client.inGameHud.getChatHud().clear(false);
        ScreenshotRecorder.saveScreenshot(this.output.toFile(), filename, client.getFramebuffer(), 1, message ->
        {
            client.execute(() -> this.completedScreenshots.add("screenshots/" + filename));
            LOG.info("Standalone screenshot {}", filename);
        });
    }

    private void uiEvent(String action, Object detail)
    {
        this.uiEvents.add(Map.of("stage", this.stages.get(this.stage), "stageTick", this.stageTicks, "action", action,
                "detail", detail));
    }

    private boolean draftDidNotApply(MinecraftClient client)
    {
        ClientRuntime.RenderBinding binding = own(client);
        return PROFILE.equals(this.runtime.localAppearance()) && binding != null
                && binding.instance().equals(this.galleryBindingInstance);
    }

    private void galleryUiTick(MinecraftClient client)
    {
        if (!(client.currentScreen instanceof LocalAppearanceScreen screen))
        {
            return;
        }

        if (this.uiStartedAt < 0)
        {
            if (!previewRendered(screen.previewDiagnostics()))
            {
                return;
            }

            this.uiStartedAt = this.stageTicks;
            this.galleryFirstPage = List.copyOf(screen.visibleModelIds());
        }
        int age = this.stageTicks - this.uiStartedAt;
        if (age == 5)
        {
            screen.setSearchQuery("UI-Test-01");
            String label = this.runtime.localModels().stream().filter(model -> model.id().equals(GALLERY_DRAFT_ID))
                    .map(ClientRuntime.Action::label).findFirst().orElse("");
            boolean pressed = pressButton(screen, label);
            check("standaloneGallerySearchAndDraft",
                    pressed && screen.visibleModelIds().equals(List.of(GALLERY_DRAFT_ID))
                            && screen.selectedModelId().equals(GALLERY_DRAFT_ID) && draftDidNotApply(client),
                    "Real search field change and model-card callback; selected=" + screen.selectedModelId()
                            + "; visible=" + screen.visibleModelIds() + "; active=" + this.runtime.localAppearance()
                            + "; original instance=" + this.galleryBindingInstance);
            uiEvent("search-card-draft", Map.of("selected", screen.selectedModelId(), "visible",
                    screen.visibleModelIds(), "active", this.runtime.localAppearance()));
        }
        if (age == 10)
        {
            if (this.runtime.options.isFavorite(GALLERY_DRAFT_ID))
            {
                screen.toggleSelectedFavorite();
            }

            screen.toggleSelectedFavorite();
            screen.setFavoritesOnly(true);
            check("standaloneGalleryFavoriteFilter",
                    this.runtime.options.isFavorite(GALLERY_DRAFT_ID)
                            && screen.visibleModelIds().equals(List.of(GALLERY_DRAFT_ID)) && draftDidNotApply(client),
                    "Actual persisted favorite callback and filtered grid=" + screen.visibleModelIds());
            uiEvent("favorite-filter", screen.visibleModelIds());
        }
        if (age == 18)
        {
            screen.setSearchQuery("");
            check("standaloneGalleryFavoriteSearchRebuild",
                    !screen.visibleModelIds().isEmpty()
                            && screen.visibleModelIds().stream().allMatch(this.runtime.options::isFavorite)
                            && draftDidNotApply(client),
                    "Removing the actual search text rebuilds the favorite-only grid=" + screen.visibleModelIds());
        }
        if (age == 24)
        {
            screen.setFavoritesOnly(false);
            screen.goToPage(0);
            this.galleryFirstPage = List.copyOf(screen.visibleModelIds());
            boolean pressed = pressButton(screen, "下一页");
            check("standaloneGalleryPagination",
                    pressed && screen.pageCount() > 1 && screen.pageIndex() == 1 && !screen.visibleModelIds().isEmpty()
                            && !screen.visibleModelIds().equals(this.galleryFirstPage) && draftDidNotApply(client),
                    "Actual next-page button rebuilt cards; first=" + this.galleryFirstPage + "; next="
                            + screen.visibleModelIds() + "; page=" + screen.pageIndex() + "/" + screen.pageCount());
            uiEvent("next-page", Map.of("page", screen.pageIndex(), "visible", screen.visibleModelIds()));
        }
        if (age == 30)
        {
            boolean previous = pressButton(screen, "上一页");
            check("standaloneGalleryPreviousPage",
                    previous && screen.pageIndex() == 0 && screen.visibleModelIds().equals(this.galleryFirstPage),
                    "Actual previous-page button restores initial card identities=" + screen.visibleModelIds());
            screen.setSearchQuery("UI-Test-01");
            screen.browseModel(GALLERY_DRAFT_ID);
            if (this.runtime.options.isFavorite(GALLERY_DRAFT_ID) != this.originalFavorite)
            {
                screen.toggleSelectedFavorite();
            }

            check("standaloneGalleryDraftNeverApplied",
                    screen.selectedModelId().equals(GALLERY_DRAFT_ID) && draftDidNotApply(client)
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Different selected draft ID remains GUI-only through search/filter/page rebuilds; active="
                            + this.runtime.localAppearance());
            this.galleryVerified = true;
        }
    }

    private static boolean pressButton(Screen screen, String label)
    {
        ButtonWidget button = screen.children().stream().filter(ButtonWidget.class::isInstance)
                .map(ButtonWidget.class::cast)
                .filter(widget -> widget.visible && widget.active && widget.getMessage().getString().equals(label))
                .findFirst().orElse(null);
        if (button == null)
        {
            return false;
        }

        button.onPress(null);
        return true;
    }

    private static int maximum(List<Integer> values)
    {
        return values.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private int ownWorldVertices(MinecraftClient client)
    {
        ClientRuntime.RenderBinding binding = own(client);
        if (binding == null)
        {
            return 0;
        }

        return ((List<?>) ModelRenderer.diagnostics().get("models")).stream()
                .filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                .filter(model -> model.owner().equals(binding.owner().toString())
                        && model.hash().equals(binding.assetHash()))
                .mapToInt(ModelRenderer.FrameModel::vertices).sum();
    }

    private void modelSettingsUiTick(MinecraftClient client)
    {
        if (!(client.currentScreen instanceof ModelSettingsScreen screen))
        {
            return;
        }

        if (this.uiStartedAt < 0)
        {
            if (!previewRendered(screen.previewDiagnostics()))
            {
                return;
            }

            this.uiStartedAt = this.stageTicks;
        }
        int age = this.stageTicks - this.uiStartedAt;
        if (age == 16)
        {
            this.settingsDragAttempted = dragPreview(screen,
                    mainPreviewDraw(defaultPreviewDraws(screen.previewDiagnostics())));
        }

        int vertices = ownWorldVertices(client);
        if (age < 8)
        {
            this.headdressBefore.add(vertices);
        }

        if (age == 8)
        {
            screen.toggleDefaultHeaddress();
            uiEvent("headwear-toggle", screen.defaultHeaddress());
        }
        if (age >= 10 && age < 24)
        {
            this.headdressChanged.add(vertices);
        }

        if (age == 24)
        {
            int before = maximum(this.headdressBefore), changed = maximum(this.headdressChanged);
            check("standaloneModelSettingsHeaddressGeometry",
                    screen.defaultHeaddress() != this.originalHeaddress && before > 0 && changed > 0
                            && (this.originalHeaddress ? changed < before : changed > before)
                            && PROFILE.equals(this.runtime.localAppearance()),
                    "Actual owner/world FrameModel vertices before=" + this.headdressBefore + "; toggled="
                            + this.headdressChanged + "; original headdress=" + this.originalHeaddress
                            + ". Maxima exclude short natural eye-closure samples; bow removal must change geometry.");
            screen.toggleDefaultHeaddress();
            uiEvent("headwear-restore", screen.defaultHeaddress());
        }
        if (age >= 27 && age < 40)
        {
            this.headdressRestored.add(vertices);
        }

        if (age == 40)
        {
            check("standaloneModelSettingsHeaddressRestored",
                    screen.defaultHeaddress() == this.originalHeaddress && maximum(this.headdressBefore) > 0
                            && maximum(this.headdressRestored) == maximum(this.headdressBefore),
                    "Actual restored world vertex samples=" + this.headdressRestored + "; initial="
                            + this.headdressBefore);
            check("standaloneModelSettingsSaveCallback",
                    screen.saveSettings(false) && PROFILE.equals(this.runtime.localAppearance())
                            && client.currentScreen == screen
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual product save callback preserves the validated profile and keeps the settings screen open");
            this.settingsVerified = true;
        }
    }

    private void wheelUiTick(MinecraftClient client) throws Exception
    {
        if (client.currentScreen instanceof ModelConfigScreen config)
        {
            wheelConfigTick(client, config);
            return;
        }
        if (!(client.currentScreen instanceof AnimationWheelScreen screen))
        {
            return;
        }

        if (this.uiStartedAt < 0)
        {
            this.wheelAuthorIds = new ArrayList<>(this.runtime.localModelProfile().extraAnimations().keySet());
            int index = -1;
            for (int i = 0; i < this.wheelAuthorIds.size(); i++)
            {
                if (this.wheelAuthorIds.get(i).equals("extra1"))
                {
                    index = i;
                    break;
                }
            }

            if (index < 0)
            {
                return;
            }

            this.uiStartedAt = this.stageTicks;
            this.wheelActionId = this.wheelAuthorIds.get(index);
            this.wheelSlot = index % 8;
            boolean locked = pressButton(screen, "保持打开：关");
            boolean paged = true;
            for (int i = 0; i < index / 8; i++)
            {
                paged &= pressButton(screen, "下一页");
            }

            JsonObject source = JsonParser.parseString(new String(builtinResource("ysm.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<String> sourceIds = new ArrayList<>(
                    source.getAsJsonObject("properties").getAsJsonObject("extra_animation").keySet());
            check("standaloneWheelPageAndLock",
                    locked && paged && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && this.wheelAuthorIds.equals(sourceIds)
                            && ((List<?>) screen.diagnostics().get("visibleActionIds")).equals(
                                    sourceIds.subList(index / 8 * 8, Math.min(sourceIds.size(), index / 8 * 8 + 8)))
                            && ((List<?>) screen.diagnostics().get("visibleActionIds")).get(this.wheelSlot)
                                    .equals(this.wheelActionId),
                    "Actual keep-open callback and authored extra-animation order from original JAR manifest; default has eight entries and one page, not the generic sorted movement inventory; wheel="
                            + screen.diagnostics());
        }
        int age = this.stageTicks - this.uiStartedAt;
        if (age >= 0 && age <= 30)
        {
            this.configBefore.add(worldVertices(client));
        }

        if (age == 5)
        {
            screen.beginHoldSelection();
            hoverWheel(screen);
            check("standaloneWheelHover", ((Number) screen.diagnostics().get("hovered")).intValue() == this.wheelSlot,
                    "Actual mouseMoved hit-tests visible wedge " + this.wheelSlot + "; wheel=" + screen.diagnostics());
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_1 + this.wheelSlot, 0, 0));
        }
        if (age == 6)
        {
            check("standaloneWheelUsesLocalApi",
                    wheelManual(client) && this.runtime.requestPacketsSent() == this.requestBaseline
                            && client.currentScreen == screen,
                    "Actual numeric-key handler starts local manual=" + this.wheelActionId + "; C2S="
                            + this.runtime.requestPacketsSent());
            uiEvent("wheel-numeric-action", own(client) == null ? "missing binding" : own(client).layers());
            ClientRuntime.RenderBinding binding = own(client);
            this.wheelStartedAt = binding == null ? -1
                    : binding.layers().stream()
                            .filter(layer -> layer.layer().equals("manual")
                                    && layer.animation().equals(this.wheelActionId))
                            .mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_G, 0, 0)); // Held entry-key repeat must not re-arm a consumed numeric choice.
            screen.keyReleased(new KeyInput(GLFW.GLFW_KEY_G, 0, 0));
        }
        if (age == 7)
        {
            ClientRuntime.RenderBinding binding = own(client);
            boolean sameStart = binding != null && binding.layers().stream()
                    .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(this.wheelActionId)
                            && layer.startedAtTick() == this.wheelStartedAt);
            check("standaloneWheelNumberConsumesHold",
                    this.wheelStartedAt >= 0 && sameStart && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Selecting a number while holding the entry key consumes that selection; held-G repeat and release cannot restart the locked preview; startedAtTick="
                            + this.wheelStartedAt);
        }
        if (age == 8)
        {
            check("standaloneWheelActionLockIndependent",
                    pressButton(screen, this.wheelOriginalActionLock ? "动作锁定：开" : "动作锁定：关")
                            && this.runtime.localActionLocked() != this.wheelOriginalActionLock
                            && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && PROFILE.equals(this.runtime.localAppearance())
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Action-lock product callback changes the separate native-motion cancellation preference while the menu keep-open flag stays on");
        }
        if (age == 9)
        {
            check("standaloneWheelActionLockRestored",
                    pressButton(screen, this.wheelOriginalActionLock ? "动作锁定：关" : "动作锁定：开")
                            && this.runtime.localActionLocked() == this.wheelOriginalActionLock
                            && Boolean.TRUE.equals(screen.diagnostics().get("locked")),
                    "Independent action-lock preference restored through its actual button");
        }

        if (age == 16)
        {
            check("standaloneWheelStopButton", pressButton(screen, "停止当前动作"), "Actual stop button callback invoked");
        }

        if (age == 17)
        {
            check("standaloneWheelStopped",
                    own(client) != null
                            && own(client).layers().stream().noneMatch(layer -> layer.layer().equals("manual")),
                    "Actual stop callback removes the private manual layer");
        }

        if (age == 20)
        {
            screen.beginHoldSelection();
            hoverWheel(screen);
            screen.keyReleased(new KeyInput(GLFW.GLFW_KEY_G, 0, 0));
        }
        if (age == 21)
        {
            ClientRuntime.RenderBinding binding = own(client);
            this.wheelStartedAt = binding == null ? -1
                    : binding.layers().stream()
                            .filter(layer -> layer.layer().equals("manual")
                                    && layer.animation().equals(this.wheelActionId))
                            .mapToLong(layer -> layer.startedAtTick()).findFirst().orElse(-1);
            screen.releaseSelection();
            screen.releaseSelection();
        }
        if (age == 22)
        {
            ClientRuntime.RenderBinding binding = own(client);
            boolean sameStart = binding != null && binding.layers().stream()
                    .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(this.wheelActionId)
                            && layer.startedAtTick() == this.wheelStartedAt);
            check("standaloneWheelReleaseSelectsOnce",
                    this.wheelStartedAt >= 0 && sameStart && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual entry-key release starts a private layer once; repeated release does not restart it; startedAtTick="
                            + this.wheelStartedAt);
        }
        if (age == 28)
        {
            pressButton(screen, "停止当前动作");
        }

        if (age == 30)
        {
            String pose = client.player.getPose().name() + ":" + client.player.isSleeping() + ":"
                    + client.player.hasVehicle();
            check("standaloneWheelLockOnlyKeepsUi",
                    client.currentScreen == screen && Boolean.TRUE.equals(screen.diagnostics().get("locked"))
                            && pose.equals(this.wheelPose) && PROFILE.equals(this.runtime.localAppearance())
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Native pose/sleep/vehicle before=" + this.wheelPose + "; after=" + pose
                            + "; local profile unchanged");
        }
        if (age == 34)
        {
            int configSlot = this.wheelAuthorIds.indexOf("extra0");
            Map<String, Object> diagnostics = screen.diagnostics();
            double radius = ((Number) diagnostics.get("innerRadius")).doubleValue()
                    + (((Number) diagnostics.get("outerRadius")).doubleValue()
                            - ((Number) diagnostics.get("innerRadius")).doubleValue()) * .24;
            double angle = -Math.PI / 2 + configSlot * Math.PI * 2 / 8;
            double x = ((Number) diagnostics.get("centerX")).doubleValue() + Math.cos(angle) * radius;
            double y = ((Number) diagnostics.get("centerY")).doubleValue() + Math.sin(angle) * radius;
            screen.mouseMoved(x, y);
            boolean clicked = screen.mouseClicked(new Click(x, y, new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)),
                    false);
            check("standaloneWheelAuthorConfigEntry",
                    configSlot >= 0
                            && "#extra_config".equals(this.runtime.localModelProfile().extraAnimations().get("extra0"))
                            && clicked && client.currentScreen instanceof ModelConfigScreen,
                    "Actual inner-ring click follows source extra0 → #extra_config; it opens author configuration rather than plays a guessed animation");
        }
        if (this.wheelConfigVerified)
        {
            this.wheelVerified = true;
        }

    }

    private int worldVertices(MinecraftClient client)
    {
        return ((List<?>) ModelRenderer.diagnostics().get("models")).stream()
                .filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                .filter(model -> model.owner().equals(client.player.getUuid().toString()))
                .mapToInt(ModelRenderer.FrameModel::vertices).sum();
    }

    private void wheelConfigTick(MinecraftClient client, ModelConfigScreen screen)
    {
        Map<String, Object> diagnostics = screen.diagnostics();
        if (!(diagnostics.get("forms") instanceof List<?> forms) || forms.size() != 1)
        {
            return;
        }

        Map<?, ?> form = (Map<?, ?>) forms.getFirst();
        if (this.configStartedAt < 0)
        {
            this.configStartedAt = this.stageTicks;
            this.configOriginalValue = ((Number) form.get("value")).doubleValue();
            JsonObject sourceForm = this.runtime.localModelProfile().extraAnimationButtons().get(0).getAsJsonObject()
                    .getAsJsonArray("config_forms").get(0).getAsJsonObject();
            check("standaloneAuthorConfigSource",
                    diagnostics.get("group").equals("extra_config") && form.get("key").equals("extra_config:0")
                            && form.get("kind").equals("CHECKBOX")
                            && sourceForm.get("type").getAsString().equals("checkbox")
                            && sourceForm.get("value").getAsString().equals("v.roaming.red_bow_headdress"),
                    "Real configuration widget maps to the original YSM checkbox expression; screen=" + diagnostics
                            + "; source=" + sourceForm);
            uiEvent("author-config-open", diagnostics);
            saveScreenshot(client, "wheel-author-config-ui");
        }
        int age = this.stageTicks - this.configStartedAt;
        if (age == 1)
        {
            check("standaloneAuthorConfigDraftPrivate",
                    pressButton(screen, this.configOriginalValue > 0 ? "✓ 开启" : "× 关闭")
                            && this.runtime.localModelVariables(PROFILE.modelId())
                                    .get("variable.roaming.red_bow_headdress") == this.configOriginalValue
                            && ((Number) screen.diagnostics().get("pendingScripts")).intValue() == 1
                            && PROFILE.equals(this.runtime.localAppearance()),
                    "Actual checkbox callback stages a script without mutating the active model before Apply");
        }

        if (age == 2)
        {
            check("standaloneAuthorConfigApplyPrivate", pressButton(screen, "应用并保存")
                    && this.runtime.localModelVariables(PROFILE.modelId())
                            .get("variable.roaming.red_bow_headdress") == (this.configOriginalValue > 0 ? 0d : 1d)
                    && this.runtime.requestPacketsSent() == this.requestBaseline
                    && PROFILE.equals(this.runtime.localAppearance()),
                    "Actual Apply button executes and saves the source checkbox script; no C2S request");
        }

        if (age >= 3 && age <= 15)
        {
            this.configChanged.add(worldVertices(client));
        }

        if (age == 15)
        {
            check("standaloneAuthorConfigChangesWorldGeometry",
                    maximum(this.configBefore) > 0 && maximum(this.configChanged) > 0
                            && (this.configOriginalValue > 0 ? maximum(this.configChanged) < maximum(this.configBefore)
                                    : maximum(this.configChanged) > maximum(this.configBefore)),
                    "Real native world mesh changes with author headwear variable; before=" + this.configBefore
                            + "; changed=" + this.configChanged);
        }

        if (age == 16)
        {
            pressButton(screen, this.configOriginalValue > 0 ? "× 关闭" : "✓ 开启");
        }

        if (age == 17)
        {
            check("standaloneAuthorConfigRestoreCallback",
                    pressButton(screen, "应用并保存") && this.runtime.localModelVariables(PROFILE.modelId())
                            .get("variable.roaming.red_bow_headdress") == this.configOriginalValue,
                    "Actual author configuration callback restores original value");
        }

        if (age >= 18 && age <= 31)
        {
            this.configRestored.add(worldVertices(client));
        }

        if (age == 32)
        {
            check("standaloneAuthorConfigRestoredGeometry",
                    maximum(this.configBefore) > 0 && maximum(this.configRestored) == maximum(this.configBefore)
                            && PROFILE.equals(this.runtime.localAppearance())
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Real restored geometry and private profile; original=" + this.configBefore + "; restored="
                            + this.configRestored);
            uiEvent("author-config-restored", diagnostics);
            screen.close();
            check("standaloneAuthorConfigCloseCallback", client.currentScreen instanceof AnimationWheelScreen,
                    "Product configuration Close returns to the existing keep-open wheel");
            this.wheelConfigVerified = true;
        }
    }

    private static void hoverWheel(AnimationWheelScreen screen, int slot)
    {
        Map<String, Object> diagnostics = screen.diagnostics();
        double radius = (((Number) diagnostics.get("innerRadius")).doubleValue()
                + ((Number) diagnostics.get("outerRadius")).doubleValue()) / 2;
        double angle = -Math.PI / 2 + slot * Math.PI * 2 / 8;
        screen.mouseMoved(((Number) diagnostics.get("centerX")).doubleValue() + Math.cos(angle) * radius,
                ((Number) diagnostics.get("centerY")).doubleValue() + Math.sin(angle) * radius);
    }

    private void hoverWheel(AnimationWheelScreen screen)
    {
        hoverWheel(screen, this.wheelSlot);
    }

    private boolean wheelManual(MinecraftClient client)
    {
        ClientRuntime.RenderBinding binding = own(client);
        return binding != null && binding.layers().stream()
                .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(this.wheelActionId));
    }

    private static boolean previewRendered(Map<String, Object> diagnostics)
    {
        return ((Number) diagnostics.get("frameModels")).longValue() > 0
                && ((Number) diagnostics.get("frameQuads")).longValue() > 0
                && ((Number) diagnostics.get("frameVertices")).longValue() > 0
                && ((Number) diagnostics.get("emittedVertices")).longValue() > 0
                && ((Number) diagnostics.get("gpuPixels")).longValue() > 0;
    }

    private void localActionUiTick(MinecraftClient client)
    {
        if (!(client.currentScreen instanceof PlayerModelScreen screen))
        {
            return;
        }

        if (!this.uiActionPressed && this.stageTicks >= 5)
        {
            String sourceLabel = this.runtime.localModelProfile().extraAnimations().get("extra1");
            String label = sourceLabel == null || sourceLabel.isEmpty() ? "extra1"
                    : this.runtime.localModelProfile().localized(client.options.language, sourceLabel, sourceLabel);
            ButtonWidget button = screen.children().stream().filter(ButtonWidget.class::isInstance)
                    .map(ButtonWidget.class::cast).filter(widget -> widget.getY() == 100 && widget.active
                            && widget.getMessage().getString().equals(label))
                    .findFirst().orElse(null);
            if (button != null && this.runtime.localModelProfile().extraAnimations().containsKey("extra1"))
            {
                this.uiActionId = "extra1";
                this.uiActionPressed = true;
                // Run the actual product button callback, which must choose playLocal rather than a server request.
                button.onPress(null);
            }
        }
        else if (this.uiActionPressed && !this.uiActionVerified && this.stageTicks >= 6)
        {
            ClientRuntime.RenderBinding local = own(client);
            boolean localLayer = local != null && local.layers().stream()
                    .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals(this.uiActionId));
            check("standaloneActionsUiUsesLocalApi",
                    localLayer && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual local action button selected=" + this.uiActionId + "; local manual layer=" + localLayer
                            + "; C2S request count=" + this.runtime.requestPacketsSent());
            this.uiActionVerified = true;
            this.runtime.stopLocal();
        }
    }

    private void captureUi(MinecraftClient client, String name, Map<String, Object> observation)
    {
        Screen screen = client.currentScreen;
        List<ClickableWidget> widgets = screen == null ? List.of()
                : screen.children().stream().filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast)
                        .filter(widget -> widget.visible).toList();
        int width = client.getWindow().getScaledWidth(), height = client.getWindow().getScaledHeight();
        boolean inBounds = !widgets.isEmpty() && widgets.stream()
                .allMatch(widget -> widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0
                        && widget.getY() >= 0 && widget.getRight() <= width && widget.getBottom() <= height);
        observation.put("ui",
                Map.of("class", screen == null ? "" : screen.getClass().getName(), "scaledWidth", width, "scaledHeight",
                        height, "controls",
                        widgets.stream().map(widget -> Map.of("class", widget.getClass().getSimpleName(), "label",
                                widget.getMessage().getString(), "x", widget.getX(), "y", widget.getY(), "width",
                                widget.getWidth(), "height", widget.getHeight(), "active", widget.active, "value",
                                widget instanceof TextFieldWidget field ? field.getText() : "")).toList()));
        check(name + "StandaloneControlsInBounds", inBounds,
                "Actual initialized UI controls=" + widgets.size() + "; scaled window=" + width + "x" + height);
        if (screen instanceof LocalAppearanceScreen gallery)
        {
            List<TextFieldWidget> fields = widgets.stream().filter(TextFieldWidget.class::isInstance)
                    .map(TextFieldWidget.class::cast).toList();
            Map<String, Object> preview = gallery.previewDiagnostics();
            long cards = widgets.stream().filter(widget -> widget.getClass().getSimpleName().equals("ModelCard"))
                    .count();
            observation.put("gallery",
                    Map.of("selectedModelId", gallery.selectedModelId(), "visibleModelIds", gallery.visibleModelIds(),
                            "pageIndex", gallery.pageIndex(), "pageCount", gallery.pageCount(), "loadedPreviews",
                            gallery.loadedPreviewCount(), "drawnPreviews", gallery.drawnPreviewCount(), "preview",
                            preview, "realCardWidgets", cards));
            if (name.equals("settings-ui"))
            {
                check("standaloneSettingsUiScreen", true, screen.getClass().getName());
                check("standaloneGallerySearchField",
                        fields.size() == 1 && fields.getFirst().getMessage().getString().equals("搜索模型"),
                        "Actual gallery has one search widget; fields="
                                + fields.stream().map(field -> field.getMessage().getString()).toList());
            }
            check(name + "StandaloneGalleryPreview",
                    cards > 0 && cards == gallery.visibleModelIds().size() && gallery.loadedPreviewCount() > 0
                            && gallery.drawnPreviewCount() >= 2 && previewRendered(preview),
                    "Actual native GUI model meshes and VertexConsumer writes; cards=" + cards + "; preview="
                            + preview);
            if (name.equals("settings-ui") || name.equals("ui-reload-ui"))
            {
                Map<String, Object> eyeDiagnostics = gallery.previewDiagnostics(23f / 128, 42f / 128, 25f / 128,
                        44f / 128);
                observation.put("eyeAtlasDraws", eyeDiagnostics);
                check(name.equals("settings-ui") ? "standaloneGalleryMainAndThumbnailEyes"
                        : "standaloneGalleryEyesAfterReload", currentPreviewEyes(eyeDiagnostics),
                        "Actual emitted main and thumbnail quads have two separated, positive-area front white-eye faces; consumer atlas UVs preserve original 23..25/42..44 source pixel boundaries. Natural source blink may omit eyes briefly; screenshot selects an open-eye mature frame: "
                                + eyeDiagnostics);
                List<Map<String, Object>> authorProofs = defaultPreviewDraws(eyeDiagnostics).stream()
                        .map(this::authorPreviewProof).toList();
                observation.put("authorPreviewProofs", authorProofs);
                check(name.equals("settings-ui") ? "standaloneGalleryAuthorGuiLayers"
                        : "standaloneGalleryAuthorGuiLayersAfterReload",
                        authorProofs.size() >= 2 && authorProofs.stream().allMatch(
                                proof -> Boolean.TRUE.equals(proof.get("actualBackgroundModelForegroundOrder"))
                                        && Boolean.TRUE.equals(proof.get("sourceFlagsMatch"))
                                        && Boolean.TRUE.equals(proof.get("fixedAuthorCamera"))),
                        "Actual OWNER uses native inventory animation/camera without decorations; CARD uses author cap/camera and original 52x90 background/model/foreground UV order: "
                                + authorProofs);
            }
            if (name.equals("settings-ui"))
            {
                check("standaloneGalleryAuthorRotationLocked",
                        this.galleryDragAttempted
                                && defaultPreviewDraws(gallery.previewDiagnostics()).stream().allMatch(
                                        draw -> Boolean.TRUE.equals(authorPreviewProof(draw).get("fixedAuthorCamera"))),
                        "Actual owner mouse drag is allowed by the native inventory camera; source disable_preview_rotation applies only to CARD, which remains at 0/0");
                observation.put("previewRegressionSamples", List.copyOf(this.previewRegressionSamples));
                check("standaloneGalleryFirstEntryProgressesOnce",
                        this.previewEntrySeen && this.previewEntryCompleted && this.previewEntryAlphaValid
                                && this.previewEntryScreenshot,
                        "Independent preview age advances through 12 ticks once; source-native geometry stays opaque without extra fade/scale. The early-clock screenshot is retained.");
                check("standaloneGalleryPreviewClockStable",
                        this.previewClockValid && this.previewSharedClockValid && this.previewLayersValid
                                && this.previewSameSelectionRequested && this.previewAfterSameSelectionSamples >= 6
                                && this.previewMaximumAge >= 24,
                        "Default OWNER and CARD maintain independent stable startedAtTick/startCount=1 through ordinary frames and a real same-card click; owner age="
                                + this.previewMaximumAge + "; main/card samples=" + this.previewMainSamples + "/"
                                + this.previewCardSamples + "; after repeated selection="
                                + this.previewAfterSameSelectionSamples
                                + "; native OWNER layers and CARD player.cap GUI are actually sampled");
                check("standaloneGalleryEyeAtlasSourceBounds",
                        this.previewAtlasValid && this.previewMainEyesSeen && this.previewCardEyesSeen
                                && this.previewMatureSamples >= 12,
                        "Independent original geometry/PNG pixel evidence versus numeric source and actual consumer atlas UVs (within .001 source pixel); both eye fronts observed in large preview and thumbnail across mature samples="
                                + this.previewMatureSamples);
            }
            if (name.equals("ui-reload-ui"))
            {
                check("standaloneGalleryPreviewAfterReload",
                        this.reload != null && this.reload.isDone() && !this.reload.isCompletedExceptionally()
                                && previewRendered(preview) && PROFILE.equals(this.runtime.localAppearance()),
                        "New gallery/ModelPreview instance uploads and emits vertices after actual resource reload; preview="
                                + preview);
            }

        }
        else if (screen instanceof ModelSettingsScreen settings)
        {
            List<TextFieldWidget> fields = widgets.stream().filter(TextFieldWidget.class::isInstance)
                    .map(TextFieldWidget.class::cast).toList();
            Map<String, Double> expected = Map.of("缩放", (double) PROFILE.scale(), "位置 X", PROFILE.offsetX(), "位置 Y",
                    PROFILE.offsetY(), "位置 Z", PROFILE.offsetZ());
            boolean values = fields.size() == 4 && fields.stream().allMatch(field ->
            {
                try
                {
                    return expected.containsKey(field.getMessage().getString())
                            && Math.abs(Double.parseDouble(field.getText())
                                    - expected.get(field.getMessage().getString())) < .000001;
                }
                catch (NumberFormatException invalid)
                {
                    return false;
                }
            });
            observation.put("modelSettings",
                    Map.of("selectedModelId", settings.selectedModelId(), "draft", settings.draftSettings(),
                            "defaultHeaddress", settings.defaultHeaddress(), "preview", settings.previewDiagnostics(),
                            "drawnPreviews", settings.drawnPreviewCount(), "headwearWorldVerticesBefore",
                            List.copyOf(this.headdressBefore), "headwearWorldVerticesChanged",
                            List.copyOf(this.headdressChanged), "headwearWorldVerticesRestored",
                            List.copyOf(this.headdressRestored)));
            check("standaloneModelSettingsUiScreen", true, screen.getClass().getName());
            // Preserve the release gate's historical name, now at the dedicated four-field settings page.
            check("standaloneSettingsUiProfileAndModel",
                    PROFILE.equals(this.runtime.localAppearance()) && PROFILE.equals(settings.draftSettings()) && values
                            && settings.selectedModelId().equals(PROFILE.modelId())
                            && this.runtime.localModels().stream()
                                    .anyMatch(model -> model.id().equals(PROFILE.modelId())),
                    "Four actual settings fields match active saved model/scale/XYZ; profile="
                            + this.runtime.localAppearance());
            check("standaloneModelSettingsPreview",
                    settings.drawnPreviewCount() > 0 && previewRendered(settings.previewDiagnostics()),
                    "Actual detail-page native mesh submission and VertexConsumer writes="
                            + settings.previewDiagnostics());
            Map<String, Object> eyeDiagnostics = settings.previewDiagnostics(23f / 128, 42f / 128, 25f / 128,
                    44f / 128);
            Map<?, ?> main = mainPreviewDraw(defaultPreviewDraws(eyeDiagnostics));
            Map<String, Object> eyes = whiteEyeProof(main);
            observation.put("eyeAtlasDraws", eyeDiagnostics);
            check("standaloneModelSettingsEyes",
                    previewRendered(eyeDiagnostics) && diagnosticNumber(main, "entryProgress") >= .999
                            && Boolean.TRUE.equals(eyes.get("bothEyesSeparated"))
                            && Boolean.TRUE.equals(eyes.get("numericAtlasMappingValid")),
                    "Settings page shares the actual source-pixel-correct preview renderer; two default white-eye front quads are submitted with positive projected area: "
                            + eyes);
            Map<String, Object> author = authorPreviewProof(main);
            observation.put("authorPreviewProof", author);
            check("standaloneModelSettingsAuthorGuiLayers",
                    Boolean.TRUE.equals(author.get("actualBackgroundModelForegroundOrder"))
                            && Boolean.TRUE.equals(author.get("sourceFlagsMatch"))
                            && Boolean.TRUE.equals(author.get("fixedAuthorCamera")),
                    "Actual settings OWNER native inventory projection has normal animation and no card-only GUI decorations: "
                            + author);
            check("standaloneModelSettingsAuthorRotationLocked",
                    this.settingsDragAttempted && Boolean.TRUE.equals(author.get("fixedAuthorCamera")),
                    "Real settings OWNER drag callback retains the native inventory camera; card-only author rotation policy is not applied to the owner");
        }
        else if (screen instanceof AnimationWheelScreen wheel)
        {
            Map<String, Object> diagnostics = wheel.diagnostics();
            observation.put("wheel", diagnostics);
            List<String> labels = widgets.stream().map(widget -> widget.getMessage().getString()).toList();
            check("standaloneWheelUiSeparateModes",
                    Boolean.TRUE.equals(diagnostics.get("localMode")) && labels.contains("本地动作 ✓")
                            && labels.contains("服务器动作") && !this.runtime.localActions().isEmpty(),
                    "Actual wheel scopes=" + labels);
            List<?> rectangles = (List<?>) diagnostics.get("labels");
            boolean separate = !rectangles.isEmpty();
            for (int index = 0; index < rectangles.size(); index++)
            {
                Map<?, ?> label = (Map<?, ?>) rectangles.get(index);
                int x = number(label, "x"), y = number(label, "y"), w = number(label, "width"),
                        h = number(label, "height");
                separate &= x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height;
                for (ClickableWidget widget : widgets)
                {
                    separate &= !overlap(x, y, w, h, widget.getX(), widget.getY(), widget.getWidth(),
                            widget.getHeight());
                }

                for (int other = index + 1; other < rectangles.size(); other++)
                {
                    Map<?, ?> next = (Map<?, ?>) rectangles.get(other);
                    separate &= !overlap(x, y, w, h, number(next, "x"), number(next, "y"), number(next, "width"),
                            number(next, "height"));
                }
            }
            for (int index = 0; index < widgets.size(); index++)
            {
                for (int other = index + 1; other < widgets.size(); other++)
                {
                    ClickableWidget a = widgets.get(index);
                    ClickableWidget b = widgets.get(other);
                    separate &= !overlap(a.getX(), a.getY(), a.getWidth(), a.getHeight(), b.getX(), b.getY(),
                            b.getWidth(), b.getHeight());
                }
            }

            check("standaloneWheelLabelsAndWidgetsSeparate", separate,
                    "Real wheel text rectangles do not overlap each other or initialized widgets; labels=" + rectangles
                            + "; window=" + width + "x" + height);
        }
        else if (screen instanceof PlayerModelScreen)
        {
            check("standaloneActionsUiScreen", screen instanceof PlayerModelScreen,
                    screen == null ? "No screen" : screen.getClass().getName());
            List<String> labels = widgets.stream().map(widget -> widget.getMessage().getString()).toList();
            check("standaloneActionsUiSeparateModes",
                    labels.contains("本地动作 ✓") && labels.contains("服务器动作") && !labels.contains("真实坐下")
                            && !labels.contains("真实爬行") && !this.runtime.localActions().isEmpty(),
                    "Initialized local actions pane has explicit local/server tabs; controls=" + labels);
        }
        else
        {
            check(name + "StandaloneUiScreen", false, screen == null ? "No screen" : screen.getClass().getName());
        }
    }

    private static int number(Map<?, ?> map, String key)
    {
        return ((Number) map.get(key)).intValue();
    }

    private static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh)
    {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    private static boolean visible(Map<String, Object> renderer, ClientRuntime.RenderBinding binding)
    {
        return ((List<?>) renderer.get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast)
                .anyMatch(model -> model.owner().equals(binding.owner().toString())
                        && model.hash().equals(binding.assetHash()) && model.vertices() > 0
                        && model.maxY() > model.minY());
    }

    private void capture(MinecraftClient client, String name, ClientRuntime.RenderBinding binding,
            Map<String, Object> renderer) throws Exception
    {
        this.captured = true;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("stage", name);
        observation.put("stageTick", this.stageTicks);
        if (binding != null)
        {
            observation.put("binding",
                    Map.of("instance", binding.instance(), "hash", binding.assetHash(), "motionSource",
                            binding.motionSource(), "x", binding.x(), "y", binding.y(), "z", binding.z(), "scale",
                            binding.scale(), "layers", binding.layers()));
        }

        observation.put("profile", this.runtime.localAppearance());
        observation.put("renderer", renderer);
        if (name.endsWith("-ui"))
        {
            observation.put("uiEvents", List.copyOf(this.uiEvents));
        }

        observation.put("requestPacketsSent", this.runtime.requestPacketsSent());
        observation.put("serverBridgeConnected", this.runtime.serverBridgeConnected());
        this.observations.add(observation);
        if (name.endsWith("-ui"))
        {
            captureUi(client, name, observation);
        }

        check(name + "StandaloneNoServerRequest", this.runtime.requestPacketsSent() == this.requestBaseline,
                "Only private local actions; C2S request baseline=" + this.requestBaseline + "; actual="
                        + this.runtime.requestPacketsSent());
        switch (name)
        {
        case "native" -> check("standaloneNativeBeforeSelection",
                binding == null && !this.runtime.shouldHidePlayer(client.player.getUuid()),
                this.runtime.status().toString());
        case "model" ->
        {
            this.modelHash = binding == null ? "" : binding.assetHash();
            check("standaloneLocalModelVisible",
                    binding != null && binding.instance().startsWith("local-self:") && visible(renderer, binding),
                    "Own bundled model renders without plugin=" + binding);
        }
        case "first-person" ->
        {
            check("standaloneFirstPersonNativeArm",
                    binding != null && binding.instance().startsWith("local-self:")
                            && client.options.getPerspective().isFirstPerson()
                            && ((Number) renderer.get("firstPersonSelfSkipped")).intValue() > 0
                            && !this.runtime.shouldHideFirstPersonArm()
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual first-person extraction skips private body while native held-item/use/map rendering remains allowed; custom YSM arms replace only prepared arm commands; renderer="
                            + renderer);
            Map<?, ?> components = (Map<?, ?>) renderer.get("components");
            boolean actualArmDraw = ((List<?>) components.get("submittedArmDraws")).stream()
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(draw -> client.player.getUuid().toString().equals(draw.get("owner")) && binding != null
                            && binding.instance().equals(draw.get("instance")) && "fp_arm".equals(draw.get("kind"))
                            && ((Number) draw.get("emittedVertices")).longValue() > 0);
            check("standaloneFirstPersonYsmArmMesh",
                    ((Number) components.get("submittedArms")).longValue() > this.firstPersonArmsBaseline
                            && ((Number) components.get("emittedArmVertices"))
                                    .longValue() > this.firstPersonArmVerticesBaseline
                            && actualArmDraw && ((List<?>) components.get("arms")).size() == 2
                            && this.runtime.requestPacketsSent() == this.requestBaseline,
                    "Actual author fp_arm geometry writes vertices through a matching private-owner arm command; submitted baseline="
                            + this.firstPersonArmsBaseline + "; vertex baseline=" + this.firstPersonArmVerticesBaseline
                            + "; components=" + components);
        }
        case "model-effects" ->
        {
            mergeEffectsChecks();
            boolean screenshotSaved = this.effectsScreenshot
                    && Files.isRegularFile(this.output.resolve(this.effectsScreenshotPath));
            check("standaloneYsmEffectsActiveScreenshot", screenshotSaved,
                    "Real framebuffer captured while actual custom SoundManager playback and live native particles were active; path="
                            + this.effectsScreenshotPath);
            Map<String, Object> proof = new LinkedHashMap<>(this.effects.diagnostics());
            proof.put("passed", this.effects.passed() && screenshotSaved);
            proof.put("checks", this.effects.checks());
            proof.put("activeScreenshot", this.effectsScreenshotPath);
            this.effectsReport = Map.copyOf(proof);
            observation.put("modelEffects", this.effectsReport);
            boolean cleaned = !client.player.hasVehicle();
            for (Entity entity : client.world.getEntities())
            {
                cleaned &= !this.componentFixtureUuids.contains(entity.getUuid());
            }

            check("standaloneComponentFixtureCleanup", cleaned,
                    "Only acceptance's unique-tag boat/arrow entities removed; exact native UUIDs="
                            + this.componentFixtureUuids);
        }
        case "vehicle-component", "projectile-component" ->
        {
            String prefix = name.equals("vehicle-component") ? "standaloneVehicleComponent"
                    : "standaloneProjectileComponent";
            observation.put("componentProof", this.componentProof);
            check(prefix + "ActualOwnerAndBinding",
                    Boolean.TRUE.equals(this.componentProof.get("currentBindingAndAuthorAsset")),
                    "Real summoned native UUID/rider or projectile owner is the private default profile owner; current binding and original author component agree: "
                            + this.componentProof);
            check(prefix + "ActualDraw", Boolean.TRUE.equals(this.componentProof.get("actualVerticesEmitted")),
                    "Matching native entity component writes positive VertexConsumer vertices and replaces exactly that native render state: "
                            + this.componentProof);
        }
        case "transform", "save-reload", "resource-reload" ->
        {
            Vec3d nativePos = client.player.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
            check(name + "StandaloneTransform",
                    binding != null && binding.assetHash().equals(this.modelHash) && visible(renderer, binding)
                            && Math.abs(binding.scale() - PROFILE.scale()) < .000001
                            && Math.abs(binding.x() - nativePos.x - PROFILE.offsetX()) < .000001
                            && Math.abs(binding.y() - nativePos.y - PROFILE.offsetY()) < .000001
                            && Math.abs(binding.z() - nativePos.z - PROFILE.offsetZ()) < .000001,
                    "World XYZ offsets and scale match saved profile; native=" + nativePos + "; local=" + binding);
            if (name.equals("resource-reload"))
            {
                check("standaloneResourceReload", this.reload != null && this.reload.isDone()
                        && !this.reload.isCompletedExceptionally()
                        && ((Number) renderer.get("extractedFrames")).longValue() >= this.reloadFrameBaseline + 2,
                        "Fresh geometry and restored textures after resource reload=" + renderer);
            }

        }
        case "action" -> check("standaloneLocalActionLayer",
                binding != null && binding.layers().stream()
                        .anyMatch(layer -> layer.layer().equals("manual") && layer.animation().equals("extra1")),
                "Private animation=" + binding);
        case "stop" ->
            check("standaloneLocalActionStopped",
                    binding != null && binding.layers().stream().noneMatch(layer -> layer.layer().equals("manual"))
                            && visible(renderer, binding),
                    "Model stays visible after stopping private action=" + binding);
        case "disable" -> check("standaloneDisableRestoresNative",
                binding == null && !this.runtime.localAppearance().enabled()
                        && !this.runtime.shouldHidePlayer(client.player.getUuid())
                        && ((List<?>) renderer.get("models")).isEmpty(),
                "Native player restored; renderer=" + renderer);
        default ->
            {
            }
        }
        Files.createDirectories(this.output);
        saveScreenshot(client, name);
    }

    private void check(String name, boolean passed, String detail)
    {
        this.checks.add(Map.of("name", name, "passed", passed, "detail", detail));
        LOG.info("Standalone check {}={} {}", name, passed, detail);
    }

    private void finish(MinecraftClient client)
    {
        if (this.finished)
        {
            return;
        }

        this.finished = true;
        try
        {
            if (this.effects != null)
            {
                this.effects.close();
                mergeEffectsChecks();
            }
            if (!this.componentFixtureUuids.isEmpty())
            {
                cleanupComponentFixtures(client);
            }

            if (this.interactions)
            {
                cleanupInteractions(client);
            }

            check("standaloneAllStagesCaptured",
                    this.interactions ? this.interactionCompleted && !this.observations.isEmpty()
                            : this.observations.size() == this.stages.size(),
                    "Captured stages=" + this.observations.stream().map(row -> row.get("stage")).toList());
            check("standaloneBridgeStayedDisconnected", !this.runtime.serverBridgeConnected(),
                    this.runtime.status().toString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", "standalone-no-server-plugins");
            result.put("passed", this.checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
            result.put("minecraft", "1.21.11");
            result.put("clientVersion", FabricLoader.getInstance().getModContainer("meplayeractions").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString());
            result.put("runtimeServerConnected", this.runtime.serverBridgeConnected());
            result.put("serverChannelAvailable",
                    client.getNetworkHandler() != null && ClientPlayNetworking.canSend(ActionPayload.ID));
            result.put("testedClientSha256", this.artifactProof.get("clientArtifactSha256"));
            result.put("artifactProof", this.artifactProof);
            result.put("startedAtMillis", this.startedMillis);
            result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", this.checks);
            result.put("screenshots", this.screenshots);
            result.put("observations", this.observations);
            result.put("savedProfilePath", this.runtime.localAppearanceSettingsPath().toString());
            result.put("galleryFixture", this.galleryFixture);
            result.put("uiEvents", this.uiEvents);
            result.put("validationProfile", this.validationProfile);
            if (this.interactions)
            {
                result.put("interactionDelivery", interactionDelivery());
            }

            else
            {
                result.put("guiPreviewRegression", guiPreviewRegression());
            }

            result.put("modelEffects", this.effectsReport);
            Files.createDirectories(this.output);
            Files.writeString(this.output.resolve("results.json"), GSON.toJson(result), StandardCharsets.UTF_8);
        }
        catch (Exception failure)
        {
            LOG.error("Cannot save standalone results", failure);
        }
        client.scheduleStop();
    }

    private void mergeEffectsChecks()
    {
        if (this.effects == null || this.effectsChecksMerged)
        {
            return;
        }

        this.checks.addAll(this.effects.checks().stream().filter(candidate -> this.checks.stream()
                .noneMatch(existing -> existing.get("name").equals(candidate.get("name")))).toList());
        this.effectsChecksMerged = this.effects.complete();
    }

    private Map<String, Object> guiPreviewRegression()
    {
        List<String> required = List.of("standaloneBuiltinPreviewSourceEvidence",
                "standaloneGallerySameSelectionCallback", "standaloneGalleryFirstEntryProgressesOnce",
                "standaloneGalleryPreviewClockStable", "standaloneGalleryEyeAtlasSourceBounds",
                "standaloneGalleryMainAndThumbnailEyes", "standaloneModelSettingsEyes",
                "standaloneGalleryEyesAfterReload", "standaloneGalleryAuthorGuiLayers",
                "standaloneGalleryAuthorRotationLocked", "standaloneModelSettingsAuthorGuiLayers",
                "standaloneModelSettingsAuthorRotationLocked", "standaloneGalleryAuthorGuiLayersAfterReload");
        List<Map<String, Object>> relevant = this.checks.stream().filter(row -> required.contains(row.get("name")))
                .toList();
        boolean passed = required.stream()
                .allMatch(name -> relevant.stream()
                        .anyMatch(row -> name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("passed"))))
                && relevant.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        return Map.of("passed", passed, "requiredChecks", required, "checks", relevant, "builtinAssetEvidence",
                this.builtinPreviewAsset, "frameSamples", this.previewRegressionSamples, "entryMeaning",
                "Independent native OWNER and Dummy CARD clocks; OWNER uses native motion and Inventory70, CARD stops main and routes authored GUI to cap/30, without added YSM fade/scale");
    }

    private static Map<String, Object> artifactProof()
    {
        Map<String, Object> proof = new LinkedHashMap<>();
        try
        {
            Path origin = FabricLoader.getInstance().getModContainer("meplayeractions").orElseThrow().getOrigin()
                    .getPaths().getFirst();
            boolean fromJar = Files.isRegularFile(origin) && origin.getFileName().toString().endsWith(".jar");
            proof.put("clientFromJar", fromJar);
            proof.put("clientSource", origin.toAbsolutePath().toString());
            proof.put("clientArtifactSha256", fromJar
                    ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin)))
                    : "");
            proof.put("clientHashSource", "FabricLoader actual loaded production mod origin");
        }
        catch (Exception failure)
        {
            proof.put("clientFromJar", false);
            proof.put("clientArtifactSha256", "");
            proof.put("error", failure.toString());
        }
        return Map.copyOf(proof);
    }
}
