package com.simmc.meplayeractions.client.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.ui.ActionsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.block.BedBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import java.util.concurrent.CompletableFuture;

/** Deliberately inert in ordinary launches. Uses only Minecraft APIs in an isolated runDir. */
public final class E2EHarness {
    private static final Logger LOGGER = LoggerFactory.getLogger("MEPlayerActions/E2E");
    private static final String PLAYER = "MPATest";
    private static final UUID OTHER = UUID.nameUUIDFromBytes("OfflinePlayer:MPAObserver".getBytes(StandardCharsets.UTF_8));
    private static final List<String> STAGES = System.getProperty("meplayeractions.e2e.focus", "").equals("visibility")
            ? List.of("other-idle", "other-undisguise", "other-native-move", "other-redisguise", "other-undisguise-again", "undisguise")
            : List.of("push-first-download", "push-cache-reconnect", "push-asset-failed", "push-asset-recovered", "idle", "local-appearance-server-base", "local-appearance-own", "local-appearance-action", "local-appearance-off",
            "native-follow", "server-trailing", "wave", "crawl", "crawl-side", "bed", "ride", "boat", "extra", "hunger", "jump",
            "firstperson", "firstperson-extra0", "extra0-restored", "gsit-sit", "gsit-crawl", "gsit-lay", "gsit-firstperson", "gsit-thirdperson", "gsit-undisguise", "gsit-redisguise", "gsit-reset", "resource-reload", "pack-disabled", "pack-restored", "local-off", "local-on", "npc", "npc-crawl", "npc-crawl-side",
            "menu", "self-hidden", "self-hidden-extra0", "self-restored", "server-reload", "other-idle",
            "other-item-01-hold", "other-item-01-swing", "other-item-01-use", "other-item-02-hold", "other-item-02-swing", "other-item-02-use", "other-native-follow", "other-crawl", "other-bed",
            "other-ride", "other-jump", "other-extra0", "other-range-out", "other-range-in", "other-undisguise", "other-native-move", "other-redisguise", "other-undisguise-again",
            "other-second-model", "other-second-undisguise", "other-first-model", "own-second-model", "own-second-undisguise", "own-first-model", "undisguise", "preview", "preview-end");
    private final ClientRuntime runtime;
    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/e2e")).toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final List<Map<String, Object>> screenshotCallbacks = new ArrayList<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<Map<String, Object>> fixtureFrames = new ArrayList<>();
    private final long started = System.nanoTime();
    private final long startedMillis = System.currentTimeMillis();
    private final Map<String, Object> proof = artifactProof();
    private int ticks, stage = -1, stageTicks, connectionTicks, clearWorldTicks;
    private boolean connected, finished, captured, handshakeChecked, worldConfigured, jumpSeen, jumpSeenAfterGround;
    private double idleHeight;
    private double npcIdleHeight, otherIdleHeight;
    private int jumpFrames;
    private boolean otherJumpSeen, otherJumpAfterGround;
    private int otherJumpFrames;
    private int jumpGroundedTicks, jumpPressedAt = -1, otherTakeoffAt = -1;
    private boolean jumpNativeTakeoff, otherNativeTakeoff;
    private double jumpGroundY, jumpMaxRise, otherJumpMaxRise;
    private int reloadReadyAt = -1, reloadVisibleTicks;
    private long reloadFrameBaseline;
    private List<String> originalPackProfiles = List.of(), disabledPackProfiles = List.of();
    private String packExpectedInstance = "", packExpectedHash = "", packAuthorityError = "", packReloadError = "";
    private boolean packSelectionChanged;
    private Map<String, Object> packPushBaseline = Map.of(), pushReconnectBaseline = Map.of();
    private boolean pushReconnectConnecting, pushReconnectDisguiseIssued;
    private String pushReconnectHash = "", pushReconnectPreviousInstance = "";
    private long pushReconnectGeneration;
    private int pushReconnectTicks, pushReconnectReadyAt = -1;
    private boolean pushReconnectDisconnected;
    private Map<String, Object> pushFaultBaseline = Map.of(), pushInvalidBaseline = Map.of();
    private String pushFaultOriginalHash = "", pushFaultPreviousInstance = "";
    private int pushFaultStableTicks;
    private boolean pushFaultRequested;
    private final Map<String, String> pushPreviousInstances = new LinkedHashMap<>();
    private String ownSecondInitialInstance = "", ownSecondInitialHash = "", ownSecondSourceInstance = "";
    private boolean ownSecondTargetRequested;
    private long itemSubmissionBaseline, itemFrameBaseline, itemStageStartedAtMillis;
    private boolean itemStageSucceeded;
    private int itemDrawStableTicks;
    private final Map<String,Map<String,float[]>> heldItemBaselines = new LinkedHashMap<>();
    private Map<String,Object> itemLatestProof = Map.of();
    private Map<String,Object> itemShieldExpectedSource = Map.of();
    private boolean handsCleanupPending;
    private int ownSecondReadyTicks;
    private Map<String, Object> ownSecondSourceProof = Map.of();
    private long packReloadRequest, packReloadStartedAtMillis, packReloadCompletedAtMillis, packFrameBaseline, packDrawBaseline;
    private int packReloadCallbacks, packReadyAt = -1, packSettledTicks;
    private int packMissingSamples;
    private boolean packNoStaleReady = true;
    private JsonObject packAuthority;
    private String remotePreviousInstance = "", remoteExtraInstance = "";
    private boolean remoteExtraRequested, remoteExtraSeen, remoteExtraEventChecked, remoteExtraEarlyValid = true;
    private int remoteExtraReadyTicks, remoteExtraEarlySamples, remoteExtraCompletedAt = -1;
    private long remoteExtraStartedAt;
    private double remoteExtraLengthTicks, remoteExtraFirstChangedAge = Double.NaN;
    private int followSamples,followWalking;
    private double maxFollowError,maxServerGap;
    private double restoredPlayerX = Double.NaN;
    private CompletableFuture<Void> reload;
    private ArmorStandEntity sideCamera;
    private Entity gsitCamera;
    private Map<String, Object> gsitNaturalCamera = Map.of();
    private ClientRuntime.RenderBinding localServerBaseline;
    private List<ClientRuntime.RenderBinding> localRemoteBaseline = List.of();
    private long localRequestBaseline;
    private int localAppearanceReadyAt = -1;
    private String ownAccessoryInstance = "";
    private boolean ownAccessorySeen, ownAccessoryEventChecked, ownAccessoryEarlyValid;
    private int ownAccessoryEarlySamples, ownAccessoryCompletedAt;
    private long ownAccessoryStartedAt;
    private double ownAccessoryLengthTicks, ownAccessoryFirstChangedAge;
    private static final LocalAppearanceSettings LOCAL_PROFILE = new LocalAppearanceSettings(true, "openysm_default", .65f, .25, .35, -.2);

    private E2EHarness(ClientRuntime runtime) { this.runtime = runtime; }

    public static void register(ClientRuntime runtime) {
        if (System.getProperty("meplayeractions.e2e.focus", "").equals("standalone")) {
            LocalAppearanceHarness.register(runtime);
            return;
        }
        E2EHarness harness = new E2EHarness(runtime);
        ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
        LOGGER.info("Isolated E2E harness enabled; outputs {}", harness.output);
    }

