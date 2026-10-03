package com.simmc.meplayeractions.client.render;

import com.google.gson.GsonBuilder;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
            : List.of("idle", "local-appearance-server-base", "local-appearance-own", "local-appearance-action", "local-appearance-off",
            "native-follow", "server-trailing", "wave", "crawl", "crawl-side", "bed", "ride", "boat", "extra", "hunger", "jump",
            "firstperson", "firstperson-extra0", "extra0-restored", "gsit-sit", "gsit-crawl", "gsit-lay", "gsit-firstperson", "gsit-thirdperson", "gsit-undisguise", "gsit-redisguise", "gsit-reset", "resource-reload", "local-off", "local-on", "npc", "npc-crawl", "npc-crawl-side",
            "menu", "self-hidden", "self-hidden-extra0", "self-restored", "server-reload", "other-idle", "other-native-follow", "other-crawl", "other-bed",
            "other-ride", "other-jump", "other-extra0", "other-range-out", "other-range-in", "other-undisguise", "other-native-move", "other-redisguise", "other-undisguise-again",
            "other-second-model", "other-second-undisguise", "other-first-model", "own-second-model", "own-second-undisguise", "own-first-model", "undisguise", "preview", "preview-end");
    private final ClientRuntime runtime;
    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/e2e")).toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
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
    private static final LocalAppearanceSettings LOCAL_PROFILE = new LocalAppearanceSettings(true, "ysm_02_jk", .65f, .25, .35, -.2);

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
            if (name.equals("resource-reload")) {
                resourceReloadTick(client);
                return;
            }
            if (name.equals("other-extra0")) {
                remoteAccessoryTick(client);
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
        switch (name) {
            case "idle" -> command(client, "mpatest " + PLAYER + " reset");
            case "local-appearance-server-base" -> { runtime.disableLocalAppearance(); command(client, "mpatest " + PLAYER + " reset"); }
            case "local-appearance-own" -> {
                localRequestBaseline = runtime.requestPacketsSent(); localAppearanceReadyAt = -1;
                runtime.selectLocalModel("ysm_02_jk"); runtime.updateLocalAppearance(LOCAL_PROFILE);
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
            case "firstperson-extra0" -> command(client, "meplayeractions play extra0");
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
            case "local-off" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "local-on" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "boat" -> command(client,"mpatest "+PLAYER+" boat");
            case "extra" -> command(client,"mpatest "+PLAYER+" extra");
            case "hunger" -> command(client,"mpatest "+PLAYER+" hunger");
            case "npc" -> command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
            case "menu" -> {
                command(client, "mpatest " + PLAYER + " reset");
                runtime.preview("ysm_02_jk");
                client.setScreen(new ActionsScreen(runtime));
            }
            case "self-hidden" -> runtime.options.showSelf = false;
            case "self-hidden-extra0" -> command(client, "meplayeractions play extra0");
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
            case "own-second-model" -> command(client, "mpatest " + PLAYER + " disguise ysm_02_jk");
            case "own-second-undisguise" -> command(client, "meplayeractions undisguise");
            case "own-first-model" -> command(client, "mpatest " + PLAYER + " disguise ysm_01_jk");
            case "undisguise" -> command(client, "meplayeractions undisguise");
            case "preview" -> runtime.preview("ysm_02_jk");
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
        if (!captured && ready && localAppearanceReadyAt >= 0 && stageTicks >= localAppearanceReadyAt + 25) capture(client, name);
        if (captured && stageTicks >= localAppearanceReadyAt + 55) { next(client); return; }
        if (stageTicks > 240) {
            check(name + "Ready", false, "Local appearance did not become visible; status=" + runtime.localAppearanceStatus());
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
                    message -> LOGGER.info("E2E screenshot {}: {}", filename, message.getString()));
        } catch (Exception failure) { check("screenshot-" + name, false, failure.toString()); }
    }

    private void check(String name, boolean passed, String detail) {
        checks.add(Map.of("name", name, "passed", passed, "detail", detail));
        LOGGER.info("E2E check {}={} {}", name, passed, detail);
    }

    private void finish(MinecraftClient client) {
        if (finished) return;
        finished = true;
        try {
            Files.createDirectories(output);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", !checks.isEmpty() && checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
            result.put("minecraft", "1.21.11"); result.put("clientVersion", "0.4.0");
            result.put("testedClientSha256", proof.get("clientArtifactSha256")); result.put("testedServerSha256", proof.get("serverArtifactSha256"));
            result.put("artifactProof", proof);result.put("startedAtMillis", startedMillis);result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
            result.put("fixtureFrames", fixtureFrames);
            Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            LOGGER.info("E2E results written to {}", output.resolve("results.json"));
        } catch (Exception failure) { LOGGER.error("Cannot save E2E results", failure); }
        client.scheduleStop();
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
