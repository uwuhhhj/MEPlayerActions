package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.effects.YsmAudioRegistry;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import com.simmc.meplayeractions.expression.Molang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Opt-in standalone acceptance: actual native callbacks, private OGG decoder, sound channels and particles. */
public final class ModelEffectsHarness implements AutoCloseable {
    private final ClientRuntime runtime;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Path> ownedFiles = new ArrayList<>(), ownedDirectories = new ArrayList<>();
    private MinecraftClient client;
    private LocalAppearanceSettings original;
    private Perspective perspective;
    private Path fixture, base;
    private String phase = "setup", fixtureId = "", fixtureHash = "", originalHash = "", vanillaSound = "", failure = "";
    private int ticks, phaseTicks, oggBytes;
    private long requestBaseline, openedBaseline, timelineSoundBaseline, timelineParticleBaseline, loopOpenedBaseline;
    private SoundInstance sentinel;
    private boolean complete, cleaned, reloadWasPlaying, resetWasPlaying;
    public ModelEffectsHarness(ClientRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    public boolean complete() { return complete; }
    public boolean passed() { return complete && checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))); }
    public List<Map<String, Object>> checks() { return List.copyOf(checks); }
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase); result.put("phaseTicks", phaseTicks); result.put("complete", complete); result.put("passed", passed());
        result.put("fixtureId", fixtureId); result.put("fixtureHash", fixtureHash); result.put("oggBytes", oggBytes); result.put("vanillaSound", vanillaSound);
        result.put("failure", failure); result.put("audio", YsmAudioRegistry.diagnostics()); result.put("runtime", runtime.modelEffectsDiagnostics());
        result.put("effectsActive", phase.equals("playing") && number(ownerEffects(), "customPlaying") > 0 && number(ownerEffects(), "liveParticles") > 0
                && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > loopOpenedBaseline && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0);
        return result;
    }
    public void tick(MinecraftClient client) {
        if (complete) return; this.client = client; ticks++; phaseTicks++;
        if (ticks > 420 && !phase.equals("restoring")) { fail("FX acceptance timeout"); restore(); }
        try {
            switch (phase) {
                case "setup" -> setup();
                case "binding" -> {
                    var binding = own();
                    if (binding != null && binding.assetHash().equals(fixtureHash) && runtime.localAppearance().modelId().equals(fixtureId)) {
                        check("standaloneYsmEffectsFixtureUsesCurrentBinding", runtime.localModelProfile().soundResource("tone").isPresent(), "Validated local YSM profile and current native owner/instance/hash");
                        openedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                        timelineSoundBaseline = number(ownerEffects(), "soundsStarted"); timelineParticleBaseline = number(ownerEffects(), "particlesStarted");
                        check("standaloneYsmEffectsTimelineStarted", runtime.playLocal("extra1"), "Play authored extra1 with sound_effects and particle_effects keyframes");
                        next("timeline");
                    } else if (phaseTicks > 120) { fail("Fixture model did not become ready"); restore(); }
                }
                case "timeline" -> {
                    if (phaseTicks >= 24) {
                        check("standaloneYsmSoundTimelineCallsNativeBackend", number(ownerEffects(), "soundsStarted") > timelineSoundBaseline
                                && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > openedBaseline, "Authored sound keyframe reached the native OGG stream decoder");
                        check("standaloneYsmParticleTimelineCallsNativeBackend", number(ownerEffects(), "particlesStarted") > timelineParticleBaseline, "Authored particle keyframe created a native Particle");
                        runtime.stopLocal(); loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                        evaluate("ysm.play_sound('fx_loop','tone',4,0.12,1); ysm.play_sound('fx_scope','" + vanillaSound + "',6,0.03,1); ysm.particle('minecraft:flame',0,1,0,0.1,0.1,0.1,0,4,80);");
                        next("playing");
                    }
                }
                case "playing" -> {
                    if (phaseTicks >= 30) {
                        check("standaloneYsmCustomOggStreamActuallyOpened", number(YsmAudioRegistry.diagnostics(), "streamsOpened") > loopOpenedBaseline
                                && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0, "Actual OggAudioStream construction and active streamed PCM source");
                        check("standaloneYsmCustomLoopSoundActuallyPlaying", number(ownerEffects(), "customPlaying") > 0, "SoundManager.isPlaying on the model-owned loop");
                        check("standaloneYsmNativePackSoundActuallyPlaying", number(ownerEffects(), "packPlaying") > 0, "Native namespace sound reuses the current Minecraft resource pack");
                        check("standaloneYsmParticlesActuallySpawned", number(ownerEffects(), "particlesStarted") >= timelineParticleBaseline + 5
                                && number(ownerEffects(), "liveParticles") > 0, "Actual native ParticleManager instances with authored lifetime");
                        evaluate("ysm.stop_sound('fx_loop');"); next("stopping");
                    }
                }
                case "stopping" -> {
                    if (phaseTicks >= 12) {
                        check("standaloneYsmStopSoundScoped", number(ownerEffects(), "customPlaying") == 0 && number(ownerEffects(), "packPlaying") > 0
                                && sentinelPlaying(), "Stopping one local slot preserves its sibling scope and unrelated native audio");
                        evaluate("ysm.stop_all_sounds(1);"); next("stopped");
                    }
                }
                case "stopped" -> {
                    if (phaseTicks >= 12) {
                        check("standaloneYsmStopAllScoped", number(ownerEffects(), "liveSounds") == 0 && sentinelPlaying(), "Stops only this binding's selected global audio context");
                        loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened"); evaluate("ysm.play_sound('fx_reload','tone',4,0.08,1);"); next("reload-start");
                    }
                }
                case "reload-start" -> {
                    reloadWasPlaying = number(ownerEffects(), "customPlaying") > 0 && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0
                            && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > loopOpenedBaseline;
                    if (phaseTicks >= 10 && reloadWasPlaying || phaseTicks >= 40) { runtime.resourcesReloaded(); next("reloading"); }
                }
                case "reloading" -> {
                    if (phaseTicks >= 12) {
                        check("standaloneYsmEffectsReloadCleanup", reloadWasPlaying && emptyAudio() && sentinelPlaying(), "Runtime resource-reload callback retires model routes/streams without stopping unrelated native audio");
                        loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                        evaluate("ysm.play_sound('fx_reset','tone',4,0.08,1);"); next("reset-start");
                    }
                }
                case "reset-start" -> {
                    resetWasPlaying = number(ownerEffects(), "customPlaying") > 0 && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0
                            && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > loopOpenedBaseline;
                    if (phaseTicks >= 10 && resetWasPlaying || phaseTicks >= 40) { runtime.reset(); runtime.joined(); next("resetting"); }
                }
                case "resetting" -> {
                    if (phaseTicks >= 12) {
                        check("standaloneYsmEffectsResetCleanup", resetWasPlaying && emptyAudio() && sentinelPlaying(), "Runtime reset closes owned model effects and leaves unrelated native audio intact"); restore();
                    }
                }
                case "restoring" -> {
                    var binding = own();
                    boolean ready = original != null && runtime.localAppearance().equals(original) && binding != null
                            && binding.assetHash().equals(originalHash) && ModelRenderer.has(binding.assetHash());
                    if (ready || phaseTicks > 120) {
                        check("standaloneYsmEffectsOriginalProfileRestored", ready, "Original private profile, same model hash and real GPU model restored");
                        check("standaloneYsmEffectsNoServerRequests", runtime.requestPacketsSent() == requestBaseline, "Model effects used native client callbacks without server action requests");
                        cleanup(); next("complete"); complete = true;
                    }
                }
            }
        } catch (Exception error) { fail(error.getClass().getSimpleName() + ": " + error.getMessage()); restore(); }
    }
    private void setup() throws Exception {
        if (client.player == null || client.world == null || own() == null) { if (phaseTicks > 120) throw new IOException("No active native owner"); return; }
        if (runtime.serverBridgeReady()) throw new IOException("This fixture is limited to the isolated no-plugin acceptance");
        original = runtime.localAppearance(); originalHash = own().assetHash(); perspective = client.options.getPerspective();
        requestBaseline = runtime.requestPacketsSent(); byte[] ogg = existingOgg(); oggBytes = ogg.length;
        base = runtime.localModelDirectory().toAbsolutePath().normalize(); Files.createDirectories(base); base = base.toRealPath();
        fixture = Files.createTempDirectory(base, "mpa-effects-fixture-").toAbsolutePath().normalize();
        for (String child : List.of("models", "animations", "textures", "sounds")) {
            Path directory = fixture.resolve(child); Files.createDirectory(directory); ownedDirectories.add(directory);
        }
        write("ysm.json", """
            {"spec":2,"metadata":{"name":"Local effect acceptance"},"properties":{"default_texture":"default","extra_animation":{"extra1":"FX"}},
            "files":{"player":{"model":{"main":"models/main.json"},"animation":{"main":"animations/main.json","extra":"animations/extra.json"},"texture":"textures/default.png"},"sound_path":"sounds"}}
            """);
        write("models/main.json", """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.fx_acceptance","texture_width":16,"texture_height":16},
            "bones":[{"name":"root","pivot":[0,0,0],"cubes":[{"origin":[-2,0,-2],"size":[4,4,4],"uv":[0,0]}]}]}]}
            """);
        write("animations/main.json", "{\"format_version\":\"1.8.0\",\"animations\":{\"idle\":{\"loop\":true,\"animation_length\":1,\"bones\":{}}}}");
        write("animations/extra.json", """
            {"format_version":"1.8.0","animations":{"extra1":{"loop":false,"animation_length":1,"bones":{},
            "sound_effects":{"0.1":{"effect":"tone"}},"particle_effects":{"0.1":{"effect":"minecraft:flame"}}}}}
            """);
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xffffaa44);
        Path png = fixture.resolve("textures/default.png"); ownedFiles.add(png);
        if (!ImageIO.write(image, "png", png.toFile())) throw new IOException("PNG fixture encoder unavailable");
        Path sound = fixture.resolve("sounds/tone.ogg"); ownedFiles.add(sound); Files.write(sound, ogg);
        var imported = YsmFolderModel.readWithPreview(fixture); fixtureHash = AssetTransfer.hash(imported.raw());
        fixtureId = "ysm:" + fixture.getFileName(); client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        sentinel = new PositionedSoundInstance(Identifier.of(vanillaSound), SoundCategory.PLAYERS, .02f, 1,
                SoundInstance.createRandom(), true, 0, SoundInstance.AttenuationType.NONE, client.player.getX(), client.player.getY(), client.player.getZ(), false);
        client.getSoundManager().play(sentinel); runtime.selectLocalModel(fixtureId); next("binding");
    }
    private byte[] existingOgg() throws IOException {
        for (String event : List.of("minecraft:entity.experience_orb.pickup", "minecraft:block.note_block.pling", "minecraft:block.amethyst_block.hit")) {
            var set = client.getSoundManager().get(Identifier.of(event)); if (set == null) continue;
            var sound = set.getSound(SoundInstance.createRandom());
            Identifier location = sound.getLocation(); if (!location.getNamespace().equals("minecraft")) continue;
            var resource = client.getResourceManager().getResource(location); if (resource.isEmpty()) continue;
            try (var input = resource.get().getInputStream()) {
                byte[] bytes = input.readNBytes(262_145); if (bytes.length > 262_144) continue;
                try { YsmAudioRegistry.validateOgg(bytes); vanillaSound = event; return bytes; }
                catch (IllegalArgumentException unsupported) { }
            }
        }
        throw new IOException("No bounded vanilla Vorbis resource available");
    }
    private void write(String name, String text) throws IOException {
        Path path = fixture.resolve(name); ownedFiles.add(path); Files.writeString(path, text);
    }
    private double evaluate(String script) {
        var context = new Molang.Context(); context.frame(Map.of()); runtime.configureExpressionContext(client.player.getUuid(), context);
        return Molang.compile(script).evaluate(context);
    }
    private ClientRuntime.RenderBinding own() { return client == null || client.player == null ? null : runtime.appearanceBinding(client.player.getUuid()); }
    private Map<?, ?> ownerEffects() {
        if (client == null || client.player == null) return Map.of();
        Object value = runtime.modelEffectsDiagnostics().get(client.player.getUuid().toString()); return value instanceof Map<?, ?> map ? map : Map.of();
    }
    private static long number(Map<?, ?> map, String key) { Object value = map.get(key); return value instanceof Number numeric ? numeric.longValue() : 0; }
    private boolean sentinelPlaying() { return sentinel != null && client.getSoundManager().isPlaying(sentinel); }
    private boolean emptyAudio() { return number(YsmAudioRegistry.diagnostics(), "routes") == 0 && number(YsmAudioRegistry.diagnostics(), "activeStreams") == 0 && number(ownerEffects(), "liveSounds") == 0; }
    private void next(String phase) { this.phase = phase; phaseTicks = 0; }
    private void check(String name, boolean passed, String detail) {
        if (checks.stream().anyMatch(check -> name.equals(check.get("name")))) return;
        checks.add(Map.of("name", name, "passed", passed, "detail", detail));
    }
    private void fail(String reason) { failure = reason; check("standaloneYsmEffectsExecution", false, reason); }
    private void restore() {
        if (phase.equals("restoring")) return;
        if (client != null && sentinel != null) client.getSoundManager().stop(sentinel);
        if (original != null) runtime.updateLocalAppearance(original);
        if (client != null && perspective != null) client.options.setPerspective(perspective); next("restoring");
    }
    private void cleanup() {
        if (cleaned) return; boolean success = true;
        try {
            if (fixture != null) {
                if (!fixture.getParent().equals(base) || Files.isSymbolicLink(fixture) || !fixture.toRealPath().equals(fixture))
                    throw new IOException("Unexpected fixture cleanup target");
                for (Path file : ownedFiles) {
                    if (!file.normalize().startsWith(fixture) || Files.isSymbolicLink(file)) throw new IOException("Unexpected fixture file");
                    Files.deleteIfExists(file);
                }
                for (Path directory : ownedDirectories) Files.deleteIfExists(directory);
                Files.deleteIfExists(fixture);
            }
        } catch (IOException invalid) { success = false; failure = "Fixture cleanup incomplete: " + invalid.getClass().getSimpleName(); }
        cleaned = true; check("standaloneYsmEffectsFixtureCleaned", success, "Only this acceptance's generated files and empty directories were removed");
    }
    @Override public void close() { if (!complete) restore(); cleanup(); }
}
