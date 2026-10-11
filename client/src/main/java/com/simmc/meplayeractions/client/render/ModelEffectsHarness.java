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
import net.minecraft.resource.Resource;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Opt-in standalone acceptance: actual native callbacks, private OGG decoder, sound channels and particles. */
public final class ModelEffectsHarness implements AutoCloseable
{
    private final ClientRuntime runtime;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Path> ownedFiles = new ArrayList<>(), ownedDirectories = new ArrayList<>();
    private MinecraftClient client;
    private LocalAppearanceSettings original;
    private Perspective perspective;
    private Path fixture, base;
    private String phase = "setup", fixtureId = "", fixtureHash = "", originalHash = "", vanillaSound = "",
            failure = "";
    private int ticks, phaseTicks, oggBytes;
    private long requestBaseline, openedBaseline, timelineSoundBaseline, timelineParticleBaseline, loopOpenedBaseline;
    private SoundInstance sentinel;
    private boolean complete, cleaned, reloadWasPlaying, resetWasPlaying;

    public ModelEffectsHarness(ClientRuntime runtime)
    {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public boolean complete()
    {
        return this.complete;
    }

    public boolean passed()
    {
        return this.complete && this.checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
    }

    public List<Map<String, Object>> checks()
    {
        return List.copyOf(this.checks);
    }

    public Map<String, Object> diagnostics()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", this.phase);
        result.put("phaseTicks", this.phaseTicks);
        result.put("complete", this.complete);
        result.put("passed", passed());
        result.put("fixtureId", this.fixtureId);
        result.put("fixtureHash", this.fixtureHash);
        result.put("oggBytes", this.oggBytes);
        result.put("vanillaSound", this.vanillaSound);
        result.put("failure", this.failure);
        result.put("audio", YsmAudioRegistry.diagnostics());
        result.put("runtime", this.runtime.modelEffectsDiagnostics());
        result.put("effectsActive",
                this.phase.equals("playing") && number(ownerEffects(), "customPlaying") > 0
                        && number(ownerEffects(), "liveParticles") > 0
                        && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > this.loopOpenedBaseline
                        && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0);
        return result;
    }