    private void tick(MinecraftClient client) {
        if (finished) return;
        ticks++;
        try {
            if (System.nanoTime() - started > 420_000_000_000L) {
                check("completion", false, "420 second timeout; screen=" + (client.currentScreen == null ? "world" : client.currentScreen.getClass().getSimpleName()));
                finish(client);
                return;
            }
            if (!connected) {
                if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null && ticks > 40) {
                    check("titleSmoke", true, "Minecraft title screen initialized with all required mixins");
                    check("productionJarLoaded", Boolean.TRUE.equals(proof.get("clientFromJar")), proof.toString());
                    screenshot(client, "00-title");
                    String address = System.getProperty("meplayeractions.e2e.server", "127.0.0.1:25591");
                    if (address.equals("title-only")) { finish(client); return; }
                    ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse(address),
                            new ServerInfo("MEPlayerActions isolated E2E", address, ServerInfo.ServerType.OTHER), false, null);
                    connected = true;
                }
                return;
            }
            if (stage >= 0 && STAGES.get(stage).equals("push-cache-reconnect")) {
                pushReconnectTick(client);
                return;
            }
            if (client.world == null || client.player == null || client.getNetworkHandler() == null) {
                if (++connectionTicks > 900) { check("realMultiplayer", false, "Did not join isolated Paper server"); finish(client); }
                return;
            }
            clearWorldTicks = client.getOverlay() == null && client.currentScreen == null ? clearWorldTicks + 1 : 0;
            if (!handshakeChecked) {
                connectionTicks++;
                if (!worldConfigured) {
                    worldConfigured = true;
                    if (!runtime.options.enabled) runtime.toggleEnabled();
                    runtime.options.showSelf = true;
                    runtime.options.followServerTimeline = false;
                    runtime.disableLocalAppearance();
                    client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                    client.options.getGamma().setValue(1.0);
                    client.player.getAbilities().flying = false;
                    command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
                }
                if (own(client).isEmpty() || clearWorldTicks < 10) {
                    if (connectionTicks > 500) { check("realMultiplayer", false, "No acknowledged local binding: " + runtime.status()); finish(client); }
                    return;
                }
                handshakeChecked = true;
                check("realMultiplayer", true, "Connected to Paper 1.21.11 with acknowledged local model");
                check("assetHashAndGpu", ModelRenderer.has(own(client).get(0).assetHash()),
                        "hash=" + own(client).get(0).assetHash() + "; " + ModelRenderer.diagnostics());
                next(client);
                return;
            }
            stageTicks++;
            String name = STAGES.get(stage);
            if (name.equals("push-first-download")) {
                if (stageTicks % 10 == 0) command(client, "mpatest " + PLAYER + " status");
                if (stageTicks % 5 == 0) {
                    JsonObject status = readPackAuthority(client);
                    if (status != null) packAuthority = status;
                }
            }
            if(name.equals("native-follow") || name.equals("server-trailing")) {
                client.options.forwardKey.setPressed(stageTicks>=15 && stageTicks<40);
                if(name.equals("native-follow") && stageTicks>=17 && stageTicks<40) measureFollow(client,client.player);
            }
            if(name.equals("other-native-follow") && stageTicks>=17 && stageTicks<40) measureFollow(client,otherPlayer(client));
            if (name.equals("jump")) prepareNativeJump(client);
            if (stageTicks % 5 == 0) phase(client, name);
            if (stageTicks == 5) {
                if (name.equals("gsit-sit")) command(client, "meplayeractions pose sit");
                if (name.equals("gsit-crawl")) command(client, "meplayeractions pose crawl");
                if (name.equals("gsit-lay")) command(client, "lay");
            }
            if (name.equals("gsit-sit") || name.equals("gsit-crawl") || name.equals("gsit-lay") || name.equals("gsit-undisguise") || name.equals("gsit-redisguise")) {
                if (stageTicks == 10) {
                    gsitCamera = client.getCameraEntity();
                    gsitNaturalCamera = Map.of("focused", gsitCamera == null ? "" : gsitCamera.getUuidAsString(),
                            "localPlayer", client.player.getUuidAsString(), "perspective", client.options.getPerspective().name());
                    sideCamera = sideCamera(client);
                }
                if (stageTicks >= 10 && sideCamera != null) {
                    client.setCameraEntity(sideCamera);
                    client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                }
            }
            if (name.startsWith("other-")) {
                PlayerEntity other = otherPlayer(client);
                List<ClientRuntime.RenderBinding> remote = otherBindings(client);
                if (!remote.isEmpty()) aim(client, remote.get(0));
                else if (other != null) aim(client, other.getX(), other.getY() + .6, other.getZ());
                if (name.equals("other-jump")) {
                    if (other != null && stageTicks >= 10) {
                        otherJumpMaxRise = Math.max(otherJumpMaxRise, other.getY() + 60);
                        if (!other.isOnGround() && other.getY() > -59.88) {
                            otherNativeTakeoff = true;
                            if (otherTakeoffAt < 0) otherTakeoffAt = stageTicks;
                        }
                    }
                    boolean jumping = otherBindings(client).stream().flatMap(binding -> binding.layers().stream())
                            .anyMatch(layer -> layer.animation().equals("player_jump") || layer.animation().equals("jump"));
                    if (jumping) {
                        otherJumpSeen = true; otherJumpFrames++;
                        if (otherNativeTakeoff && (other != null ? other.isOnGround() : remote.stream().anyMatch(binding -> binding.y() <= -59.98))) otherJumpAfterGround = true;
                    }
                    recordJumpFrame(client, name, other, remote);
                    if (!captured && jumping && stageTicks >= 5) capture(client, name);
                }
            }
            if (name.equals("jump")) {
                if (jumpPressedAt >= 0) {
                    jumpMaxRise = Math.max(jumpMaxRise, client.player.getY() - jumpGroundY);
                    if (!client.player.isOnGround() && jumpMaxRise > .12) jumpNativeTakeoff = true;
                }
                boolean jumping = own(client).stream().flatMap(binding -> binding.layers().stream())
                        .anyMatch(layer -> layer.animation().equals("player_jump") || layer.animation().equals("jump"));
                if (jumping) {
                    jumpSeen = true;
                    jumpFrames++;
                    if (jumpNativeTakeoff && client.player.isOnGround()) jumpSeenAfterGround = true;
                }
                recordJumpFrame(client, name, client.player, own(client));
                if (!captured && jumping && stageTicks >= 5) capture(client, name);
            }
            if (name.startsWith("other-item-")) { itemDrawTick(client, name); return; }
            if (name.equals("resource-reload")) {
                resourceReloadTick(client);
                return;
            }
            if (name.equals("push-asset-failed") || name.equals("push-asset-recovered")) {
                pushFaultTick(client, name);
                return;
            }
            if (name.equals("pack-disabled") || name.equals("pack-restored")) {
                packReloadTick(client, name);
                return;
            }
            if (name.equals("other-extra0")) {
                remoteAccessoryTick(client);
                return;
            }
            if (name.equals("own-second-model")) {
                ownSecondModelTick(client);
                return;
            }
            if (name.equals("firstperson-extra0") || name.equals("self-hidden-extra0")) {
                ownAccessoryTick(client, name);
                return;
            }
            if (name.startsWith("local-appearance-") && !name.equals("local-appearance-server-base")) {
                localAppearanceTick(client, name);
                return;
            }
            if (name.equals("server-reload") && stageTicks == 30) command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
            if ((name.equals("local-on") || name.equals("npc") || name.equals("server-reload") || name.equals("preview") || name.equals("gsit-redisguise") || name.equals("own-first-model")) && own(client).isEmpty()) {
                if (stageTicks > 400) { check(name, false, "No model returned after lifecycle operation"); next(client); }
                return;
            }
            if ((name.equals("other-idle") || name.equals("other-range-in") || name.equals("other-redisguise") || name.equals("other-first-model")) && otherBindings(client).isEmpty()) {
                if (stageTicks > 200) { check(name, false, "Other player binding unavailable"); next(client); }
                return;
            }
            if (stageTicks == 20) { client.inGameHud.getChatHud().clear(false); client.getToastManager().clear(); }
            if (!captured && stageTicks >= (name.equals("resource-reload") ? 45 : 30)) capture(client, name);
            if (name.equals("jump")) {
                if ((jumpPressedAt >= 0 && stageTicks >= jumpPressedAt + 80) || stageTicks > 200) next(client);
            } else if (name.equals("other-jump")) {
                if ((otherTakeoffAt >= 0 && stageTicks >= otherTakeoffAt + 80) || stageTicks > 200) next(client);
            } else if (stageTicks >= 60) next(client);
        } catch (Exception failure) {
            check("harness", false, failure.toString());
            LOGGER.error("E2E harness failed", failure);
            finish(client);
        }
    }

    private void next(MinecraftClient client) {
        if (stage >= 0 && STAGES.get(stage).equals("other-item-02-use")) {
            command(client, "mpatest MPAObserver hands-restore"); handsCleanupPending = false;
        }
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        if(stage>=0 && (STAGES.get(stage).equals("native-follow") || STAGES.get(stage).equals("other-native-follow"))) {
            String label=STAGES.get(stage).equals("native-follow")?"own":"remote";
            check(label+"NativeEntityFollow",followSamples>=10 && maxFollowError<.000001,
                    "Samples="+followSamples+"; worst pivot error="+maxFollowError+" blocks; server delay=8 ticks");
            check(label+"IgnoresDelayedServerPosition",maxServerGap>.05,"Delayed server separation="+maxServerGap+" blocks");
            check(label+"NativeWalkStartsLocally",followWalking>=5,"Native walking observations="+followWalking);
        }
        if (stage >= 0 && STAGES.get(stage).equals("jump")) {
            check("jumpNativeTakeoff", jumpPressedAt >= 0 && jumpNativeTakeoff,
                    "Single native jump key after stable standing; pressedAt=" + jumpPressedAt + "; maxRise=" + jumpMaxRise);
            check("jumpAnimation", jumpSeen, "Animated jump tick observations=" + jumpFrames);
            check("jumpFinishesAfterGround", jumpSeenAfterGround, "Observed jump animation after real player landed");
        }
        if (stage >= 0 && STAGES.get(stage).equals("other-jump")) {
            check("otherJumpNativeTakeoff", otherNativeTakeoff,
                    "Unmodded B uses its native jump key after stable standing; takeoffAt=" + otherTakeoffAt + "; maxRise=" + otherJumpMaxRise);
            check("otherJumpAnimation", otherJumpSeen, "Remote animated jump observations=" + otherJumpFrames);
            check("otherJumpFinishesAfterGround", otherJumpAfterGround, "Remote jump animation continues at ground height; native entity can be hidden by ME");
        }
        stage++;
        stageTicks = 0;
        captured = false;
        followSamples=0;followWalking=0;maxFollowError=0;maxServerGap=0;
        if (stage >= STAGES.size()) { finish(client); return; }
        String name = STAGES.get(stage);
        sideCamera = null;
        client.setScreen(null);
        client.setCameraEntity(client.player);
        client.options.setPerspective(name.startsWith("other-") || name.startsWith("firstperson")
                ? Perspective.FIRST_PERSON : Perspective.THIRD_PERSON_FRONT);
        LOGGER.info("E2E stage {}", name);
        phase(client, name);
        if (!name.equals("bed")) command(client, "time set day");
        if (name.startsWith("other-item-")) beginItemDraw(client, name);
        switch (name) {
            case "push-first-download" -> {
                packExpectedInstance = own(client).getFirst().instance();
                packReloadCompletedAtMillis = System.currentTimeMillis(); packAuthority = null;
                command(client, "mpatest " + PLAYER + " status");
            }
            case "push-cache-reconnect" -> beginPushReconnect(client);
            case "push-asset-failed", "push-asset-recovered" -> beginPushFault(client, name);
            case "idle" -> command(client, "mpatest " + PLAYER + " reset");
            case "local-appearance-server-base" -> { runtime.disableLocalAppearance(); command(client, "mpatest " + PLAYER + " reset"); }
            case "local-appearance-own" -> {
                localRequestBaseline = runtime.requestPacketsSent(); localAppearanceReadyAt = -1;
                runtime.selectLocalModel("openysm_default"); runtime.updateLocalAppearance(LOCAL_PROFILE);
            }
            case "local-appearance-action" -> {
                localAppearanceReadyAt = -1;
                check("localAppearanceActionAccepted", runtime.playLocal("extra1"), "Explicit local action starts without a server request");
            }
            case "local-appearance-off" -> { localAppearanceReadyAt = -1; runtime.disableLocalAppearance(); }
            case "native-follow" -> { command(client,"mpatest "+PLAYER+" reset"); command(client,"mpatest "+PLAYER+" delayed ysm_01_jk"); }
            case "server-trailing" -> { runtime.options.followServerTimeline=true;command(client,"mpatest "+PLAYER+" reset"); }
            case "wave" -> { runtime.options.followServerTimeline=false;command(client,"mpatest "+PLAYER+" reset");command(client, "mpatest " + PLAYER + " wave"); }
            case "crawl", "npc-crawl" -> command(client, "mpatest " + PLAYER + " crawl");
            case "crawl-side", "npc-crawl-side" -> {
                client.setCameraEntity(sideCamera(client));
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            case "bed" -> { command(client, "time set night"); command(client, "mpatest " + PLAYER + " bed"); }
            case "ride" -> command(client, "mpatest " + PLAYER + " ride");
            case "jump" -> {
                jumpGroundedTicks = 0; jumpPressedAt = -1; jumpNativeTakeoff = false;
                jumpSeen = false; jumpSeenAfterGround = false; jumpFrames = 0; jumpMaxRise = 0;
                // Reset/teleport is a separate preparation step, never the takeoff itself.
                command(client, "mpatest " + PLAYER + " reset");
            }
            case "firstperson" -> {
                command(client, "mpatest " + PLAYER + " reset");
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
            case "firstperson-extra0" -> beginOwnAccessory(client, name);
            case "gsit-sit" -> {
                command(client, "mpatest " + PLAYER + " reset");
                command(client, "item replace entity " + PLAYER + " armor.chest with minecraft:elytra");
            }
            case "gsit-crawl", "gsit-lay" -> command(client, "meplayeractions reset");
            case "gsit-firstperson" -> {
                client.setCameraEntity(gsitCamera == null ? client.player : gsitCamera);
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
            case "gsit-thirdperson" -> {
                client.setCameraEntity(gsitCamera == null || gsitCamera.isRemoved() ? client.player : gsitCamera);
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            case "gsit-undisguise" -> command(client, "meplayeractions undisguise");
            case "gsit-redisguise" -> command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
            case "gsit-reset" -> {
                command(client, "lay"); command(client, "meplayeractions reset"); command(client, "mpatest " + PLAYER + " reset");
            }
            case "resource-reload" -> {
                reloadReadyAt = -1; reloadVisibleTicks = 0;
                command(client, "item replace entity " + PLAYER + " armor.chest with minecraft:air");
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                reload = client.reloadResources();
            }
            case "pack-disabled" -> beginPackDisable(client);
            case "pack-restored" -> {
                client.getResourcePackManager().setEnabledProfiles(originalPackProfiles);
                beginPackReload(client);
            }
            case "local-off" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "local-on" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "boat" -> command(client,"mpatest "+PLAYER+" boat");
            case "extra" -> command(client,"mpatest "+PLAYER+" extra");
            case "hunger" -> command(client,"mpatest "+PLAYER+" hunger");
            case "npc" -> command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
            case "menu" -> {
                command(client, "mpatest " + PLAYER + " reset");
                runtime.preview("openysm_default");
                client.setScreen(new ActionsScreen(runtime));
            }
            case "self-hidden" -> runtime.options.showSelf = false;
            case "self-hidden-extra0" -> beginOwnAccessory(client, name);
            case "self-restored" -> runtime.options.showSelf = true;
            case "server-reload" -> {
                command(client, "mpatest " + PLAYER + " reset");
                command(client, "mpatest " + PLAYER + " reload");
                // Plugin reload removes its disguise, so create a new instance after reload.
                command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
            }
            case "other-idle" -> {
                command(client, "mpatest " + PLAYER + " reset");
                command(client, "mpatest MPAObserver reset");
                command(client, "mpatest MPAObserver disguise ysm_01_jk");
            }
            case "other-native-follow" -> { command(client,"mpatest MPAObserver delayed ysm_01_jk");command(client,"mpatest MPAObserver walk"); }
            case "other-crawl" -> command(client, "mpatest MPAObserver crawl");
            case "other-bed" -> { command(client, "time set night"); command(client, "mpatest MPAObserver bed"); }
            case "other-ride" -> command(client, "mpatest MPAObserver ride");
            case "other-jump" -> {
                otherNativeTakeoff = false; otherTakeoffAt = -1; otherJumpMaxRise = 0;
                otherJumpSeen = false; otherJumpAfterGround = false; otherJumpFrames = 0;
                command(client, "mpatest MPAObserver reset");
                // B's independent fixture presses the native jump key after this reset settles.
            }
            case "other-extra0" -> {
                remotePreviousInstance = otherBindings(client).stream().map(ClientRuntime.RenderBinding::instance).findFirst().orElse("");
                remoteExtraInstance = ""; remoteExtraRequested = false; remoteExtraSeen = false;
                remoteExtraEventChecked = false; remoteExtraEarlyValid = true; remoteExtraReadyTicks = 0;
                remoteExtraEarlySamples = 0; remoteExtraCompletedAt = -1; remoteExtraFirstChangedAge = Double.NaN;
                command(client, "mpatest MPAObserver disguise ysm_02_jk");
            }
            case "other-range-out" -> { command(client, "mpatest MPAObserver reset"); command(client, "tp MPAObserver 8.5 -60 0.5"); }
            case "other-range-in" -> command(client, "tp MPAObserver 8.0 -60 0.5");
            // B's independent observer fixture issues undisguise as the actual unmodded player.
            case "other-undisguise", "other-undisguise-again", "other-second-undisguise" -> command(client, "mpatest MPAObserver reset");
            case "other-native-move" -> {
                PlayerEntity other = otherPlayer(client);
                restoredPlayerX = other == null ? Double.NaN : other.getX();
                command(client, "mpatest MPAObserver walk");
            }
            case "other-redisguise" -> command(client, "mpatest MPAObserver disguise ysm_01_jk");
            case "other-second-model" -> command(client, "mpatest MPAObserver disguise ysm_02_jk");
            case "other-first-model" -> command(client, "mpatest MPAObserver disguise ysm_01_jk");
            case "own-second-model" -> {
                var current = own(client).stream().findFirst().orElse(null);
                ownSecondInitialInstance = current == null ? "" : current.instance();
                ownSecondInitialHash = current == null ? "" : current.assetHash();
                ownSecondSourceInstance = ""; ownSecondTargetRequested = false; ownSecondReadyTicks = 0;
                ownSecondSourceProof = Map.of();
                // server-reload leaves this player in ysm_02_jk. An identical disguise command is
                // intentionally idempotent; establish a genuine different-model source first.
                command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
            }
            case "own-second-undisguise" -> command(client, "meplayeractions undisguise");
            case "own-first-model" -> command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
            case "undisguise" -> command(client, "meplayeractions undisguise");
            case "preview" -> runtime.preview("openysm_default");
            case "preview-end" -> runtime.preview("off");
            default -> { }
        }
    }

    private void capture(MinecraftClient client, String name) {
        captured = true;
        Map<String, Object> diagnostic = ModelRenderer.diagnostics();
        List<ClientRuntime.RenderBinding> bindings = own(client);
        List<?> models = (List<?>) diagnostic.get("models");
        double height = models.stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).filter(model -> model.owner().equals(client.player.getUuid().toString()))
                .mapToDouble(model -> model.maxY() - model.minY()).findFirst().orElse(0);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("stage", name); observation.put("tick", ticks); observation.put("nativePose", client.player.getPose().name());
        observation.put("onGround", client.player.isOnGround()); observation.put("vehicle", client.player.hasVehicle());
        if(name.startsWith("gsit-")) observation.put("naturalCameraBeforeTestOverride", gsitNaturalCamera);
        observation.put("bindings", bindings.stream().map(binding -> Map.of(
                "owner", binding.owner().toString(), "instance", binding.instance(), "hash", binding.assetHash(),
                "layers", binding.layers(), "tick", binding.serverTick(), "x", binding.x(), "y", binding.y(),
                "z", binding.z(), "scale", binding.scale(), "hidePlayer", binding.hidePlayer())).toList());
        observation.put("renderer", diagnostic); observations.add(observation);
        Map<String, Object> push = runtime.serverPushDiagnostics();
        observation.put("serverPush", push);
        observation.put("resourcePackIndexPresent", packIndexPresent(client));
        if (name.equals("push-cache-reconnect")) observation.put("reconnect", Map.of(
                "actualDisconnectObserved", pushReconnectDisconnected, "before", pushReconnectBaseline,
                "previousInstance", pushReconnectPreviousInstance, "expectedHash", pushReconnectHash,
                "ticks", pushReconnectTicks, "readyAt", pushReconnectReadyAt));
        if (name.equals("push-asset-failed") || name.equals("push-asset-recovered"))
            observation.put("assetFault", pushFaultEvidence(client, name));
        if (name.equals("own-second-model")) observation.put("modelChangeSource", ownSecondSourceProof);
        check(name + "NoAssetRequest", pushCount(push, "assetRequestPacketsSent") == 0,
                "Real successful send counter for asset_request=" + pushCount(push, "assetRequestPacketsSent"));
        checkPushLifecycle(client, name, push);
        if (name.equals("pack-disabled") || name.equals("pack-restored")) observation.put("packLifecycle", packEvidence(client, name, diagnostic));
        if (name.startsWith("local-appearance-")) {
            observation.put("localAppearance", runtime.localAppearance());
            observation.put("requestPacketsSent", runtime.requestPacketsSent());
        }
        String layers = bindings.stream().flatMap(binding -> binding.layers().stream()).map(layer -> layer.animation())
                .reduce((a, b) -> a + "," + b).orElse("");
        List<ClientRuntime.RenderBinding> otherBindings = otherBindings(client);
        PlayerEntity other = otherPlayer(client);
        String otherLayers = otherBindings.stream().flatMap(binding -> binding.layers().stream()).map(layer -> layer.animation())
                .reduce((a, b) -> a + "," + b).orElse("");
        double otherHeight = models.stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).filter(model -> model.owner().equals(OTHER.toString()))
                .mapToDouble(model -> model.maxY() - model.minY()).findFirst().orElse(0);
        observation.put("otherLayers", otherLayers); observation.put("otherLocalBindings", otherBindings.size());
        observation.put("otherAnimationBindings", otherBindings.stream().map(binding -> Map.of(
                "instance", binding.instance(), "hash", binding.assetHash(), "tick", binding.serverTick(), "layers", binding.layers())).toList());
        observation.put("otherAccessoryAuthority", runtime.accessoryState(OTHER));
        if (name.equals("other-extra0")) observation.put("remoteAccessoryFixture", Map.of(
                "startedAtTick", remoteExtraStartedAt, "lengthTicks", remoteExtraLengthTicks,
                "firstChangedAge", Double.isFinite(remoteExtraFirstChangedAge) ? remoteExtraFirstChangedAge : -1,
                "completedAtStageTick", remoteExtraCompletedAt));
        observation.put("otherNativeEntityPresent", other != null);
        if (other != null) observation.put("otherNative", Map.of("pose", other.getPose().name(), "onGround", other.isOnGround(),
                "sleeping", other.isSleeping(), "vehicle", other.hasVehicle(), "x", other.getX(), "y", other.getY(), "z", other.getZ()));
        command(client, "mpatest " + (name.startsWith("other-") ? "MPAObserver" : PLAYER) + " status");
        switch (name) {
            case "push-first-download" -> {
                check("serverPushNegotiated", pushNegotiated(push), "Actual hello_ack mode/capabilities=" + push);
                check("serverPushPlainMePack", !packIndexPresent(client)
                                && client.getResourcePackManager().getEnabledIds().stream().anyMatch(id -> id.startsWith("file/"))
                                && height > 0 && !bindings.isEmpty(),
                        "Ordinary enabled ME resource pack has no MPA model index; actual own pushed mesh height=" + height
                                + "; profiles=" + client.getResourcePackManager().getEnabledIds());
                check("serverPushColdCacheDownloaded", pushCount(push, "offersReceived") > 0 && pushCount(push, "cacheMisses") > 0 && pushCount(push, "transfersBegun") > 0
                                && pushCount(push, "transfersCompleted") > 0 && pushCount(push, "gpuPrepared") > 0
                                && pushCount(push, "renderReadySent") > 0 && pushCount(push, "renderAcks") > 0,
                        "Cold disposable clone proves offer/miss/complete data/SHA+parse/GPU/ready/ACK; actual counters=" + push);
                JsonObject asset = packAuthority == null ? null : packAuthority.getAsJsonObject("clientAssetStatus");
                check("serverPushDefaultJarAssetSource", asset != null && asset.has("readSucceeded") && asset.get("readSucceeded").getAsBoolean()
                                && "ready".equals(asset.get("state").getAsString()) && "jar".equals(asset.get("source").getAsString())
                                && packAuthorityMatches(client, true),
                        "Fresh real ModelAssets result and viewer lease prove isolated initial OWN absence -> default plugin JAR asset: " + packAuthority);
                observation.put("serverAuthority", packAuthority);
            }
            case "server-trailing" -> {
                var binding=bindings.stream().findFirst().orElse(null);
                check("explicitTrailingMode",binding!=null && binding.motionSource().equals("server-timeline"),runtime.status().toString());
            }
            case "boat" -> check("boatDedicatedLocalClip",height>0 && layers.contains("boat"),layers);
            case "extra" -> check("extraDedicatedLocalClip",height>0 && layers.contains("extra1"),layers);
            case "hunger" -> check("hungerExpressionRuntime",height>0 && !bindings.isEmpty() && bindings.getFirst().model().ysmPhysics(),"Original scripts enabled with low food level");
            case "idle" -> { idleHeight = height; check("modelIdle", height > 0 && (long) diagnostic.get("drawnBatches") > 0, "height=" + height); }
            case "local-appearance-server-base" -> {
                localServerBaseline = bindings.stream().findFirst().orElse(null);
                localRemoteBaseline = otherBindings;
                check("localAppearanceServerBaseline", localServerBaseline != null && height > 0
                                && !localServerBaseline.instance().startsWith("local-self:"),
                        "Server-owned local renderer binding before private override=" + localServerBaseline);
            }
            case "local-appearance-own", "local-appearance-action" -> {
                var local = bindings.stream().findFirst().orElse(null);
                var nativePos = client.player.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
                check(name + "PrivateModelAndTransform", local != null && height > 0 && local.instance().startsWith("local-self:")
                                && local.motionSource().equals("local-self") && localServerBaseline != null
                                && !local.assetHash().equals(localServerBaseline.assetHash()) && Math.abs(local.scale() - .65) < .00001
                                && Math.abs(local.x() - nativePos.x - .25) < .000001
                                && Math.abs(local.y() - nativePos.y - .35) < .000001
                                && Math.abs(local.z() - nativePos.z + .2) < .000001,
                        "Private reference model/scale/XYZ; local=" + local + "; native=" + nativePos);
                check(name + "RemoteBindingUnchanged", sameBindings(localRemoteBaseline, otherBindings),
                        "Only own model is overridden; remote bindings=" + otherBindings);
                check(name + "NoServerRequest", runtime.requestPacketsSent() == localRequestBaseline,
                        "C2S action requests before=" + localRequestBaseline + "; current=" + runtime.requestPacketsSent());
                if (name.equals("local-appearance-action")) check("localAppearanceLocalActionLayer", layers.contains("extra1"), layers);
            }
            case "local-appearance-off" -> {
                var restored = bindings.stream().findFirst().orElse(null);
                check("localAppearanceRestoresServerBinding", restored != null && height > 0 && localServerBaseline != null
                                && restored.instance().equals(localServerBaseline.instance()) && restored.assetHash().equals(localServerBaseline.assetHash())
                                && Math.abs(restored.scale() - localServerBaseline.scale()) < .000001
                                && Math.abs(restored.x() - localServerBaseline.x()) < .000001
                                && Math.abs(restored.y() - localServerBaseline.y()) < .000001
                                && Math.abs(restored.z() - localServerBaseline.z()) < .000001 && !runtime.localAppearance().enabled(),
                        "Disabling private appearance restores unchanged server instance=" + restored);
                check("localAppearanceDisableNoServerRequest", runtime.requestPacketsSent() == localRequestBaseline,
                        "C2S requests=" + runtime.requestPacketsSent() + "; baseline=" + localRequestBaseline);
            }
            case "crawl" -> {
                check("authoritativeCrawlState", (layers.contains("climb") || layers.contains("crawl")), "server layer=" + layers + "; client recalculated pose=" + client.player.getPose());
                check("crawlGeometry", height > 0 && height < idleHeight * 0.8, "idle=" + idleHeight + "; crawl=" + height);
            }
            case "bed" -> {
                check("bedState", client.player.isSleeping() && layers.contains("bed_sleep"), layers);
                bedGeometry(client, models, client.player.getUuid(), 2, 2, "ownBed");
            }
            case "gsit-sit" -> check("gsitRealSit", height > 0 && layers.contains("sit"), layers);
            case "gsit-crawl" -> check("gsitRealCrawl", height > 0 && (layers.contains("climb") || layers.contains("crawl")), layers);
            case "gsit-lay" -> {
                check("gsitLayMapped", height > 0 && layers.contains("bed_sleep"), "GSit floor anchor; " + layers);
                var ownMesh = models.stream().filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                        .filter(frame -> frame.owner().equals(client.player.getUuidAsString())).findFirst().orElse(null);
                check("gsitLayFloorContact", ownMesh != null && ownMesh.minY() >= -60.15 && ownMesh.minY() <= -59.85,
                        "Actual GSit floor mesh=" + ownMesh);
            }
            case "gsit-reset" -> check("gsitReturnedToIdle", height > 0 && layers.contains("idle") && !(layers.contains("climb") || layers.contains("crawl")) && !layers.contains("sleep"), layers);
            case "gsit-undisguise" -> check("gsitUndisguiseRestoresNative", bindings.isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "gsit-redisguise" -> check("gsitRedisguiseLocalModel", height > 0 && layers.contains("bed_sleep"), layers);
            case "ride" -> check("rideState", client.player.hasVehicle() && layers.contains("minecart"), layers);
            case "firstperson" -> check("firstPersonOwnModelHidden", height == 0 && (int) diagnostic.get("firstPersonSelfSkipped") > 0, diagnostic.toString());
            case "firstperson-extra0" -> {
                var variables = ModelRenderer.expressionVariables(client.player.getUuid());
                check("firstPersonAccessoryTimelineRuns", height == 0 && (int) diagnostic.get("firstPersonSelfSkipped") > 0
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                        "Hidden source extra0 commits its 1.2083/1.25 s accessory events; variables=" + variables);
            }
            case "extra0-restored" -> {
                var variables = ModelRenderer.expressionVariables(client.player.getUuid());
                check("thirdPersonAccessoryStatePersists", height > 0 && !layers.contains("extra0")
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                        "Finished first-person action persists after F5; layers=" + layers + "; variables=" + variables);
            }
            case "gsit-firstperson" -> check("gsitFirstPersonOwnModelHidden", height == 0 && (int) diagnostic.get("firstPersonSelfSkipped") > 0,
                    "naturalCamera=" + gsitNaturalCamera + "; renderer=" + diagnostic);
            case "gsit-thirdperson" -> {
                @SuppressWarnings("unchecked") Map<String, Object> camera=(Map<String, Object>)diagnostic.get("camera");
                var binding=bindings.stream().findFirst().orElse(null);
                double distance=binding==null?0:Math.sqrt(Math.pow(((Number)camera.get("x")).doubleValue()-binding.x(),2)
                        +Math.pow(((Number)camera.get("y")).doubleValue()-binding.y()-.5,2)
                        +Math.pow(((Number)camera.get("z")).doubleValue()-binding.z(),2));
                check("gsitNaturalThirdPersonVisible", height>0 && Boolean.FALSE.equals(camera.get("firstPerson")) && distance>1.5,
                        "Natural camera distance="+distance+"; camera="+camera);
            }
            case "resource-reload" -> check("resourceReload", !bindings.isEmpty() && height > 0 && (int) diagnostic.get("textures") > 0, diagnostic.toString());
            case "local-off" -> check("localOffFallback", bindings.isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "local-on" -> check("localOnReacquired", !bindings.isEmpty() && height > 0, runtime.status().toString());
            case "npc" -> { npcIdleHeight = height; check("secondModelGpu", height > 0 && !bindings.isEmpty(), "npc height=" + height); }
            case "npc-crawl" -> check("npcCrawlGeometry", height > 0 && height < npcIdleHeight * 0.8, "idle=" + npcIdleHeight + "; crawl=" + height);
            case "crawl-side", "npc-crawl-side" -> check(name + "Visible", height > 0 && (layers.contains("climb") || layers.contains("crawl")), "camera=" + client.getCameraEntity().getType() + "; height=" + height);
            case "menu" -> {
                check("actionsPanel", client.currentScreen instanceof ActionsScreen && !runtime.actions().isEmpty(), "Configured translated actions=" + runtime.actions().size());
                check("previewRespectsServerBinding", !bindings.isEmpty() && bindings.stream().noneMatch(binding -> binding.instance().equals("preview")), "Owned server binding prevents local preview duplication");
            }
            case "self-hidden" -> check("selfDisplayToggleOff", height == 0 && own(client).isEmpty(), runtime.status().toString());
            case "self-hidden-extra0" -> {
                var variables = ModelRenderer.expressionVariables(client.player.getUuid());
                check("hiddenSelfAccessoryTimelineRuns", height == 0 && own(client).isEmpty()
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                        "Local self toggle hides drawing and preserves source scripts; variables=" + variables);
            }
            case "self-restored" -> {
                check("selfDisplayToggleOn", height > 0 && !bindings.isEmpty(), runtime.status().toString());
                var variables = ModelRenderer.expressionVariables(client.player.getUuid());
                check("hiddenSelfAccessoryStatePersists", height > 0 && !layers.contains("extra0")
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                        "Accessory state survives local hide/show; variables=" + variables);
            }
            case "server-reload" -> check("serverReloadReacquired", height > 0 && !bindings.isEmpty(), runtime.status().toString());
            case "other-idle" -> { otherIdleHeight = otherHeight; check("remoteLocalModel", otherHeight > 0 && !otherBindings.isEmpty(), "MPAObserver has no MPA client; local height=" + otherHeight); }
            case "other-extra0" -> {
                var variables = ModelRenderer.expressionVariables(OTHER);
                check("remoteExtraActionObserved", remoteExtraSeen && remoteExtraEventChecked,
                        "Observed source extra0 startedAt=" + remoteExtraStartedAt + "; length=" + remoteExtraLengthTicks + "; firstChangedAge=" + remoteExtraFirstChangedAge);
                check("remoteAccessoryNotPremature", remoteExtraEarlySamples >= 3 && remoteExtraEarlyValid,
                        "Pre-event samples=" + remoteExtraEarlySamples + "; initial state must remain 0 before source event");
                check("remoteAccessoryStateBeforeRange", remoteExtraCompletedAt >= 0 && otherHeight > 0 && !otherLayers.contains("extra0")
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1
                                && runtime.accessoryState(OTHER).getOrDefault("a", 0d) == 1
                                && runtime.accessoryState(OTHER).getOrDefault("b", 0d) == 1,
                        "Remote source action completed before range transfer; firstChangedAge=" + remoteExtraFirstChangedAge
                                + "; variables=" + variables + "; authority=" + runtime.accessoryState(OTHER));
            }
            case "other-crawl" -> check("remoteCrawl", otherHeight > 0 && otherHeight < otherIdleHeight * .8 && (otherLayers.contains("climb") || otherLayers.contains("crawl")), "idle=" + otherIdleHeight + "; crawl=" + otherHeight + "; " + otherLayers);
            case "other-bed" -> {
                check("remoteBed", otherHeight > 0 && otherLayers.contains("bed_sleep"), "server layer=" + otherLayers + "; native entity present=" + (other != null));
                bedGeometry(client, models, OTHER, 5, 2, "otherBed");
            }
            case "other-ride" -> check("remoteMinecart", otherHeight > 0 && otherLayers.contains("minecart"), "server layer=" + otherLayers + "; native entity present=" + (other != null));
            case "other-range-out" -> check("distanceAt8Hidden", otherBindings.isEmpty() && otherHeight == 0, "Remote binding count=" + otherBindings.size());
            case "other-range-in" -> {
                check("distanceWithin8Visible", !otherBindings.isEmpty() && otherHeight > 0, "Remote binding count=" + otherBindings.size() + "; height=" + otherHeight);
                var variables = ModelRenderer.expressionVariables(OTHER);
                check("remoteAccessoryStateAfterRange", otherHeight > 0 && !otherLayers.contains("extra0")
                                && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                        "New viewer binding restores server accessory state without replay; variables=" + variables);
            }
            case "other-undisguise", "other-undisguise-again", "other-second-undisguise" -> {
                long copies = client.world.getPlayers().stream().filter(player -> player.getUuid().equals(OTHER)).count();
                check(name + "RestoresNativePlayer", other != null && !other.isInvisibleTo(client.player)
                        && otherBindings.isEmpty() && !runtime.shouldHidePlayer(OTHER) && copies == 1,
                        "Native player present=" + (other != null) + "; visible=" + (other != null && !other.isInvisibleTo(client.player))
                                + "; bindings=" + otherBindings.size() + "; native copies=" + copies);
            }
            case "other-native-move" -> check("undisguisedRemoteKeepsMoving", other != null && !other.isInvisibleTo(client.player)
                    && Double.isFinite(restoredPlayerX) && other.getX() - restoredPlayerX > .3 && otherBindings.isEmpty(),
                    "Original x=" + restoredPlayerX + "; current x=" + (other == null ? "missing" : other.getX()));
            case "other-redisguise", "other-first-model" -> check(name + "LocalModelReturns", otherHeight > 0 && !otherBindings.isEmpty()
                    && runtime.shouldHidePlayer(OTHER), "Remote binding count=" + otherBindings.size() + "; height=" + otherHeight);
            case "other-second-model", "own-second-model" -> {
                UUID owner=name.startsWith("other-")?OTHER:client.player.getUuid();
                var local=runtime.renderBindings().stream().filter(binding->binding.owner().equals(owner)).findFirst();
                check(name+"ReferenceModelLocalWithExpressions",local.isPresent() && local.get().model().ysmPhysics()
                        && ModelRenderer.has(local.get().assetHash()),"Reference retains original expressions and is rendered locally");
            }
            case "own-second-undisguise" -> check("ownSecondUndisguiseRestoresPlayer", bindings.isEmpty()
                    && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "own-first-model" -> check("ownFirstModelReturnsAfterReference", height > 0 && !bindings.isEmpty(), "Local model height=" + height);
            case "undisguise" -> check("undisguiseRestoresPlayer", bindings.isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "preview" -> check("localPreview", height > 0 && bindings.stream().anyMatch(binding -> binding.instance().equals("preview")), "Bundled model preview height=" + height);
            case "preview-end" -> check("localPreviewReleased", bindings.isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            default -> { }
        }
        if (!models.isEmpty()) {
            boolean uprightYaw = models.stream().filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                    .allMatch(model -> Math.abs(model.appliedYaw() - (180 - model.bodyYaw())) < 0.001);
            check(name + "NoNativePoseRotation", uprightYaw, "Only world yaw is applied by the renderer; root animation owns pose rotation");
        }
        screenshot(client, String.format("%02d-%s", stage + 1, name));
    }

    private void prepareNativeJump(MinecraftClient client) {
        if (jumpPressedAt >= 0) {
            // Hold for one simulation tick, then release to avoid repeated creative jumps.
            if (stageTicks >= jumpPressedAt + 2) client.options.jumpKey.setPressed(false);
            return;
        }
        boolean standing = client.player.isOnGround() && !client.player.hasVehicle() && !client.player.isSleeping()
                && client.player.getPose() == net.minecraft.entity.EntityPose.STANDING
                && Math.abs(client.player.getY() + 60) < .02;
        jumpGroundedTicks = stageTicks >= 10 && standing ? jumpGroundedTicks + 1 : 0;
        if (jumpGroundedTicks >= 6) {
            jumpPressedAt = stageTicks;
            jumpGroundY = client.player.getY();
            client.options.jumpKey.setPressed(true);
            LOGGER.info("E2E native jump key pressed after {} stable grounded samples at stage tick {}", jumpGroundedTicks, stageTicks);
        }
    }

    private static boolean sameBindings(List<ClientRuntime.RenderBinding> before, List<ClientRuntime.RenderBinding> after) {
        return before.size() == after.size() && before.stream().allMatch(original -> after.stream().anyMatch(current ->
                current.owner().equals(original.owner()) && current.instance().equals(original.instance())
                        && current.assetHash().equals(original.assetHash()) && current.scale() == original.scale()
                        && Math.abs(current.x() - original.x()) < .000001 && Math.abs(current.y() - original.y()) < .000001
                        && Math.abs(current.z() - original.z()) < .000001));
    }

    private void localAppearanceTick(MinecraftClient client, String name) {
        var local = own(client).stream().findFirst().orElse(null);
        boolean override = !name.equals("local-appearance-off");
        boolean ready = local != null && local.instance().startsWith("local-self:") == override
                && visibleModel(ModelRenderer.diagnostics(), local);
        if (ready && localAppearanceReadyAt < 0) localAppearanceReadyAt = stageTicks;
        if (name.equals("local-appearance-action")) {
            var action = local == null ? null : local.layers().stream()
                    .filter(layer -> layer.layer().equals("manual") && layer.animation().equals("extra1")).findFirst().orElse(null);
            if (ready && action != null) {
                double age = local.serverTick() - action.startedAtTick();
                double length = local.model().animationLengthTicks("extra1") / action.speed();
                if (stageTicks % 5 == 0) fixtureFrames.add(Map.of("stage", name, "stageTick", stageTicks,
                        "instance", local.instance(), "sampleTick", local.serverTick(), "startedAtTick", action.startedAtTick(),
                        "ageTicks", age, "lengthTicks", length, "layers", local.layers()));
                // The real OpenYSM extra1 is 20 ticks long. Observe after its blend-in
                // while it is alive, instead of the old ready+25 capture after stopLocal.
                if (!captured && age >= Math.min(5, length * .5) && age < length) capture(client, name);
            }
        } else if (!captured && ready && localAppearanceReadyAt >= 0 && stageTicks >= localAppearanceReadyAt + 25) capture(client, name);
        if (captured && stageTicks >= localAppearanceReadyAt + 55) { next(client); return; }
        if (stageTicks > 240) {
            check(name + "Ready", false, "Local appearance did not become visible; status=" + runtime.localAppearanceStatus());
            if (!captured) capture(client, name);
            next(client);
        }
    }

    private void beginOwnAccessory(MinecraftClient client, String name) {
        var binding = runtime.animationBindings().stream()
                .filter(value -> value.owner().equals(client.player.getUuid())).findFirst().orElse(null);
        ownAccessoryInstance = binding == null ? "" : binding.instance();
        ownAccessorySeen = false; ownAccessoryEventChecked = false; ownAccessoryEarlyValid = true;
        ownAccessoryEarlySamples = 0; ownAccessoryCompletedAt = -1; ownAccessoryStartedAt = 0;
        ownAccessoryLengthTicks = 0; ownAccessoryFirstChangedAge = Double.NaN;
        var authority = runtime.accessoryState(client.player.getUuid());
        check(ownAccessoryPrefix(name) + "StartsClear", binding != null
                        && authority.getOrDefault("a", -1d) == 0 && authority.getOrDefault("b", -1d) == 0,
                "Same real owner instance=" + ownAccessoryInstance + "; authoritative accessories before extra0=" + authority);
        command(client, "meplayeractions play extra0");
    }

    private static String ownAccessoryPrefix(String name) {
        return name.equals("firstperson-extra0") ? "firstPersonAccessory" : "hiddenSelfAccessory";
    }

    /** A fixed deadline in the accepted action clock, independent of when the expected value appears. */
    private void ownAccessoryTick(MinecraftClient client, String name) {
        String prefix = ownAccessoryPrefix(name);
        // showSelf=false suppresses renderBindings, while its real animation instance
        // must continue sampling. Read that clock without requiring submitted geometry.
        var binding = runtime.animationBindings().stream().filter(value -> value.owner().equals(client.player.getUuid())
                && value.instance().equals(ownAccessoryInstance)).findFirst().orElse(null);
        if (binding != null) {
            var active = binding.layers().stream().filter(layer -> layer.layer().equals("manual")
                    && layer.animation().equals("extra0")).findFirst().orElse(null);
            if (!ownAccessorySeen && active != null) {
                ownAccessorySeen = true; ownAccessoryStartedAt = active.startedAtTick();
                ownAccessoryLengthTicks = binding.model().animationLengthTicks("extra0") / active.speed();
            }
            if (ownAccessorySeen) {
                double age = binding.serverTick() - ownAccessoryStartedAt;
                var authority = runtime.accessoryState(client.player.getUuid());
                var variables = ModelRenderer.expressionVariables(client.player.getUuid());
                boolean changed = authority.getOrDefault("a", -1d) == 1 && authority.getOrDefault("b", -1d) == 1;
                if (changed && !Double.isFinite(ownAccessoryFirstChangedAge)) ownAccessoryFirstChangedAge = age;
                if (age < 20) {
                    ownAccessoryEarlySamples++;
                    ownAccessoryEarlyValid &= authority.getOrDefault("a", -1d) == 0 && authority.getOrDefault("b", -1d) == 0;
                }
                // Source events occur at 24.166/25 ticks. ME's authoritative property
                // clock includes LERPIN and its snapshot can follow the rebased local
                // event; use the same bounded 35-tick deadline as the remote fixture.
                // Never wait for a/b=1 to decide when to assert or capture.
                if (!ownAccessoryEventChecked && age >= 35) {
                    ownAccessoryEventChecked = true;
                    check(prefix + "EventDeadline", changed
                                    && variables.getOrDefault("variable.roaming.a", -1d) == 1
                                    && variables.getOrDefault("variable.roaming.b", -1d) == 1,
                            "Accepted manual action age=" + age + "; source events=24.166/25 ticks; first authoritative change="
                                    + ownAccessoryFirstChangedAge + "; authority=" + authority + "; instance=" + binding.instance());
                    capture(client, name);
                }
                if (stageTicks % 5 == 0) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("stage", name); sample.put("stageTick", stageTicks); sample.put("instance", binding.instance());
                    sample.put("sampleTick", binding.serverTick()); sample.put("startedAtTick", ownAccessoryStartedAt);
                    sample.put("ageTicks", age); sample.put("sourceLengthTicks", ownAccessoryLengthTicks);
                    sample.put("layers", binding.layers()); sample.put("authority", authority);
                    sample.put("variables", Map.of("a", variables.getOrDefault("variable.roaming.a", -1d),
                            "b", variables.getOrDefault("variable.roaming.b", -1d)));
                    if (Double.isFinite(ownAccessoryFirstChangedAge)) sample.put("firstAuthoritativeChangeAge", ownAccessoryFirstChangedAge);
                    fixtureFrames.add(sample);
                }
                if (ownAccessoryCompletedAt < 0 && age >= ownAccessoryLengthTicks + 10 && active == null) {
                    ownAccessoryCompletedAt = stageTicks;
                    check(prefix + "ActionCompleted", ownAccessoryEventChecked && captured,
                            "Actual manual source ended before restoring visibility; age=" + age + "; instance=" + binding.instance());
                }
            }
        }
        if (captured && ownAccessoryCompletedAt >= 0 && stageTicks >= ownAccessoryCompletedAt + 12) {
            check(prefix + "NotPremature", ownAccessoryEarlySamples >= 3 && ownAccessoryEarlyValid,
                    "Actual pre-event samples=" + ownAccessoryEarlySamples + "; a/b remain 0 before source event");
            next(client); return;
        }
        if (stageTicks > 240) {
            check(prefix + "TimelineCompleted", false, "extra0 did not start/reach its fixed deadline/finish in the same real instance; started="
                    + ownAccessorySeen + "; eventChecked=" + ownAccessoryEventChecked);
            if (!captured) capture(client, name);
            next(client);
        }
    }

    private void recordJumpFrame(MinecraftClient client, String name, PlayerEntity entity, List<ClientRuntime.RenderBinding> bindings) {
        if (entity == null) return;
        if (stageTicks > 55 && stageTicks % 5 != 0) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", name); row.put("stageTick", stageTicks); row.put("onGround", entity.isOnGround());
        row.put("pose", entity.getPose().name()); row.put("vehicle", entity.hasVehicle());
        row.put("position", List.of(entity.getX(), entity.getY(), entity.getZ()));
        row.put("velocity", List.of(entity.getVelocity().x, entity.getVelocity().y, entity.getVelocity().z));
        row.put("jumpKeyPressed", name.equals("jump") && client.options.jumpKey.isPressed());
        row.put("layers", bindings.stream().flatMap(binding -> binding.layers().stream()).toList());
        fixtureFrames.add(row);
    }

    private static boolean visibleModel(Map<String, Object> diagnostic, ClientRuntime.RenderBinding binding) {
        return ((List<?>) diagnostic.get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).anyMatch(model -> model.owner().equals(binding.owner().toString())
                        && model.hash().equals(binding.assetHash()) && model.vertices() > 0 && model.maxY() > model.minY());
    }

    private void resourceReloadTick(MinecraftClient client) {
        var binding = own(client).stream().findFirst().orElse(null);
        Map<String, Object> diagnostic = ModelRenderer.diagnostics();
        long frameNumber = ((Number) diagnostic.get("extractedFrames")).longValue();
        if (reload != null && reload.isCompletedExceptionally()) {
            check("resourceReload", false, "Resource reload future completed exceptionally");
            capture(client, "resource-reload"); next(client); return;
        }
        boolean ready = reload != null && reload.isDone() && binding != null && clearWorldTicks >= 10;
        if (ready && reloadReadyAt < 0) {
            reloadReadyAt = stageTicks;
            reloadFrameBaseline = frameNumber;
            LOGGER.info("E2E reload reacquired binding at stage tick {}, extracted frame {}", stageTicks, frameNumber);
        }
        boolean freshVisible = ready && reloadReadyAt >= 0 && frameNumber >= reloadFrameBaseline + 2
                && visibleModel(diagnostic, binding);
        reloadVisibleTicks = freshVisible ? reloadVisibleTicks + 1 : 0;
        if (stageTicks % 5 == 0) fixtureFrames.add(Map.of(
                "stage", "resource-reload", "stageTick", stageTicks, "bindingReady", ready,
                "readyAtStageTick", reloadReadyAt, "freshVisible", freshVisible,
                "visibleTicks", reloadVisibleTicks, "frameBaseline", reloadFrameBaseline, "renderer", diagnostic));
        if (!captured && reloadVisibleTicks >= 10) capture(client, "resource-reload");
        if (captured && freshVisible && stageTicks >= reloadReadyAt + 30) { next(client); return; }
        if (stageTicks > 400) {
            check("resourceReload", false, "Reload failed to retain a fresh own model after binding acknowledgement; renderer=" + diagnostic);
            if (!captured) capture(client, "resource-reload");
            next(client);
        }
    }

    private static long pushCount(Map<String, Object> diagnostics, String key) {
        return diagnostics.get(key) instanceof Number count ? count.longValue() : -1;
    }

    private static List<Map<?, ?>> pushRows(Map<String, Object> diagnostics, String key) {
        if (!(diagnostics.get(key) instanceof Collection<?> rows)) return List.of();
        return rows.stream().filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value).toList();
    }

    private static boolean pushNegotiated(Map<String, Object> diagnostics) {
        return Boolean.TRUE.equals(diagnostics.get("negotiated")) && "server-push".equals(diagnostics.get("assetMode"))
                && diagnostics.get("capabilities") instanceof Collection<?> capabilities && capabilities.contains("server_push_models");
    }

    private static boolean samePushIdentity(Map<?, ?> row, ClientRuntime.RenderBinding binding) {
        return binding.owner().toString().equals(row.get("owner")) && binding.instance().equals(row.get("instance"))
                && binding.assetHash().equals(row.get("hash"));
    }

    private static boolean currentPushAuthorization(Map<String, Object> push) {
        List<Map<?, ?>> raw = pushRows(push, "bindings");
        // A granted hash can finish for another still-authorized owner of that same hash.
        // The protocol tests verify its exact issuance identity; this check verifies current hash authority.
        return pushRows(push, "activeOffers").stream().allMatch(offer -> raw.stream().anyMatch(binding ->
                !String.valueOf(offer.get("hash")).isBlank() && offer.get("hash").equals(binding.get("hash"))));
    }

    private void checkPushLifecycle(MinecraftClient client, String name, Map<String, Object> push) {
        List<Map<?, ?>> raw = pushRows(push, "bindings");
        boolean renderedAuthorized = runtime.renderBindings().stream()
                .filter(binding -> !binding.instance().equals("preview") && !binding.instance().startsWith("local-self:"))
                .allMatch(binding -> raw.stream().anyMatch(row -> samePushIdentity(row, binding)
                        && Boolean.TRUE.equals(row.get("active")) && Boolean.TRUE.equals(row.get("readySent"))
                        && "ready".equals(row.get("serverAssetStatus"))));
        check(name + "PushAuthorizationCurrent", currentPushAuthorization(push) && renderedAuthorized,
                "In-flight hashes require a current raw server binding; actual server meshes require current owner/instance/hash and ready ACK: " + push);
        UUID released = Set.of("other-range-out", "other-undisguise", "other-undisguise-again", "other-second-undisguise").contains(name)
                ? OTHER : Set.of("own-second-undisguise", "undisguise", "preview", "preview-end").contains(name) ? client.player.getUuid() : null;
        if (released != null) check(name + "PushOwnerReleased", raw.stream().noneMatch(row -> released.toString().equals(row.get("owner")))
                        && runtime.renderBindings().stream().filter(binding -> !binding.instance().equals("preview") && !binding.instance().startsWith("local-self:"))
                        .noneMatch(binding -> binding.owner().equals(released))
                        && pushRows(push, "activeOffers").stream().filter(row -> released.toString().equals(row.get("owner")))
                        .allMatch(offer -> raw.stream().anyMatch(row -> !released.toString().equals(row.get("owner")) && row.get("hash").equals(offer.get("hash")))),
                "Released owner has no raw binding or server-render binding; retained shared-hash grants require another current authorized owner: owner=" + released + "; diagnostics=" + push);
        String expected = switch (name) {
            case "npc", "own-second-model", "other-second-model", "other-extra0" -> "ysm_02_jk";
            case "own-first-model", "other-first-model", "other-redisguise", "gsit-redisguise" -> "ysm_01_jk";
            default -> "";
        };
        if (!expected.isEmpty()) {
            String owner = (name.startsWith("other-") ? OTHER : client.player.getUuid()).toString();
            Map<?, ?> current = raw.stream().filter(row -> owner.equals(row.get("owner"))).findFirst().orElse(Map.of());
            String previous = name.equals("own-second-model") ? ownSecondSourceInstance : pushPreviousInstances.getOrDefault(owner, "");
            check(name + "PushNewInstance", expected.equals(current.get("modelId")) && Boolean.TRUE.equals(current.get("active"))
                            && !previous.isEmpty() && !String.valueOf(current.get("instance")).equals(previous),
                    "Requested model=" + expected + "; previous observed instance=" + previous + "; current=" + current);
        }
        for (Map<?, ?> row : raw) pushPreviousInstances.put(String.valueOf(row.get("owner")), String.valueOf(row.get("instance")));
    }

    private void ownSecondModelTick(MinecraftClient client) {
        Map<String, Object> push = runtime.serverPushDiagnostics(), diagnostic = ModelRenderer.diagnostics();
        Map<?, ?> raw = pushRows(push, "bindings").stream()
                .filter(row -> client.player.getUuidAsString().equals(row.get("owner"))).findFirst().orElse(Map.of());
        var binding = own(client).stream().findFirst().orElse(null);
        String expected = ownSecondTargetRequested ? "ysm_02_jk" : "ysm_01_jk";
        String previous = ownSecondTargetRequested ? ownSecondSourceInstance : ownSecondInitialInstance;
        boolean ready = binding != null && samePushIdentity(raw, binding) && expected.equals(raw.get("modelId"))
                && Boolean.TRUE.equals(raw.get("active")) && Boolean.TRUE.equals(raw.get("readySent"))
                && "ready".equals(raw.get("serverAssetStatus")) && "jar".equals(raw.get("serverAssetSource"))
                && !previous.isEmpty() && !binding.instance().equals(previous) && visibleModel(diagnostic, binding);
        ownSecondReadyTicks = ready ? ownSecondReadyTicks + 1 : 0;
        if (!ownSecondTargetRequested && ownSecondReadyTicks >= 3) {
            ownSecondSourceInstance = binding.instance();
            ownSecondSourceProof = Map.of("initialInstance", ownSecondInitialInstance, "initialHash", ownSecondInitialHash,
                    "sourceInstance", binding.instance(), "sourceHash", binding.assetHash(),
                    "sourceStageTick", stageTicks, "serverPush", push, "renderer", diagnostic);
            fixtureFrames.add(Map.of("stage", "own-second-model-source", "proof", ownSecondSourceProof));
            check("ownSecondModelDifferentSourceReady", !binding.assetHash().equals(ownSecondInitialHash),
                    "Real ysm_01_jk source has fresh instance, different hash, current authorized JAR asset, ready ACK and actual mesh: " + ownSecondSourceProof);
            // Capture the accepted source before issuing the target command, rather than using an
            // unrelated earlier screenshot of this owner, which was already the target model.
            ownSecondTargetRequested = true; ownSecondReadyTicks = 0;
            command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
        } else if (ownSecondTargetRequested && ownSecondReadyTicks >= 10 && stageTicks >= 30) {
            check("ownSecondModelTargetHashAndSource", binding.assetHash().equals(ownSecondInitialHash),
                    "Real ysm_02_jk returns to its original reference hash with a different instance from accepted ysm_01_jk, current JAR source, ready ACK and actual mesh: " + raw);
            capture(client, "own-second-model"); next(client); return;
        }
        if (stageTicks > 240) {
            check("ownSecondModelChangeCompleted", false,
                    "Did not observe both actual authorized source and new target within 240 ticks; source=" + ownSecondSourceProof + "; current=" + push);
            capture(client, "own-second-model"); next(client);
        }
    }

    private void beginPushReconnect(MinecraftClient client) {
        pushReconnectBaseline = runtime.serverPushDiagnostics();
        var binding = own(client).stream().findFirst().orElse(null);
        pushReconnectPreviousInstance = binding == null ? "" : binding.instance();
        pushReconnectHash = binding == null ? "" : binding.assetHash();
        pushReconnectGeneration = pushCount(pushReconnectBaseline, "generation");
        pushReconnectConnecting = false; pushReconnectDisguiseIssued = false; pushReconnectDisconnected = false;
        pushReconnectTicks = 0; pushReconnectReadyAt = -1;
        check("serverPushReconnectPrerequisites", binding != null && pushNegotiated(pushReconnectBaseline)
                        && pushCount(pushReconnectBaseline, "transfers") == 0 && ModelRenderer.has(pushReconnectHash),
                "The first pushed model is acknowledged and no transfer is in flight: " + pushReconnectBaseline);
        client.disconnect(new TitleScreen(), false);
    }

    private void pushReconnectTick(MinecraftClient client) {
        pushReconnectTicks++;
        if (client.world == null && client.player == null && client.getNetworkHandler() == null) pushReconnectDisconnected = true;
        if (!pushReconnectConnecting && pushReconnectDisconnected && client.currentScreen instanceof TitleScreen
                && client.getOverlay() == null && pushReconnectTicks >= 10) {
            String address = System.getProperty("meplayeractions.e2e.server", "127.0.0.1:25591");
            ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse(address),
                    new ServerInfo("MEPlayerActions cache reconnect", address, ServerInfo.ServerType.OTHER), false, null);
            pushReconnectConnecting = true;
        }
        if (client.player != null && client.world != null && client.getNetworkHandler() != null) {
            stageTicks++;
            if (!pushReconnectDisguiseIssued) {
                runtime.options.showSelf = true; client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
                pushReconnectDisguiseIssued = true;
            }
            Map<String, Object> push = runtime.serverPushDiagnostics();
            var binding = own(client).stream().findFirst().orElse(null);
            boolean ready = pushNegotiated(push) && pushCount(push, "generation") > pushReconnectGeneration
                    && binding != null && !binding.instance().equals(pushReconnectPreviousInstance)
                    && binding.assetHash().equals(pushReconnectHash) && visibleModel(ModelRenderer.diagnostics(), binding)
                    && client.currentScreen == null && client.getOverlay() == null;
            if (!ready) pushReconnectReadyAt = -1;
            else if (pushReconnectReadyAt < 0) pushReconnectReadyAt = stageTicks;
            if (stageTicks % 5 == 0) phase(client, "push-cache-reconnect");
            if (ready && stageTicks >= pushReconnectReadyAt + 20) {
                check("serverPushActualReconnect", pushReconnectDisconnected && pushReconnectConnecting
                                && pushCount(push, "generation") > pushReconnectGeneration,
                        "Actual world/network disconnect, ConnectScreen join, and newer runtime generation; before="
                                + pushReconnectBaseline + "; after=" + push);
                check("serverPushReconnectCacheHitNoDownload", pushCount(push, "cacheHits") > pushCount(pushReconnectBaseline, "cacheHits")
                                && pushCount(push, "offersReceived") > pushCount(pushReconnectBaseline, "offersReceived")
                                && pushCount(push, "gpuPrepared") > pushCount(pushReconnectBaseline, "gpuPrepared")
                                && pushCount(push, "renderReadySent") > pushCount(pushReconnectBaseline, "renderReadySent")
                                && pushCount(push, "transfersBegun") == pushCount(pushReconnectBaseline, "transfersBegun")
                                && pushCount(push, "transfersCompleted") == pushCount(pushReconnectBaseline, "transfersCompleted")
                                && pushCount(push, "cacheMisses") == pushCount(pushReconnectBaseline, "cacheMisses")
                                && pushCount(push, "renderAcks") > pushCount(pushReconnectBaseline, "renderAcks"),
                        "Fresh server offer validates cached raw model, prepares GPU and receives a new ACK without data transfer: " + push);
                capture(client, "push-cache-reconnect"); next(client); return;
            }
        }
        if (pushReconnectTicks > 600) {
            check("serverPushActualReconnect", false, "Reconnect exceeded 600 ticks: " + runtime.serverPushDiagnostics());
            finish(client);
        }
    }

    private void beginPushFault(MinecraftClient client, String name) {
        pushFaultBaseline = runtime.serverPushDiagnostics(); pushInvalidBaseline = Map.of(); pushFaultStableTicks = 0;
        var binding = own(client).stream().findFirst().orElse(null);
        if (name.equals("push-asset-failed")) {
            pushFaultOriginalHash = binding == null ? "" : binding.assetHash();
            pushFaultPreviousInstance = binding == null ? "" : binding.instance();
            pushFaultRequested = true;
        } else pushFaultPreviousInstance = packExpectedInstance;
        packExpectedInstance = ""; packAuthority = null; packReloadCompletedAtMillis = System.currentTimeMillis();
        command(client, "mpatest " + PLAYER + (name.equals("push-asset-failed") ? " push-fault" : " push-repair"));
    }

    private Map<String, Object> pushFaultEvidence(MinecraftClient client, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", name); row.put("stageTick", stageTicks); row.put("before", pushFaultBaseline);
        row.put("invalidBarrier", pushInvalidBaseline); row.put("current", runtime.serverPushDiagnostics());
        row.put("originalHash", pushFaultOriginalHash); row.put("previousInstance", pushFaultPreviousInstance);
        row.put("expectedInstance", packExpectedInstance); row.put("stableTicks", pushFaultStableTicks);
        row.put("serverAuthority", packAuthority); row.put("authorityRead", packAuthorityError);
        row.put("renderer", ModelRenderer.diagnostics());
        return row;
    }

    private void pushFaultTick(MinecraftClient client, String name) {
        boolean failing = name.equals("push-asset-failed");
        if (stageTicks % 10 == 0) command(client, "mpatest " + PLAYER + " status");
        if (stageTicks % 5 == 0) {
            JsonObject status = readPackAuthority(client);
            if (status != null && !status.getAsJsonObject("clientSnapshot").get("instance").getAsString().equals(pushFaultPreviousInstance)) {
                packAuthority = status; packExpectedInstance = status.getAsJsonObject("clientSnapshot").get("instance").getAsString();
            }
        }
        Map<String, Object> push = runtime.serverPushDiagnostics();
        Map<?, ?> raw = pushRows(push, "bindings").stream().filter(row -> client.player.getUuidAsString().equals(row.get("owner")))
                .findFirst().orElse(Map.of());
        JsonObject fault = packAuthority == null ? null : packAuthority.getAsJsonObject("assetFaultFixture");
        JsonObject asset = packAuthority == null ? null : packAuthority.getAsJsonObject("clientAssetStatus");
        boolean authoritative = fault != null && fault.has("readSucceeded") && fault.get("readSucceeded").getAsBoolean()
                && asset != null && asset.has("readSucceeded") && asset.get("readSucceeded").getAsBoolean();
        boolean sameInstance = !packExpectedInstance.isBlank() && packExpectedInstance.equals(raw.get("instance"));
        boolean invalid = authoritative && "invalid".equals(asset.get("state").getAsString()) && "own".equals(asset.get("source").getAsString())
                && fault.get("active").getAsBoolean() && sameInstance && "invalid".equals(raw.get("serverAssetStatus"))
                && "own".equals(raw.get("serverAssetSource")) && "".equals(raw.get("hash"));
        boolean noLocal = own(client).isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid())
                && ((List<?>) ModelRenderer.diagnostics().get("models")).stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).noneMatch(model -> model.owner().equals(client.player.getUuidAsString()));
        if (invalid && pushInvalidBaseline.isEmpty()) pushInvalidBaseline = push;
        boolean quietInvalid = !pushInvalidBaseline.isEmpty()
                && pushCount(push, "transfersBegun") == pushCount(pushInvalidBaseline, "transfersBegun")
                && pushCount(push, "renderAcks") == pushCount(pushInvalidBaseline, "renderAcks")
                && pushRows(push, "activeOffers").stream().noneMatch(row -> client.player.getUuidAsString().equals(row.get("owner")));
        var restored = own(client).stream().findFirst().orElse(null);
        boolean repaired = authoritative && !fault.get("active").getAsBoolean() && fault.get("restoredExactly").getAsBoolean()
                && "ready".equals(asset.get("state").getAsString()) && sameInstance && restored != null
                && restored.instance().equals(packExpectedInstance) && restored.assetHash().equals(pushFaultOriginalHash)
                && visibleModel(ModelRenderer.diagnostics(), restored);
        boolean settled = pushNegotiated(push) && packAuthorityMatches(client, !failing)
                && (failing ? invalid && noLocal && quietInvalid : repaired);
        pushFaultStableTicks = settled ? pushFaultStableTicks + 1 : 0;
        if (stageTicks % 5 == 0) fixtureFrames.add(pushFaultEvidence(client, name));
        if (stageTicks >= 60 && pushFaultStableTicks >= 15 || stageTicks > (failing ? 240 : 400)) {
            if (failing) {
                check("serverPushInvalidOwnAssetAuthoritative", invalid, "Real saved/created OWN {} plus actual plugin reload: " + packAuthority);
                check("serverPushInvalidAssetFallback", settled && noLocal && packAuthorityMatches(client, false),
                        "Server actual ME ownership and ME viewer visible, no lease or stale local own mesh for 15 ticks: " + pushFaultEvidence(client, name));
                check("serverPushInvalidAssetNoStaleTransfer", quietInvalid && pushFaultStableTicks >= 15,
                        "After invalid terminal state, no retry transfer, offer or stale render ACK: " + push);
            } else {
                check("serverPushFaultFixtureRestored", authoritative && fault.get("restoredExactly").getAsBoolean() && !fault.get("active").getAsBoolean(),
                        "Original OWN bytes or original absence restored by guarded ignored helper: " + fault);
                check("serverPushRepairRecovered", repaired && packAuthorityMatches(client, true) && pushFaultStableTicks >= 15,
                        "Actual plugin reload creates a new authorized instance; matching original hash/GPU/mesh/lease/ACK: " + pushFaultEvidence(client, name));
                check("serverPushRepairCacheNoDownload", repaired && pushCount(push, "cacheHits") > pushCount(pushFaultBaseline, "cacheHits")
                                && pushCount(push, "transfersBegun") == pushCount(pushFaultBaseline, "transfersBegun")
                                && pushCount(push, "gpuPrepared") > pushCount(pushFaultBaseline, "gpuPrepared")
                                && pushCount(push, "renderReadySent") > pushCount(pushFaultBaseline, "renderReadySent")
                                && pushCount(push, "renderAcks") > pushCount(pushFaultBaseline, "renderAcks"),
                        "Valid original model is reused only after fresh repair authorization: " + push);
                if (repaired && authoritative && fault.get("restoredExactly").getAsBoolean()) pushFaultRequested = false;
            }
            capture(client, name); next(client);
        }
    }

    private void beginPackDisable(MinecraftClient client) {
        var manager = client.getResourcePackManager();
        originalPackProfiles = List.copyOf(manager.getEnabledIds());
        disabledPackProfiles = manager.getEnabledProfiles().stream()
                .filter(profile -> profile.getId().equals("vanilla") || profile.isRequired())
                .map(profile -> profile.getId()).toList();
        var binding = own(client).stream().findFirst().orElse(null);
        packExpectedInstance = binding == null ? "" : binding.instance();
        packExpectedHash = binding == null ? "" : binding.assetHash();
        packMissingSamples = 0; packNoStaleReady = true;
        check("serverPushPackReloadPrerequisites", binding != null && ModelRenderer.has(packExpectedHash)
                        && !packIndexPresent(client) && pushNegotiated(runtime.serverPushDiagnostics())
                        && originalPackProfiles.stream().anyMatch(id -> !disabledPackProfiles.contains(id)),
                "Real enabled profiles=" + originalPackProfiles + "; keeping vanilla/required=" + disabledPackProfiles
                        + "; instance=" + packExpectedInstance + "; hash=" + packExpectedHash);
        manager.setEnabledProfiles(disabledPackProfiles);
        packSelectionChanged = true;
        beginPackReload(client);
    }

    private void beginPackReload(MinecraftClient client) {
        packReadyAt = -1; packSettledTicks = 0; packFrameBaseline = -1; packDrawBaseline = -1;
        packAuthority = null; packAuthorityError = "Waiting for a fresh MPATestHelper owner status";
        packReloadCallbacks = 0; packReloadCompletedAtMillis = 0; packReloadError = "";
        packReloadStartedAtMillis = System.currentTimeMillis();
        packPushBaseline = runtime.serverPushDiagnostics();
        check(STAGES.get(stage).equals("pack-disabled") ? "serverPushPackDisabledQuiescent" : "serverPushPackRestoredQuiescent",
                pushCount(packPushBaseline, "transfers") == 0 && pushRows(packPushBaseline, "activeOffers").isEmpty(),
                "This unrelated-resource reload has no pending server download or decoder offer at its baseline: " + packPushBaseline);
        long request = ++packReloadRequest;
        reload = client.reloadResources();
        reload.whenComplete((unused, failure) -> {
            long completedAt = System.currentTimeMillis();
            client.execute(() -> {
                if (request != packReloadRequest) return;
                packReloadCallbacks++;
                packReloadCompletedAtMillis = completedAt;
                packReloadError = failure == null ? "" : failure.toString();
            });
        });
    }

    private void packReloadTick(MinecraftClient client, String name) {
        boolean disabling = name.equals("pack-disabled");
        Map<String, Object> diagnostic = ModelRenderer.diagnostics();
        long frameNumber = ((Number) diagnostic.get("extractedFrames")).longValue();
        boolean completed = reload != null && reload.isDone() && !reload.isCompletedExceptionally()
                && packReloadCallbacks == 1 && packReloadCompletedAtMillis >= packReloadStartedAtMillis;
        if (completed && clearWorldTicks >= 10 && packFrameBaseline < 0) {
            packFrameBaseline = frameNumber;
            packDrawBaseline = ((Number) diagnostic.get("drawnBatches")).longValue();
        }
        boolean freshFrames = completed && clearWorldTicks >= 10 && packFrameBaseline >= 0
                && frameNumber >= packFrameBaseline + 2;
        if (completed && stageTicks % 10 == 0) command(client, "mpatest " + PLAYER + " status");
        if (completed && stageTicks % 5 == 0) {
            JsonObject status = readPackAuthority(client);
            if (status != null) packAuthority = status;
        }
        var binding = own(client).stream().findFirst().orElse(null);
        Map<String, Object> push = runtime.serverPushDiagnostics();
        if (freshFrames) { packMissingSamples++; packNoStaleReady &= currentPushAuthorization(push); }
        boolean matchingReady = binding != null && binding.instance().equals(packExpectedInstance)
                && binding.assetHash().equals(packExpectedHash) && ModelRenderer.has(packExpectedHash)
                && visibleModel(diagnostic, binding)
                && ((Number) diagnostic.get("drawnBatches")).longValue() > packDrawBaseline;
        boolean serverMatches = packAuthorityMatches(client, true);
        boolean profilesMatch = List.copyOf(client.getResourcePackManager().getEnabledIds())
                .equals(disabling ? disabledPackProfiles : originalPackProfiles);
        boolean noDownload = pushCount(push, "transfersBegun") == pushCount(packPushBaseline, "transfersBegun")
                && pushCount(push, "transfersCompleted") == pushCount(packPushBaseline, "transfersCompleted");
        boolean freshAck = pushCount(push, "renderReadySent") > pushCount(packPushBaseline, "renderReadySent")
                && pushCount(push, "renderAcks") > pushCount(packPushBaseline, "renderAcks");
        boolean settled = freshFrames && runtime.serverBridgeReady() && pushNegotiated(push) && profilesMatch && serverMatches
                && !packIndexPresent(client) && matchingReady && packNoStaleReady && noDownload && freshAck;
        packSettledTicks = settled ? packSettledTicks + 1 : 0;
        if (settled && packReadyAt < 0) packReadyAt = stageTicks;
        if (stageTicks % 5 == 0) fixtureFrames.add(packEvidence(client, name, diagnostic));
        boolean failed = reload != null && reload.isCompletedExceptionally();
        if (packSettledTicks >= 10 || failed || stageTicks > (disabling ? 240 : 400)) {
            check(disabling ? "packDisableReloadCompleted" : "packRestoreReloadCompleted", completed,
                    "Real reload callbacks=" + packReloadCallbacks + "; started=" + packReloadStartedAtMillis
                            + "; completed=" + packReloadCompletedAtMillis + "; error=" + packReloadError);
            if (disabling) {
                check("serverPushPackDisabledNoIndex", profilesMatch && !packIndexPresent(client),
                        "Enabled now=" + client.getResourcePackManager().getEnabledIds() + "; original=" + originalPackProfiles);
                check("serverPushPackDisabledModelRetained", matchingReady && packMissingSamples >= 10 && packNoStaleReady,
                        "Embedded pushed asset survives unrelated ME pack removal with same instance/hash=" + packExpectedInstance + "/" + packExpectedHash);
                check("serverPushPackDisabledFreshFrames", freshFrames && matchingReady && packSettledTicks >= 10,
                        "Extracted frames=" + frameNumber + "; post-reload baseline=" + packFrameBaseline + "; renderer=" + diagnostic);
                check("serverPushPackDisabledOwnerLeaseSameInstance", serverMatches && packSettledTicks >= 10,
                        "Expected owner instance=" + packExpectedInstance + "; authority=" + packAuthority + "; read=" + packAuthorityError);
            } else {
                check("packRestoredExactProfiles", profilesMatch && !packIndexPresent(client),
                        "Restored enabled profiles=" + client.getResourcePackManager().getEnabledIds() + "; original=" + originalPackProfiles);
                check("packRestoredHashRevalidated", matchingReady && packMissingSamples >= 10 && packNoStaleReady,
                        "Expected instance/hash=" + packExpectedInstance + "/" + packExpectedHash
                                + "; actual post-reload frames=" + packMissingSamples + "; binding=" + binding);
                check("packRestoredOwnerLeaseSameInstance", serverMatches && packSettledTicks >= 10,
                        "Fresh helper authority=" + packAuthority + "; read=" + packAuthorityError);
                check("packRestoredFreshFrames", freshFrames && matchingReady && packSettledTicks >= 10,
                        "Extracted frames=" + frameNumber + "; post-reload baseline=" + packFrameBaseline + "; renderer=" + diagnostic);
                if (completed && profilesMatch) packSelectionChanged = false;
            }
            check(disabling ? "serverPushPackDisabledNoDownloadFreshAck" : "serverPushPackRestoredNoDownloadFreshAck",
                    noDownload && freshAck && packSettledTicks >= 10,
                    "No repeated network data; actual new ready+ACK after GPU/resource reload; before=" + packPushBaseline + "; after=" + push);
            capture(client, name);
            next(client);
        }
    }

    private static boolean packIndexPresent(MinecraftClient client) {
        return client.getResourceManager().getResource(Identifier.of("meplayeractions", "models/index.json")).isPresent();
    }

    private Map<String, Object> packEvidence(MinecraftClient client, String name, Map<String, Object> diagnostic) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", name); row.put("stageTick", stageTicks); row.put("expectedInstance", packExpectedInstance);
        row.put("expectedHash", packExpectedHash); row.put("originalEnabledProfiles", originalPackProfiles);
        row.put("enabledProfiles", List.copyOf(client.getResourcePackManager().getEnabledIds()));
        row.put("indexPresent", packIndexPresent(client)); row.put("expectedGpuAssetPresent", ModelRenderer.has(packExpectedHash));
        row.put("reloadCallbacks", packReloadCallbacks); row.put("reloadStartedAtMillis", packReloadStartedAtMillis);
        row.put("reloadCompletedAtMillis", packReloadCompletedAtMillis); row.put("reloadError", packReloadError);
        row.put("frameBaseline", packFrameBaseline); row.put("drawnBatchBaseline", packDrawBaseline);
        row.put("readyAtStageTick", packReadyAt); row.put("stableTicks", packSettledTicks);
        row.put("authorizationAlwaysCurrent", packNoStaleReady); row.put("postReloadFrameSamples", packMissingSamples);
        row.put("pushBefore", packPushBaseline); row.put("pushAfter", runtime.serverPushDiagnostics());
        row.put("serverAuthority", packAuthority); row.put("authorityRead", packAuthorityError); row.put("renderer", diagnostic);
        return row;
    }

    /** Correlate actual queue submissions with independent native B actions and server inventory. */
    private void beginItemDraw(MinecraftClient client, String name) {
        Map<String,Object> diagnostic = ModelRenderer.diagnostics();
        itemSubmissionBaseline = ((Number)diagnostic.get("submittedItems")).longValue();
        itemFrameBaseline = ((Number)diagnostic.get("extractedFrames")).longValue();
        itemStageStartedAtMillis = System.currentTimeMillis(); itemDrawStableTicks = 0; itemStageSucceeded = false; itemLatestProof = Map.of();
        itemShieldExpectedSource = name.endsWith("-hold") || name.endsWith("-use")
                ? readShieldModelSource(client, name.endsWith("-use")) : Map.of();
        if (name.endsWith("-hold")) {
            handsCleanupPending = true;
            command(client, "mpatest MPAObserver hands " + itemModel(name));
        }
    }
    private static String itemModel(String stage) { return stage.contains("-01-") ? "ysm_01_jk" : "ysm_02_jk"; }
    private void itemDrawTick(MinecraftClient client, String name) {
        JsonObject authority = itemAuthority(), observer = itemObserverProof(name);
        JsonObject equipment = null, serverBinding = null;
        if (authority != null && authority.has("equipmentActors")) for (var row : authority.getAsJsonArray("equipmentActors")) {
            JsonObject actor = row.getAsJsonObject();
            if (OTHER.toString().equals(actor.get("uuid").getAsString())) { equipment = actor; serverBinding = actor.getAsJsonObject("aViewerBinding"); break; }
        }
        String expected = itemModel(name);
        var binding = otherBindings(client).stream().findFirst().orElse(null);
        boolean serverEquipment = equipment != null && !equipment.get("serverMpaSessionPresent").getAsBoolean()
                && "minecraft:iron_sword".equals(equipment.get("mainhand").getAsString())
                && "minecraft:shield".equals(equipment.get("offhand").getAsString())
                && equipment.getAsJsonArray("modelIds").asList().stream().anyMatch(value -> expected.equals(value.getAsString()));
        boolean current = binding != null && serverBinding != null
                && expected.equals(serverBinding.get("modelId").getAsString())
                && binding.instance().equals(serverBinding.get("instance").getAsString())
                && binding.assetHash().equals(serverBinding.get("hash").getAsString());
        boolean nativeAction = observer != null && observer.get("actionObserved").getAsBoolean();
        Map<String,Object> diagnostics = ModelRenderer.diagnostics();
        Map<String,Map<String,Object>> hands = new LinkedHashMap<>();
        if (current && diagnostics.get("submittedItemDraws") instanceof List<?> draws) for (Object value : draws) {
            if (!(value instanceof Map<?,?> row)) continue;
            if (!OTHER.toString().equals(row.get("owner")) || !binding.instance().equals(row.get("instance"))
                    || !binding.assetHash().equals(row.get("hash")) || !Boolean.TRUE.equals(row.get("currentBindingValid"))
                    || !Boolean.TRUE.equals(row.get("nativePresent")) || !OTHER.toString().equals(row.get("nativeEntityUuid"))
                    || !Boolean.TRUE.equals(row.get("nonempty")) || itemNumber(row,"submission") <= itemSubmissionBaseline
                    || itemNumber(row,"extractedFrame") <= itemFrameBaseline
                    || !itemMatrix(row.get("locatorTransform")) || !itemMatrix(row.get("finalTransform"))) continue;
            String hand = String.valueOf(row.get("hand"));
            boolean mainRight = observer != null && "RIGHT".equals(observer.get("mainArm").getAsString());
            String mainBone = mainRight ? "RightHandLocator" : "LeftHandLocator", offBone = mainRight ? "LeftHandLocator" : "RightHandLocator";
            if (!(hand.equals("mainhand") && "minecraft:iron_sword".equals(row.get("item")) && mainBone.equals(row.get("bone"))
                    || hand.equals("offhand") && "minecraft:shield".equals(row.get("item")) && offBone.equals(row.get("bone")))) continue;
            if (observer == null || itemNumber(row,"nativeEntityId") != observer.get("entityId").getAsLong()) continue;
            Map<String,Object> copied = new LinkedHashMap<>(); row.forEach((key,entry) -> copied.put(String.valueOf(key),entry)); hands.put(hand,copied);
        }
        boolean bothHands = hands.keySet().containsAll(Set.of("mainhand","offhand"));
        boolean actionDraw = name.endsWith("-hold");
        Map<String,float[]> baseline = heldItemBaselines.get(expected);
        if (name.endsWith("-swing") && hands.containsKey("mainhand") && baseline != null) {
            Map<String,Object> draw = hands.get("mainhand");
            actionDraw = Boolean.TRUE.equals(draw.get("swinging")) && itemNumber(draw,"swingTicks") > 0
                    && itemMatrixChanged(draw.get("locatorTransform"),baseline.get("mainhand"))
                    && binding.layers().stream().anyMatch(layer -> layer.animation().startsWith("swing") || layer.animation().equals("attack"));
        }
        if (name.endsWith("-use") && hands.containsKey("offhand") && baseline != null) {
            Map<String,Object> draw = hands.get("offhand");
            actionDraw = Boolean.TRUE.equals(draw.get("usingItem")) && "OFF_HAND".equals(draw.get("activeHand"))
                    && itemNumber(draw,"useTicks") >= 2 && itemMatrixChanged(draw.get("locatorTransform"),baseline.get("offhand"))
                    && binding.layers().stream().anyMatch(layer -> layer.animation().startsWith("use_offhand"));
        }
        boolean verifyShieldModel = name.endsWith("-hold") || name.endsWith("-use");
        boolean nativeShieldModel = !verifyShieldModel || itemResolvedShieldModel(hands.get("offhand"), itemShieldExpectedSource);
        boolean valid = serverEquipment && current && nativeAction && bothHands && actionDraw && nativeShieldModel;
        itemDrawStableTicks = valid ? itemDrawStableTicks + 1 : 0;
        Map<String,Object> proof = new LinkedHashMap<>();
        proof.put("stage",name); proof.put("stageTick",stageTicks); proof.put("expectedModel",expected);
        proof.put("serverAuthority",authority); proof.put("unmoddedObserver",observer); proof.put("serverEquipment",serverEquipment);
        proof.put("currentBinding",current); proof.put("bothHandsPostsubmit",bothHands); proof.put("nativeActionAndChangedLocator",actionDraw);
        if (verifyShieldModel) {
            proof.put("nativeShieldModelSource", itemShieldExpectedSource);
            proof.put("nativeShieldModelResolved", nativeShieldModel);
        }
        proof.put("itemDraws",hands); proof.put("stableTicks",itemDrawStableTicks); itemLatestProof = proof;
        if (stageTicks % 5 == 0) fixtureFrames.add(proof);
        if (!itemStageSucceeded && stageTicks >= 20 && itemDrawStableTicks >= (name.endsWith("-swing") ? 1 : 3)) {
            itemStageSucceeded = true;
            check(name + "UnmoddedOwnerEquipment",serverEquipment,"Fresh actual Bukkit inventory, model id, and no MPA session: " + equipment);
            check(name + "CurrentBindingPostsubmitBothHands",current && bothHands,"Two actual ItemRenderState.render queue submissions from current raw " + expected + " hash/instance and native B UUID/ID: " + hands);
            check(name + "NativeActionMovesItemLocator",nativeAction && actionDraw,"Independent B native swing/use plus matching A postsubmit state and changed sampled HandLocator; B=" + observer);
            if (verifyShieldModel) check(name + (name.endsWith("-use") ? "NativeBlockingShieldModelResolved" : "NativeHeldShieldModelResolved"),
                    nativeShieldModel, "Actual native special-renderer layer transformation equals the enabled Minecraft model resource; source="
                            + itemShieldExpectedSource + "; postsubmit shield=" + hands.get("offhand"));
            if (name.endsWith("-hold")) {
                Map<String,float[]> matrices = new LinkedHashMap<>(); hands.forEach((hand,draw) -> matrices.put(hand,((float[])draw.get("locatorTransform")).clone()));
                heldItemBaselines.put(expected,matrices);
            }
            observations.add(proof); capture(client,name);
        }
        if (itemStageSucceeded && stageTicks >= 40) next(client);
        else if (stageTicks >= 120) {
            if (verifyShieldModel) check(name + (name.endsWith("-use") ? "NativeBlockingShieldModelResolved" : "NativeHeldShieldModelResolved"),
                    nativeShieldModel, "Fixed deadline; actual native layer must equal the expected model resource; source="
                            + itemShieldExpectedSource + "; postsubmit shield=" + hands.get("offhand"));
            check(name + "ActualItemDraws",false,"Fixed 120tick deadline; real queue/authority/native evidence=" + itemLatestProof);
            observations.add(proof); capture(client,name); next(client);
        }
    }
    private JsonObject itemAuthority() {
        try {
            Path source = Path.of(String.valueOf(proof.get("serverSource"))).toAbsolutePath().normalize();
            Path file = source.getParent().resolve("MPATestHelper/actor-authority.json");
            JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            long sampled = value.get("sampledAtMillis").getAsLong(), age = System.currentTimeMillis()-sampled;
            if (sampled < itemStageStartedAtMillis || age < -1000 || age > 1500
                    || !value.get("authorityReadSucceeded").getAsBoolean() || !value.get("ownerOnline").getAsBoolean()
                    || !UUID.nameUUIDFromBytes(("OfflinePlayer:" + PLAYER).getBytes(StandardCharsets.UTF_8)).toString().equals(value.get("ownerUuid").getAsString())
                    || !value.get("source").getAsString().startsWith("Ignored MPATestHelper authoritative Bukkit owner")
                    || !value.get("handsFixtureActive").getAsBoolean()
                    || (STAGES.get(this.stage).endsWith("-hold") && value.get("handsEquippedAtMillis").getAsLong() < itemStageStartedAtMillis)) return null;
            return value;
        } catch (Exception absent) { return null; }
    }
    private JsonObject itemObserverProof(String stage) {
        try {
            JsonObject value = JsonParser.parseString(Files.readString(output.resolve("observer-item-actions.json"))).getAsJsonObject();
            long sample = value.get("sampledAtMillis").getAsLong(), age = System.currentTimeMillis()-sample;
            if (sample < itemStageStartedAtMillis || age < -1000 || age > 1500 || !stage.equals(value.get("stage").getAsString())
                    || !OTHER.toString().equals(value.get("owner").getAsString()) || value.get("installedMpa").getAsBoolean()
                    || !value.get("helperSha256").getAsString().matches("[a-f0-9]{64}")
                    || !"minecraft:iron_sword".equals(value.get("mainhand").getAsString())
                    || !"minecraft:shield".equals(value.get("offhand").getAsString())
                    || !Set.of("LEFT", "RIGHT").contains(value.get("mainArm").getAsString())
                    || !value.get("source").getAsString().startsWith("Ignored observer Fabric native inventory/action")) return null;
            return value;
        } catch (Exception absent) { return null; }
    }
    private static long itemNumber(Map<?,?> row,String field) { return row.get(field) instanceof Number n ? n.longValue() : -1; }
    private static boolean itemMatrix(Object value) {
        if (!(value instanceof float[] matrix) || matrix.length != 16) return false;
        for (float element : matrix) if (!Float.isFinite(element) || Math.abs(element)>1_000_000) return false;
        return Math.abs(new org.joml.Matrix4f().set(matrix).determinant3x3())>1e-12;
    }
    private static boolean itemMatrixChanged(Object value,float[] before) {
        if (!(value instanceof float[] now) || before == null || before.length != now.length) return false;
        for(int i=0;i<now.length;i++) if(Math.abs(now[i]-before[i])>1e-5) return true;
        return false;
    }
    private static Map<String,Object> readShieldModelSource(MinecraftClient client, boolean blocking) {
        Identifier source = Identifier.of("minecraft", "models/item/" + (blocking ? "shield_blocking" : "shield") + ".json");
        try (var reader = client.getResourceManager().getResource(source).orElseThrow().getReader()) {
            JsonObject display = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("display");
            Map<String,Object> contexts = new LinkedHashMap<>();
            for (String context : List.of("thirdperson_lefthand", "thirdperson_righthand")) {
                JsonObject transform = display.getAsJsonObject(context);
                contexts.put(context, Map.of("rotationDegrees", itemSourceVector(transform, "rotation", 1),
                        "translationBlocks", itemSourceVector(transform, "translation", 16),
                        "scale", itemSourceVector(transform, "scale", 1)));
            }
            return Map.of("resource", source.toString(), "readSucceeded", true, "contexts", contexts);
        } catch (Exception failure) {
            return Map.of("resource", source.toString(), "readSucceeded", false, "error", failure.toString());
        }
    }
    private static List<Double> itemSourceVector(JsonObject transform, String key, double divisor) {
        var source = transform.getAsJsonArray(key);
        if (source == null || source.size() != 3) throw new IllegalStateException("Missing native model transform " + key);
        return source.asList().stream().map(value -> value.getAsDouble() / divisor).toList();
    }
    private static boolean itemResolvedShieldModel(Map<String,Object> draw, Map<String,Object> source) {
        if (draw == null || !"minecraft:shield".equals(draw.get("item")) || !Boolean.TRUE.equals(source.get("readSucceeded"))
                || !(source.get("contexts") instanceof Map<?,?> contexts)
                || !(draw.get("resolvedLayers") instanceof List<?> layers)) return false;
        String context = "LeftHandLocator".equals(draw.get("bone")) ? "thirdperson_lefthand" : "thirdperson_righthand";
        if (!(contexts.get(context) instanceof Map<?,?> expected)) return false;
        boolean shieldFound = false;
        for (Object value : layers) {
            if (!(value instanceof Map<?,?> layer)
                    || !Boolean.TRUE.equals(layer.get("shieldSpecialRenderer"))) continue;
            if (!context.equals(layer.get("displayContext"))
                    || !itemVectorEquals(layer.get("rotationDegrees"), expected.get("rotationDegrees"))
                    || !itemVectorEquals(layer.get("translationBlocks"), expected.get("translationBlocks"))
                    || !itemVectorEquals(layer.get("scale"), expected.get("scale"))) return false;
            shieldFound = true;
        }
        return shieldFound;
    }
    private static boolean itemVectorEquals(Object actual, Object expected) {
        if (!(actual instanceof List<?> a) || !(expected instanceof List<?> e) || a.size() != 3 || e.size() != 3) return false;
        for (int axis = 0; axis < 3; axis++) {
            if (!(a.get(axis) instanceof Number av) || !(e.get(axis) instanceof Number ev)
                    || !Double.isFinite(av.doubleValue()) || !Double.isFinite(ev.doubleValue())
                    || Math.abs(av.doubleValue() - ev.doubleValue()) > 1e-5) return false;
        }
        return true;
    }

    private JsonObject readPackAuthority(MinecraftClient client) {
        try {
            String serverSource = (String) proof.get("serverSource");
            if (serverSource == null || serverSource.isBlank()
                    || !String.valueOf(proof.get("serverArtifactSha256")).matches("[0-9a-fA-F]{64}"))
                throw new IllegalStateException("Missing existing frozen server artifact proof");
            Path plugins = Path.of(serverSource).toAbsolutePath().normalize().getParent();
            Path log = plugins.getParent().resolve("logs/latest.log");
            try (FileChannel channel = FileChannel.open(log, StandardOpenOption.READ)) {
                long size = channel.size();
                int count = (int) Math.min(size, 512 * 1024L);
                ByteBuffer buffer = ByteBuffer.allocate(count);
                channel.position(size - count);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                String[] lines = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    int marker = lines[i].indexOf("LOCAL STATUS ");
                    if (marker < 0) continue;
                    JsonObject status;
                    try { status = JsonParser.parseString(lines[i].substring(marker + "LOCAL STATUS ".length()).strip()).getAsJsonObject(); }
                    catch (RuntimeException incompleteLine) { continue; }
                    if (!client.player.getUuidAsString().equals(status.get("owner").getAsString())) continue;
                    JsonObject snapshot = status.getAsJsonObject("clientSnapshot"), actor = status.getAsJsonObject("testActorAuthority");
                    if (snapshot == null || actor == null || (!packExpectedInstance.isEmpty()
                            && !packExpectedInstance.equals(snapshot.get("instance").getAsString()))) continue;
                    long sample = actor.get("sampledAtMillis").getAsLong();
                    if (sample < packReloadCompletedAtMillis || System.currentTimeMillis() - sample > 3_000) continue;
                    if (!actor.get("authorityReadSucceeded").getAsBoolean() || !actor.get("serverActualOwned").getAsBoolean()
                            || !client.player.getUuidAsString().equals(actor.get("ownerUuid").getAsString())
                            || !actor.get("source").getAsString().startsWith("Ignored MPATestHelper authoritative Bukkit owner")) continue;
                    packAuthorityError = "Fresh MPATestHelper LOCAL STATUS from " + log + "; sample=" + sample;
                    return status;
                }
            }
            packAuthorityError = "Waiting for fresh same-owner/same-instance LOCAL STATUS after the real reload callback";
        } catch (Exception failure) { packAuthorityError = failure.toString(); }
        return null;
    }

    private boolean packAuthorityMatches(MinecraftClient client, boolean localReady) {
        try {
            if (packAuthority == null) return false;
            JsonObject actor = packAuthority.getAsJsonObject("testActorAuthority");
            long sample = actor.get("sampledAtMillis").getAsLong();
            if (sample < packReloadCompletedAtMillis || System.currentTimeMillis() - sample > 3_000) return false;
            for (var entry : packAuthority.getAsJsonArray("viewers")) {
                JsonObject viewer = entry.getAsJsonObject();
                if (client.player.getUuidAsString().equals(viewer.get("viewer").getAsString()))
                    return viewer.get("allowed").getAsBoolean()
                            && viewer.get("localReadyForOwner").getAsBoolean() == localReady
                            && viewer.get("meVisibleForOwner").getAsBoolean() != localReady;
            }
        } catch (Exception failure) { packAuthorityError = failure.toString(); }
        return false;
    }

    private void remoteAccessoryTick(MinecraftClient client) {
        var binding = otherBindings(client).stream().findFirst().orElse(null);
        if (!remoteExtraRequested) {
            boolean ready = binding != null && !binding.instance().equals(remotePreviousInstance)
                    && visibleModel(ModelRenderer.diagnostics(), binding);
            remoteExtraReadyTicks = ready ? remoteExtraReadyTicks + 1 : 0;
            if (remoteExtraReadyTicks >= 3) {
                remoteExtraInstance = binding.instance();
                var state = runtime.accessoryState(OTHER);
                check("remoteAccessoryStartsClear", state.getOrDefault("a", -1d) == 0 && state.getOrDefault("b", -1d) == 0,
                        "New reference model is ready before extra0; authority=" + state);
                remoteExtraRequested = true;
                command(client, "mpatest MPAObserver extra0");
            }
        }
        if (remoteExtraRequested && binding != null && binding.instance().equals(remoteExtraInstance)) {
            var active = binding.layers().stream().filter(layer -> layer.animation().equals("extra0")).findFirst().orElse(null);
            if (!remoteExtraSeen && active != null) {
                remoteExtraSeen = true; remoteExtraStartedAt = active.startedAtTick();
                remoteExtraLengthTicks = binding.model().animationLengthTicks("extra0") / active.speed();
            }
            if (remoteExtraSeen) {
                double age = binding.serverTick() - remoteExtraStartedAt;
                var authority = runtime.accessoryState(OTHER);
                var variables = ModelRenderer.expressionVariables(OTHER);
                boolean changed = authority.getOrDefault("a", 0d) == 1 && authority.getOrDefault("b", 0d) == 1;
                if (changed && !Double.isFinite(remoteExtraFirstChangedAge)) remoteExtraFirstChangedAge = age;
                if (age < 20) {
                    remoteExtraEarlySamples++;
                    remoteExtraEarlyValid &= authority.getOrDefault("a", -1d) == 0 && authority.getOrDefault("b", -1d) == 0;
                }
                // This is a fixed timeline deadline, never a wait until the expected state happens.
                if (!remoteExtraEventChecked && age >= 35) {
                    remoteExtraEventChecked = true;
                    check("remoteAccessoryEventDeadline", changed
                                    && variables.getOrDefault("variable.roaming.a", 0d) == 1
                                    && variables.getOrDefault("variable.roaming.b", 0d) == 1,
                            "Source events at 24.166/25 ticks plus bounded server/renderer allowance; age=" + age
                                    + "; authority=" + authority + "; variables=" + variables);
                }
                if (stageTicks % 5 == 0) fixtureFrames.add(Map.of(
                        "stage", "other-extra0", "stageTick", stageTicks, "instance", binding.instance(),
                        "sampleTick", binding.serverTick(), "startedAtTick", remoteExtraStartedAt, "ageTicks", age,
                        "layers", binding.layers(), "authority", authority, "variables", variables));
                if (remoteExtraCompletedAt < 0 && age >= remoteExtraLengthTicks + 10 && active == null) {
                    remoteExtraCompletedAt = stageTicks;
                    capture(client, "other-extra0");
                }
            }
        }
        if (captured && remoteExtraCompletedAt >= 0 && stageTicks >= remoteExtraCompletedAt + 12) { next(client); return; }
        if (stageTicks > 240) {
            check("remoteExtraTimelineCompleted", false, "extra0 did not start/finish in the ready reference instance; started=" + remoteExtraSeen);
            if (!captured) capture(client, "other-extra0");
            next(client);
        }
    }

    private void measureFollow(MinecraftClient client,PlayerEntity entity) {
        if(entity==null)return;
        var binding=runtime.renderBindings().stream().filter(value->value.owner().equals(entity.getUuid())).findFirst().orElse(null);
        if(binding==null)return;
        var nativePos=entity.getLerpedPos(client.getRenderTickCounter().getTickProgress(false));
        double error=Math.sqrt(Math.pow(binding.x()-nativePos.x,2)+Math.pow(binding.y()-nativePos.y,2)+Math.pow(binding.z()-nativePos.z,2));
        maxFollowError=Math.max(maxFollowError,error);followSamples++;
        if(binding.layers().stream().anyMatch(layer->layer.layer().equals("posture") && (layer.animation().equals("walk") || layer.animation().equals("run")))) followWalking++;
        var delayed=runtime.serverTransform(entity.getUuid());
        if(delayed!=null)maxServerGap=Math.max(maxServerGap,Math.sqrt(Math.pow(binding.x()-delayed.x(),2)+Math.pow(binding.y()-delayed.y(),2)+Math.pow(binding.z()-delayed.z(),2)));
    }
    private List<ClientRuntime.RenderBinding> own(MinecraftClient client) {
        if (client.player == null) return List.of();
        Collection<ClientRuntime.RenderBinding> bindings = runtime.renderBindings();
        return bindings.stream().filter(binding -> binding.owner().equals(client.player.getUuid())).toList();
    }

    private PlayerEntity otherPlayer(MinecraftClient client) {
        return client.world == null ? null : client.world.getPlayers().stream()
                .filter(player -> player.getName().getString().equals("MPAObserver")).findFirst().orElse(null);
    }

    private List<ClientRuntime.RenderBinding> otherBindings(MinecraftClient client) {
        return runtime.renderBindings().stream().filter(binding -> binding.owner().equals(OTHER)).toList();
    }

    private void aim(MinecraftClient client, ClientRuntime.RenderBinding binding) {
        boolean low = binding.layers().stream().anyMatch(layer -> layer.animation().contains("climb") || layer.animation().contains("crawl") || layer.animation().equals("sleep"));
        aim(client, binding.x(), binding.y() + (low ? .4 : 1.1), binding.z());
    }

    private void aim(MinecraftClient client, double x, double targetY, double z) {
        double dx = x - client.player.getX(), dz = z - client.player.getZ();
        double dy = targetY - client.player.getEyeY();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        client.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    private void phase(MinecraftClient client, String name) {
        try {
            Files.createDirectories(output);
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("uuid", client.player.getUuidAsString()); actor.put("x", client.player.getX()); actor.put("y", client.player.getY()); actor.put("z", client.player.getZ());
            actor.put("pose", client.player.getPose().name()); actor.put("onGround", client.player.isOnGround());
            actor.put("sleeping", client.player.isSleeping()); actor.put("vehicle", client.player.hasVehicle());
            actor.put("layers", own(client).stream().flatMap(binding -> binding.layers().stream()).map(layer -> layer.animation()).toList());
            if (name.startsWith("push-")) {
                actor.put("pushDelivery", runtime.serverPushDiagnostics());
                actor.put("pushAuthority", packAuthority);
            }
            own(client).stream().findFirst().ifPresent(binding -> actor.put("visualAnchor", Map.of("x", binding.x(), "y", binding.y(), "z", binding.z(), "bodyYaw", binding.bodyYaw())));
            if (name.startsWith("local-appearance-")) actor.put("privateAppearance", Map.of(
                    "enabled", runtime.localAppearance().enabled(), "modelId", runtime.localAppearance().modelId(),
                    "instance", own(client).stream().map(ClientRuntime.RenderBinding::instance).findFirst().orElse(""),
                    "requestPacketsSent", runtime.requestPacketsSent()));
            Files.writeString(output.resolve("phase.json"), new GsonBuilder().create().toJson(Map.of(
                    "stage", name, "stageIndex", stage, "stageTicks", stageTicks,
                    "startedAtMillis", System.currentTimeMillis() - stageTicks * 50L, "actor", actor, "proof", proof)), StandardCharsets.UTF_8);
        } catch (Exception failure) { LOGGER.warn("Cannot write E2E phase", failure); }
    }

    private void bedGeometry(MinecraftClient client, List<?> models, UUID owner, double bedX, double bedZ, String prefix) {
        ModelRenderer.FrameModel model = models.stream().filter(ModelRenderer.FrameModel.class::isInstance)
                .map(ModelRenderer.FrameModel.class::cast).filter(frame -> frame.owner().equals(owner.toString())).findFirst().orElse(null);
        BlockPos bed = BlockPos.ofFloored(bedX, -60, bedZ), second = bed;
        var state=client.world.getBlockState(bed);
        if(state.getBlock() instanceof BedBlock) second=bed.offset(BedBlock.getOppositePartDirection(state));
        double minX=Math.min(bed.getX(),second.getX()),maxX=Math.max(bed.getX(),second.getX())+1;
        double minZ=Math.min(bed.getZ(),second.getZ()),maxZ=Math.max(bed.getZ(),second.getZ())+1;
        double bedTop = bed.getY() + 9.0/16;
        check(prefix + "SurfaceContact", model != null && model.minY() >= bedTop - .15 && model.minY() <= bedTop + .15,
                "bedTop=" + bedTop + "; actual mesh=" + model);
        check(prefix + "Position", model != null && model.minX() < maxX && model.maxX() > minX
                        && model.minZ() < maxZ && model.maxZ() > minZ,
                "Mesh overlaps actual two-block fixture bed; actual mesh=" + model);
    }

    private ArmorStandEntity sideCamera(MinecraftClient client) {
        ClientRuntime.RenderBinding binding = own(client).stream().findFirst().orElse(null);
        double x = binding == null ? client.player.getX() : binding.x();
        double y = binding == null ? client.player.getY() : binding.y();
        double z = binding == null ? client.player.getZ() : binding.z();
        ArmorStandEntity camera = new ArmorStandEntity(client.world, x - .5, Math.max(-60, y) - .9, z + .5);
        camera.refreshPositionAndAngles(camera.getX(), camera.getY(), camera.getZ(), 90, 12);
        camera.headYaw = camera.lastHeadYaw = camera.bodyYaw = camera.lastBodyYaw = 90;
        camera.resetPosition();
        return camera;
    }

    private void command(MinecraftClient client, String command) {
        if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand(command);
    }

    private void screenshot(MinecraftClient client, String name) {
        try {
            Files.createDirectories(output);
            String filename = name + ".png";
            screenshots.add("screenshots/" + filename);
            client.inGameHud.getChatHud().clear(false);
            ScreenshotRecorder.saveScreenshot(output.toFile(), filename, client.getFramebuffer(), 1,
                    message -> {
                        long completedAt = System.currentTimeMillis();
                        client.execute(() -> screenshotCallbacks.add(Map.of("file", "screenshots/" + filename,
                                "completedAtMillis", completedAt, "fileWritten", Files.isRegularFile(output.resolve("screenshots").resolve(filename)),
                                "message", message.getString())));
                        LOGGER.info("E2E screenshot {}: {}", filename, message.getString());
                    });
        } catch (Exception failure) { check("screenshot-" + name, false, failure.toString()); }
    }

    private void check(String name, boolean passed, String detail) {
        checks.add(Map.of("name", name, "passed", passed, "detail", detail));
        LOGGER.info("E2E check {}={} {}", name, passed, detail);
    }

    private void finish(MinecraftClient client) {
        if (finished) return;
        if (handsCleanupPending && client.player != null && client.getNetworkHandler() != null) {
            command(client, "mpatest MPAObserver hands-restore"); handsCleanupPending = false;
        }
        if (pushFaultRequested && client.player != null && client.getNetworkHandler() != null) {
            // This is an emergency cleanup request, never evidence that the repair gate passed.
            command(client, "mpatest " + PLAYER + " push-repair");
        }
        if (packSelectionChanged) {
            try {
                client.getResourcePackManager().setEnabledProfiles(originalPackProfiles);
                check("packSelectionRestoredOnFailure", List.copyOf(client.getResourcePackManager().getEnabledIds()).equals(originalPackProfiles),
                        "Emergency restore of the saved selection before isolated client shutdown; profiles=" + originalPackProfiles);
                // Normal pack-restored waits for the real callback and rendering. On a
                // failing/timeout exit, restore selection as well without claiming that gate passed.
                client.reloadResources();
            } catch (Exception failure) { check("packSelectionRestoredOnFailure", false, failure.toString()); }
            packSelectionChanged = false;
        }
        check("serverPushNeverAssetRequested", pushCount(runtime.serverPushDiagnostics(), "assetRequestPacketsSent") == 0,
                "Final real asset_request send count=" + pushCount(runtime.serverPushDiagnostics(), "assetRequestPacketsSent"));
        finished = true;
        try {
            Files.createDirectories(output);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", !checks.isEmpty() && checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
            result.put("minecraft", "1.21.11"); result.put("clientVersion", "0.4.2");
            result.put("testedClientSha256", proof.get("clientArtifactSha256")); result.put("testedServerSha256", proof.get("serverArtifactSha256"));
            result.put("artifactProof", proof);result.put("startedAtMillis", startedMillis);result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
            result.put("screenshotCallbacks", screenshotCallbacks);
            result.put("fixtureFrames", fixtureFrames);
            result.put("serverPushDelivery", serverPushDelivery());
            Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            LOGGER.info("E2E results written to {}", output.resolve("results.json"));
        } catch (Exception failure) { LOGGER.error("Cannot save E2E results", failure); }
        client.scheduleStop();
    }

    private Map<String, Object> serverPushDelivery() {
        List<String> required = List.of("serverPushNegotiated", "serverPushPlainMePack", "serverPushColdCacheDownloaded", "serverPushDefaultJarAssetSource",
                "serverPushReconnectPrerequisites", "serverPushActualReconnect", "serverPushReconnectCacheHitNoDownload",
                "serverPushInvalidOwnAssetAuthoritative", "serverPushInvalidAssetFallback", "serverPushInvalidAssetNoStaleTransfer",
                "serverPushFaultFixtureRestored", "serverPushRepairRecovered", "serverPushRepairCacheNoDownload",
                "serverPushPackReloadPrerequisites", "serverPushPackDisabledQuiescent", "serverPushPackRestoredQuiescent", "packDisableReloadCompleted", "serverPushPackDisabledNoIndex",
                "serverPushPackDisabledModelRetained", "serverPushPackDisabledFreshFrames", "serverPushPackDisabledOwnerLeaseSameInstance",
                "serverPushPackDisabledNoDownloadFreshAck", "packRestoreReloadCompleted", "packRestoredExactProfiles",
                "packRestoredHashRevalidated", "packRestoredOwnerLeaseSameInstance", "packRestoredFreshFrames",
                "serverPushPackRestoredNoDownloadFreshAck", "serverPushNeverAssetRequested",
                "other-range-outPushOwnerReleased", "other-undisguisePushOwnerReleased", "other-second-undisguisePushOwnerReleased",
                "own-second-undisguisePushOwnerReleased", "undisguisePushOwnerReleased", "previewPushOwnerReleased",
                "npcPushNewInstance", "other-second-modelPushNewInstance", "other-first-modelPushNewInstance",
                "own-second-modelPushNewInstance", "own-first-modelPushNewInstance");
        List<Map<String, Object>> relevant = checks.stream().filter(row -> String.valueOf(row.get("name")).contains("Push")
                || String.valueOf(row.get("name")).contains("NoAssetRequest") || required.contains(row.get("name"))).toList();
        boolean requiredPassed = required.stream().allMatch(name -> checks.stream().anyMatch(row -> name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("passed"))));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", requiredPassed && relevant.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))));
        report.put("requiredChecks", required); report.put("checks", relevant);
        report.put("finalDiagnostics", runtime.serverPushDiagnostics());
        report.put("firstDownload", stageObservation("push-first-download"));
        report.put("reconnect", stageObservation("push-cache-reconnect"));
        report.put("fault", stageObservation("push-asset-failed")); report.put("recovery", stageObservation("push-asset-recovered"));
        report.put("packReload", observations.stream().filter(row -> Set.of("pack-disabled", "pack-restored").contains(row.get("stage"))).toList());
        report.put("authority", "Actual protocol counters, current raw server bindings/grants, real world meshes, and fresh server helper ModelAssets/viewer leases");
        return report;
    }

    private Map<String, Object> stageObservation(String stageName) {
        return observations.stream().filter(row -> stageName.equals(row.get("stage"))).findFirst().orElse(Map.of());
    }

    private static Map<String, Object> artifactProof() {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("serverArtifactSha256", System.getProperty("meplayeractions.e2e.serverSha256", ""));
        proof.put("serverSource", System.getProperty("meplayeractions.e2e.serverJar", ""));
        proof.put("serverHashSource", "Launcher read frozen test server plugin JAR before this run");
        try {
            Path source = FabricLoader.getInstance().getModContainer("meplayeractions").orElseThrow().getOrigin().getPaths().getFirst();
            boolean fromJar = Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar") && Files.size(source) < 16 * 1024 * 1024;
            proof.put("clientFromJar", fromJar);proof.put("clientSource", source.toAbsolutePath().toString());
            proof.put("clientArtifactSha256", fromJar ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))) : "");
            proof.put("clientHashSource", "FabricLoader actual loaded mod origin; production remapped JAR");
        } catch (Exception failure) { proof.put("clientFromJar", false);proof.put("clientArtifactSha256", "");proof.put("error", failure.toString()); }
        return Map.copyOf(proof);
    }
}
