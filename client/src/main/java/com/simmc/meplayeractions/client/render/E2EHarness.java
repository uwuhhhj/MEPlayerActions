package com.simmc.meplayeractions.client.render;

import com.google.gson.GsonBuilder;
import com.simmc.meplayeractions.client.ClientRuntime;
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
    private static final List<String> STAGES = List.of("idle", "wave", "crawl", "crawl-side", "bed", "ride", "jump",
            "firstperson", "gsit-sit", "gsit-crawl", "gsit-lay", "gsit-firstperson", "gsit-thirdperson", "gsit-undisguise", "gsit-redisguise", "gsit-reset", "resource-reload", "local-off", "local-on", "npc", "npc-crawl", "npc-crawl-side",
            "menu", "self-hidden", "self-restored", "server-reload", "other-idle", "other-crawl", "other-bed",
            "other-ride", "other-jump", "other-range-out", "other-range-in", "undisguise", "preview", "preview-end");
    private final ClientRuntime runtime;
    private final Path output = Path.of(System.getProperty("meplayeractions.e2e.output", "../build/e2e")).toAbsolutePath();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
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
    private CompletableFuture<Void> reload;
    private ArmorStandEntity sideCamera;
    private Entity gsitCamera;
    private Map<String, Object> gsitNaturalCamera = Map.of();

    private E2EHarness(ClientRuntime runtime) { this.runtime = runtime; }

    public static void register(ClientRuntime runtime) {
        E2EHarness harness = new E2EHarness(runtime);
        ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
        LOGGER.info("Isolated E2E harness enabled; outputs {}", harness.output);
    }

    private void tick(MinecraftClient client) {
        if (finished) return;
        ticks++;
        try {
            if (System.nanoTime() - started > 240_000_000_000L) {
                check("completion", false, "240 second timeout; screen=" + (client.currentScreen == null ? "world" : client.currentScreen.getClass().getSimpleName()));
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
                    client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                    client.options.getGamma().setValue(1.0);
                    client.player.getAbilities().flying = false;
                    command(client, "mpatest " + PLAYER + " disguise ysm_01_jk_player");
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
                    boolean jumping = otherBindings(client).stream().flatMap(binding -> binding.layers().stream())
                            .anyMatch(layer -> layer.animation().equals("player_jump") || layer.animation().equals("jump"));
                    if (jumping) {
                        otherJumpSeen = true; otherJumpFrames++;
                        if (other != null ? other.isOnGround() : remote.stream().anyMatch(binding -> binding.y() <= -59.98)) otherJumpAfterGround = true;
                    }
                    if (!captured && jumping && stageTicks >= 5) capture(client, name);
                }
            }
            if (name.equals("jump")) {
                boolean jumping = own(client).stream().flatMap(binding -> binding.layers().stream())
                        .anyMatch(layer -> layer.animation().equals("player_jump") || layer.animation().equals("jump"));
                if (jumping) {
                    jumpSeen = true;
                    jumpFrames++;
                    if (client.player.isOnGround()) jumpSeenAfterGround = true;
                }
                if (!captured && jumping && stageTicks >= 5) capture(client, name);
            }
            if (name.equals("resource-reload") && (reload == null || !reload.isDone() || own(client).isEmpty() || clearWorldTicks < 10)) {
                if (stageTicks > 400) { check("resourceReload", false, "Resource reload did not reacquire a model"); next(client); }
                return;
            }
            if (name.equals("server-reload") && stageTicks == 30) command(client, "mpatest " + PLAYER + " disguise ysm_01_jk_npc");
            if ((name.equals("local-on") || name.equals("npc") || name.equals("server-reload") || name.equals("preview") || name.equals("gsit-redisguise")) && own(client).isEmpty()) {
                if (stageTicks > 400) { check(name, false, "No model returned after lifecycle operation"); next(client); }
                return;
            }
            if ((name.equals("other-idle") || name.equals("other-range-in")) && otherBindings(client).isEmpty()) {
                if (stageTicks > 200) { check(name, false, "Other player binding unavailable"); next(client); }
                return;
            }
            if (stageTicks == 20) { client.inGameHud.getChatHud().clear(false); client.getToastManager().clear(); }
            if (!captured && stageTicks >= (name.equals("resource-reload") ? 45 : 30)) capture(client, name);
            if (stageTicks >= (name.endsWith("jump") ? 80 : 60)) next(client);
        } catch (Exception failure) {
            check("harness", false, failure.toString());
            LOGGER.error("E2E harness failed", failure);
            finish(client);
        }
    }

    private void next(MinecraftClient client) {
        if (stage >= 0 && STAGES.get(stage).equals("jump")) {
            check("jumpAnimation", jumpSeen, "Animated jump tick observations=" + jumpFrames);
            check("jumpFinishesAfterGround", jumpSeenAfterGround, "Observed jump animation after real player landed");
        }
        if (stage >= 0 && STAGES.get(stage).equals("other-jump")) {
            check("otherJumpAnimation", otherJumpSeen, "Remote animated jump observations=" + otherJumpFrames);
            check("otherJumpFinishesAfterGround", otherJumpAfterGround, "Remote jump animation continues at ground height; native entity can be hidden by ME");
        }
        stage++;
        stageTicks = 0;
        captured = false;
        if (stage >= STAGES.size()) { finish(client); return; }
        String name = STAGES.get(stage);
        sideCamera = null;
        client.setScreen(null);
        client.setCameraEntity(client.player);
        client.options.setPerspective(name.startsWith("other-") || name.equals("firstperson")
                ? Perspective.FIRST_PERSON : Perspective.THIRD_PERSON_FRONT);
        LOGGER.info("E2E stage {}", name);
        phase(client, name);
        if (!name.equals("bed")) command(client, "time set day");
        switch (name) {
            case "idle" -> command(client, "mpatest " + PLAYER + " reset");
            case "wave" -> command(client, "mpatest " + PLAYER + " wave");
            case "crawl", "npc-crawl" -> command(client, "mpatest " + PLAYER + " crawl");
            case "crawl-side", "npc-crawl-side" -> {
                client.setCameraEntity(sideCamera(client));
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            case "bed" -> { command(client, "time set night"); command(client, "mpatest " + PLAYER + " bed"); }
            case "ride" -> command(client, "mpatest " + PLAYER + " ride");
            case "jump" -> command(client, "mpatest " + PLAYER + " jump");
            case "firstperson" -> {
                command(client, "mpatest " + PLAYER + " reset");
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
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
            case "gsit-redisguise" -> command(client, "mpatest " + PLAYER + " disguise ysm_01_jk_player");
            case "gsit-reset" -> {
                command(client, "lay"); command(client, "meplayeractions reset"); command(client, "mpatest " + PLAYER + " reset");
            }
            case "resource-reload" -> {
                command(client, "item replace entity " + PLAYER + " armor.chest with minecraft:air");
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                reload = client.reloadResources();
            }
            case "local-off" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "local-on" -> { runtime.toggleEnabled(); command(client, "mpatest " + PLAYER + " status"); }
            case "npc" -> command(client, "mpatest " + PLAYER + " disguise ysm_01_jk_npc");
            case "menu" -> {
                command(client, "mpatest " + PLAYER + " reset");
                runtime.preview("ysm_01_jk_npc");
                client.setScreen(new ActionsScreen(runtime));
            }
            case "self-hidden" -> runtime.options.showSelf = false;
            case "self-restored" -> runtime.options.showSelf = true;
            case "server-reload" -> {
                command(client, "mpatest " + PLAYER + " reset");
                command(client, "mpatest " + PLAYER + " reload");
                // Plugin reload removes its disguise, so create a new instance after reload.
                command(client, "mpatest " + PLAYER + " disguise ysm_01_jk_npc");
            }
            case "other-idle" -> {
                command(client, "mpatest " + PLAYER + " reset");
                command(client, "mpatest MPAObserver reset");
                command(client, "mpatest MPAObserver disguise ysm_01_jk_player");
            }
            case "other-crawl" -> command(client, "mpatest MPAObserver crawl");
            case "other-bed" -> { command(client, "time set night"); command(client, "mpatest MPAObserver bed"); }
            case "other-ride" -> command(client, "mpatest MPAObserver ride");
            case "other-jump" -> command(client, "mpatest MPAObserver jump");
            case "other-range-out" -> { command(client, "mpatest MPAObserver reset"); command(client, "tp MPAObserver 8.5 -60 0.5"); }
            case "other-range-in" -> command(client, "tp MPAObserver 8.0 -60 0.5");
            case "undisguise" -> command(client, "meplayeractions undisguise");
            case "preview" -> runtime.preview("ysm_01_jk_npc");
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
        observation.put("otherNativeEntityPresent", other != null);
        if (other != null) observation.put("otherNative", Map.of("pose", other.getPose().name(), "onGround", other.isOnGround(),
                "sleeping", other.isSleeping(), "vehicle", other.hasVehicle(), "x", other.getX(), "y", other.getY(), "z", other.getZ()));
        command(client, "mpatest " + (name.startsWith("other-") ? "MPAObserver" : PLAYER) + " status");
        switch (name) {
            case "idle" -> { idleHeight = height; check("modelIdle", height > 0 && (long) diagnostic.get("drawnBatches") > 0, "height=" + height); }
            case "crawl" -> {
                check("authoritativeCrawlState", layers.contains("crawl"), "server layer=" + layers + "; client recalculated pose=" + client.player.getPose());
                check("crawlGeometry", height > 0 && height < idleHeight * 0.8, "idle=" + idleHeight + "; crawl=" + height);
            }
            case "bed" -> {
                check("bedState", client.player.isSleeping() && layers.contains("bed_sleep"), layers);
                bedGeometry(client, models, client.player.getUuid(), 2, 2, "ownBed");
            }
            case "gsit-sit" -> check("gsitRealSit", height > 0 && layers.contains("sit"), layers);
            case "gsit-crawl" -> check("gsitRealCrawl", height > 0 && layers.contains("crawl"), layers);
            case "gsit-lay" -> {
                check("gsitLayMapped", height > 0 && layers.contains("bed_sleep"), "GSit floor anchor; " + layers);
                var ownMesh = models.stream().filter(ModelRenderer.FrameModel.class::isInstance).map(ModelRenderer.FrameModel.class::cast)
                        .filter(frame -> frame.owner().equals(client.player.getUuidAsString())).findFirst().orElse(null);
                check("gsitLayFloorContact", ownMesh != null && ownMesh.minY() >= -60.15 && ownMesh.minY() <= -59.85,
                        "Actual GSit floor mesh=" + ownMesh);
            }
            case "gsit-reset" -> check("gsitReturnedToIdle", height > 0 && layers.contains("idle") && !layers.contains("crawl") && !layers.contains("sleep"), layers);
            case "gsit-undisguise" -> check("gsitUndisguiseRestoresNative", bindings.isEmpty() && !runtime.shouldHidePlayer(client.player.getUuid()), runtime.status().toString());
            case "gsit-redisguise" -> check("gsitRedisguiseLocalModel", height > 0 && layers.contains("bed_sleep"), layers);
            case "ride" -> check("rideState", client.player.hasVehicle() && layers.contains("sit"), layers);
            case "firstperson" -> check("firstPersonOwnModelHidden", height == 0 && (int) diagnostic.get("firstPersonSelfSkipped") > 0, diagnostic.toString());
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
            case "crawl-side", "npc-crawl-side" -> check(name + "Visible", height > 0 && layers.contains("crawl"), "camera=" + client.getCameraEntity().getType() + "; height=" + height);
            case "menu" -> {
                check("actionsPanel", client.currentScreen instanceof ActionsScreen && !runtime.actions().isEmpty(), "Configured translated actions=" + runtime.actions().size());
                check("previewRespectsServerBinding", !bindings.isEmpty() && bindings.stream().noneMatch(binding -> binding.instance().equals("preview")), "Owned server binding prevents local preview duplication");
            }
            case "self-hidden" -> check("selfDisplayToggleOff", height == 0 && own(client).isEmpty(), runtime.status().toString());
            case "self-restored" -> check("selfDisplayToggleOn", height > 0 && !bindings.isEmpty(), runtime.status().toString());
            case "server-reload" -> check("serverReloadReacquired", height > 0 && !bindings.isEmpty(), runtime.status().toString());
            case "other-idle" -> { otherIdleHeight = otherHeight; check("remoteLocalModel", otherHeight > 0 && !otherBindings.isEmpty(), "MPAObserver has no MPA client; local height=" + otherHeight); }
            case "other-crawl" -> check("remoteCrawl", otherHeight > 0 && otherHeight < otherIdleHeight * .8 && otherLayers.contains("crawl"), "idle=" + otherIdleHeight + "; crawl=" + otherHeight + "; " + otherLayers);
            case "other-bed" -> {
                check("remoteBed", otherHeight > 0 && otherLayers.contains("bed_sleep"), "server layer=" + otherLayers + "; native entity present=" + (other != null));
                bedGeometry(client, models, OTHER, 5, 2, "otherBed");
            }
            case "other-ride" -> check("remoteMinecart", otherHeight > 0 && otherLayers.contains("sit"), "server layer=" + otherLayers + "; native entity present=" + (other != null));
            case "other-range-out" -> check("distanceAt8Hidden", otherBindings.isEmpty() && otherHeight == 0, "Remote binding count=" + otherBindings.size());
            case "other-range-in" -> check("distanceWithin8Visible", !otherBindings.isEmpty() && otherHeight > 0, "Remote binding count=" + otherBindings.size() + "; height=" + otherHeight);
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
        boolean low = binding.layers().stream().anyMatch(layer -> layer.animation().contains("crawl") || layer.animation().equals("sleep"));
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
            result.put("minecraft", "1.21.11"); result.put("clientVersion", "0.3.0");
            result.put("testedClientSha256", proof.get("clientArtifactSha256")); result.put("testedServerSha256", proof.get("serverArtifactSha256"));
            result.put("artifactProof", proof);result.put("startedAtMillis", startedMillis);result.put("completedAtMillis", System.currentTimeMillis());
            result.put("checks", checks); result.put("screenshots", screenshots); result.put("observations", observations);
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