    public void tick(MinecraftClient client)
    {
        if (this.complete)
        {
            return;
        }
        this.client = client;
        this.ticks++;
        this.phaseTicks++;
        if (this.ticks > 420 && !this.phase.equals("restoring"))
        {
            fail("FX acceptance timeout");
            restore();
        }
        try
        {
            switch (this.phase)
            {
            case "setup" -> setup();
            case "binding" ->
            {
                ClientRuntime.RenderBinding binding = own();
                if (binding != null && binding.assetHash().equals(this.fixtureHash)
                        && this.runtime.localAppearance().modelId().equals(this.fixtureId))
                {
                    check("standaloneYsmEffectsFixtureUsesCurrentBinding",
                            this.runtime.localModelProfile().soundResource("tone").isPresent(),
                            "Validated local YSM profile and current native owner/instance/hash");
                    this.openedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                    this.timelineSoundBaseline = number(ownerEffects(), "soundsStarted");
                    this.timelineParticleBaseline = number(ownerEffects(), "particlesStarted");
                    check("standaloneYsmEffectsTimelineStarted", this.runtime.playLocal("extra1"),
                            "Play authored extra1 with sound_effects and particle_effects keyframes");
                    next("timeline");
                }
                else if (this.phaseTicks > 120)
                {
                    fail("Fixture model did not become ready");
                    restore();
                }
            }
            case "timeline" ->
            {
                if (this.phaseTicks >= 24)
                {
                    check("standaloneYsmSoundTimelineCallsNativeBackend",
                            number(ownerEffects(), "soundsStarted") > this.timelineSoundBaseline
                                    && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > this.openedBaseline,
                            "Authored sound keyframe reached the native OGG stream decoder");
                    check("standaloneYsmParticleTimelineCallsNativeBackend",
                            number(ownerEffects(), "particlesStarted") > this.timelineParticleBaseline,
                            "Authored particle keyframe created a native Particle");
                    this.runtime.stopLocal();
                    this.loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                    evaluate(
                            "ysm.play_sound('fx_loop','tone',4,0.12,1); ysm.play_sound('fx_scope','" + this.vanillaSound
                                    + "',6,0.03,1); ysm.particle('minecraft:flame',0,1,0,0.1,0.1,0.1,0,4,80);");
                    next("playing");
                }
            }
            case "playing" ->
            {
                if (this.phaseTicks >= 30)
                {
                    check("standaloneYsmCustomOggStreamActuallyOpened",
                            number(YsmAudioRegistry.diagnostics(), "streamsOpened") > this.loopOpenedBaseline
                                    && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0,
                            "Actual OggAudioStream construction and active streamed PCM source");
                    check("standaloneYsmCustomLoopSoundActuallyPlaying", number(ownerEffects(), "customPlaying") > 0,
                            "SoundManager.isPlaying on the model-owned loop");
                    check("standaloneYsmNativePackSoundActuallyPlaying", number(ownerEffects(), "packPlaying") > 0,
                            "Native namespace sound reuses the current Minecraft resource pack");
                    check("standaloneYsmParticlesActuallySpawned",
                            number(ownerEffects(), "particlesStarted") >= this.timelineParticleBaseline + 5
                                    && number(ownerEffects(), "liveParticles") > 0,
                            "Actual native ParticleManager instances with authored lifetime");
                    evaluate("ysm.stop_sound('fx_loop');");
                    next("stopping");
                }
            }
            case "stopping" ->
            {
                if (this.phaseTicks >= 12)
                {
                    check("standaloneYsmStopSoundScoped",
                            number(ownerEffects(), "customPlaying") == 0 && number(ownerEffects(), "packPlaying") > 0
                                    && sentinelPlaying(),
                            "Stopping one local slot preserves its sibling scope and unrelated native audio");
                    evaluate("ysm.stop_all_sounds(1);");
                    next("stopped");
                }
            }
            case "stopped" ->
            {
                if (this.phaseTicks >= 12)
                {
                    check("standaloneYsmStopAllScoped", number(ownerEffects(), "liveSounds") == 0 && sentinelPlaying(),
                            "Stops only this binding's selected global audio context");
                    this.loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                    evaluate("ysm.play_sound('fx_reload','tone',4,0.08,1);");
                    next("reload-start");
                }
            }
            case "reload-start" ->
            {
                this.reloadWasPlaying = number(ownerEffects(), "customPlaying") > 0
                        && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0
                        && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > this.loopOpenedBaseline;
                if (this.phaseTicks >= 10 && this.reloadWasPlaying || this.phaseTicks >= 40)
                {
                    this.runtime.resourcesReloaded();
                    next("reloading");
                }
            }
            case "reloading" ->
            {
                if (this.phaseTicks >= 12)
                {
                    check("standaloneYsmEffectsReloadCleanup",
                            this.reloadWasPlaying && emptyAudio() && sentinelPlaying(),
                            "Runtime resource-reload callback retires model routes/streams without stopping unrelated native audio");
                    this.loopOpenedBaseline = number(YsmAudioRegistry.diagnostics(), "streamsOpened");
                    evaluate("ysm.play_sound('fx_reset','tone',4,0.08,1);");
                    next("reset-start");
                }
            }
            case "reset-start" ->
            {
                this.resetWasPlaying = number(ownerEffects(), "customPlaying") > 0
                        && number(YsmAudioRegistry.diagnostics(), "activeStreams") > 0
                        && number(YsmAudioRegistry.diagnostics(), "streamsOpened") > this.loopOpenedBaseline;
                if (this.phaseTicks >= 10 && this.resetWasPlaying || this.phaseTicks >= 40)
                {
                    this.runtime.reset();
                    this.runtime.joined();
                    next("resetting");
                }
            }
            case "resetting" ->
            {
                if (this.phaseTicks >= 12)
                {
                    check("standaloneYsmEffectsResetCleanup", this.resetWasPlaying && emptyAudio() && sentinelPlaying(),
                            "Runtime reset closes owned model effects and leaves unrelated native audio intact");
                    restore();
                }
            }
            case "restoring" ->
            {
                ClientRuntime.RenderBinding binding = own();
                boolean ready = this.original != null && this.runtime.localAppearance().equals(this.original)
                        && binding != null && binding.assetHash().equals(this.originalHash)
                        && ModelRenderer.has(binding.assetHash());
                if (ready || this.phaseTicks > 120)
                {
                    check("standaloneYsmEffectsOriginalProfileRestored", ready,
                            "Original private profile, same model hash and real GPU model restored");
                    check("standaloneYsmEffectsNoServerRequests",
                            this.runtime.requestPacketsSent() == this.requestBaseline,
                            "Model effects used native client callbacks without server action requests");
                    cleanup();
                    next("complete");
                    this.complete = true;
                }
            }
            }
        }
        catch (Exception error)
        {
            fail(error.getClass().getSimpleName() + ": " + error.getMessage());
            restore();
        }
    }

    private void setup() throws Exception
    {
        if (this.client.player == null || this.client.world == null || own() == null)
        {
            if (this.phaseTicks > 120)
            {
                throw new IOException("No active native owner");
            }
            return;
        }
        if (this.runtime.serverBridgeReady())
        {
            throw new IOException("This fixture is limited to the isolated no-plugin acceptance");
        }

        this.original = this.runtime.localAppearance();
        this.originalHash = own().assetHash();
        this.perspective = this.client.options.getPerspective();
        this.requestBaseline = this.runtime.requestPacketsSent();
        byte[] ogg = existingOgg();
        this.oggBytes = ogg.length;
        this.base = this.runtime.localModelDirectory().toAbsolutePath().normalize();
        Files.createDirectories(this.base);
        this.base = this.base.toRealPath();
        this.fixture = Files.createTempDirectory(this.base, "mpa-effects-fixture-").toAbsolutePath().normalize();
        for (String child : List.of("models", "animations", "textures", "sounds"))
        {
            Path directory = this.fixture.resolve(child);
            Files.createDirectory(directory);
            this.ownedDirectories.add(directory);
        }
        write("ysm.json",
                """
                        {"spec":2,"metadata":{"name":"Local effect acceptance"},"properties":{"default_texture":"default","extra_animation":{"extra1":"FX"}},
                        "files":{"player":{"model":{"main":"models/main.json"},"animation":{"main":"animations/main.json","extra":"animations/extra.json"},"texture":"textures/default.png"},"sound_path":"sounds"}}
                        """);
        write("models/main.json",
                """
                        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.fx_acceptance","texture_width":16,"texture_height":16},
                        "bones":[{"name":"root","pivot":[0,0,0],"cubes":[{"origin":[-2,0,-2],"size":[4,4,4],"uv":[0,0]}]}]}]}
                        """);
        write("animations/main.json",
                "{\"format_version\":\"1.8.0\",\"animations\":{\"idle\":{\"loop\":true,\"animation_length\":1,\"bones\":{}}}}");
        write("animations/extra.json", """
                {"format_version":"1.8.0","animations":{"extra1":{"loop":false,"animation_length":1,"bones":{},
                "sound_effects":{"0.1":{"effect":"tone"}},"particle_effects":{"0.1":{"effect":"minecraft:flame"}}}}}
                """);
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
        {
            for (int x = 0; x < 16; x++)
            {
                image.setRGB(x, y, 0xffffaa44);
            }

        }

        Path png = this.fixture.resolve("textures/default.png");
        this.ownedFiles.add(png);
        if (!ImageIO.write(image, "png", png.toFile()))
        {
            throw new IOException("PNG fixture encoder unavailable");
        }

        Path sound = this.fixture.resolve("sounds/tone.ogg");
        this.ownedFiles.add(sound);
        Files.write(sound, ogg);
        YsmFolderModel.Imported imported = YsmFolderModel.readWithPreview(this.fixture);
        this.fixtureHash = AssetTransfer.hash(imported.raw());
        this.fixtureId = "ysm:" + this.fixture.getFileName();
        this.client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        this.sentinel = new PositionedSoundInstance(Identifier.of(this.vanillaSound), SoundCategory.PLAYERS, .02f, 1,
                SoundInstance.createRandom(), true, 0, SoundInstance.AttenuationType.NONE, this.client.player.getX(),
                this.client.player.getY(), this.client.player.getZ(), false);
        this.client.getSoundManager().play(this.sentinel);
        this.runtime.selectLocalModel(this.fixtureId);
        next("binding");
    }

    private byte[] existingOgg() throws IOException
    {
        for (String event : List.of("minecraft:entity.experience_orb.pickup", "minecraft:block.note_block.pling",
                "minecraft:block.amethyst_block.hit"))
        {
            WeightedSoundSet set = this.client.getSoundManager().get(Identifier.of(event));
            if (set == null)
            {
                continue;
            }

            Sound sound = set.getSound(SoundInstance.createRandom());
            Identifier location = sound.getLocation();
            if (!location.getNamespace().equals("minecraft"))
            {
                continue;
            }

            Optional<Resource> resource = this.client.getResourceManager().getResource(location);
            if (resource.isEmpty())
            {
                continue;
            }

            try (InputStream input = resource.get().getInputStream())
            {
                byte[] bytes = input.readNBytes(262_145);
                if (bytes.length > 262_144)
                {
                    continue;
                }

                try
                {
                    YsmAudioRegistry.validateOgg(bytes);
                    this.vanillaSound = event;
                    return bytes;
                }
                catch (IllegalArgumentException unsupported)
                {
                }
            }
        }
        throw new IOException("No bounded vanilla Vorbis resource available");
    }

    private void write(String name, String text) throws IOException
    {
        Path path = this.fixture.resolve(name);
        this.ownedFiles.add(path);
        Files.writeString(path, text);
    }

    private double evaluate(String script)
    {
        Molang.Context context = new Molang.Context();
        context.frame(Map.of());
        this.runtime.configureExpressionContext(this.client.player.getUuid(), context);
        return Molang.compile(script).evaluate(context);
    }

    private ClientRuntime.RenderBinding own()
    {
        return this.client == null || this.client.player == null ? null
                : this.runtime.appearanceBinding(this.client.player.getUuid());
    }

    private Map<?, ?> ownerEffects()
    {
        if (this.client == null || this.client.player == null)
        {
            return Map.of();
        }

        Object value = this.runtime.modelEffectsDiagnostics().get(this.client.player.getUuid().toString());
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static long number(Map<?, ?> map, String key)
    {
        Object value = map.get(key);
        return value instanceof Number numeric ? numeric.longValue() : 0;
    }

    private boolean sentinelPlaying()
    {
        return this.sentinel != null && this.client.getSoundManager().isPlaying(this.sentinel);
    }

    private boolean emptyAudio()
    {
        return number(YsmAudioRegistry.diagnostics(), "routes") == 0
                && number(YsmAudioRegistry.diagnostics(), "activeStreams") == 0
                && number(ownerEffects(), "liveSounds") == 0;
    }

    private void next(String phase)
    {
        this.phase = phase;
        this.phaseTicks = 0;
    }

    private void check(String name, boolean passed, String detail)
    {
        if (this.checks.stream().anyMatch(check -> name.equals(check.get("name"))))
        {
            return;
        }

        this.checks.add(Map.of("name", name, "passed", passed, "detail", detail));
    }

    private void fail(String reason)
    {
        this.failure = reason;
        check("standaloneYsmEffectsExecution", false, reason);
    }

    private void restore()
    {
        if (this.phase.equals("restoring"))
        {
            return;
        }

        if (this.client != null && this.sentinel != null)
        {
            this.client.getSoundManager().stop(this.sentinel);
        }

        if (this.original != null)
        {
            this.runtime.updateLocalAppearance(this.original);
        }

        if (this.client != null && this.perspective != null)
        {
            this.client.options.setPerspective(this.perspective);
        }
        next("restoring");
    }

    private void cleanup()
    {
        if (this.cleaned)
        {
            return;
        }
        boolean success = true;
        try
        {
            if (this.fixture != null)
            {
                if (!this.fixture.getParent().equals(this.base) || Files.isSymbolicLink(this.fixture)
                        || !this.fixture.toRealPath().equals(this.fixture))

                {
                    throw new IOException("Unexpected fixture cleanup target");
                }

                for (Path file : this.ownedFiles)
                {
                    if (!file.normalize().startsWith(this.fixture) || Files.isSymbolicLink(file))
                    {
                        throw new IOException("Unexpected fixture file");
                    }

                    Files.deleteIfExists(file);
                }
                for (Path directory : this.ownedDirectories)
                {
                    Files.deleteIfExists(directory);
                }

                Files.deleteIfExists(this.fixture);
            }
        }
        catch (IOException invalid)
        {
            success = false;
            this.failure = "Fixture cleanup incomplete: " + invalid.getClass().getSimpleName();
        }
        this.cleaned = true;
        check("standaloneYsmEffectsFixtureCleaned", success,
                "Only this acceptance's generated files and empty directories were removed");
    }

    @Override
    public void close()
    {
        if (!this.complete)
        {
            restore();
        }
        cleanup();
    }
}
