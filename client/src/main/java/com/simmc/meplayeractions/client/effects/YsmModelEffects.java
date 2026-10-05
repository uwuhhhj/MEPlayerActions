package com.simmc.meplayeractions.client.effects;

import com.mojang.brigadier.StringReader;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.sound.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.argument.ParticleEffectArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;
import java.util.*;

/** Local effects belong to one exact owner/instance/hash/world. No network or external paths are used. */
public final class YsmModelEffects implements AutoCloseable {
    public static final int MAX_INSTANCE_LIVE = 32, MAX_GLOBAL_LIVE = 64;
    public static final int MAX_SOUNDS_PER_SECOND = 8, MAX_PARTICLES_PER_SECOND = 64;
    private static final long SOUND_LIFE_NANOS = 300_000_000_000L;
    private static final Budget GLOBAL = new Budget();
    private final MinecraftClient client;
    private final List<Playing> sounds = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final Map<String, YsmAudioRegistry.Lease> audio = new HashMap<>();
    private Entity owner;
    private ClientWorld world;
    private String instance = "", hash = "";
    private YsmModelProfile profile = YsmModelProfile.empty();
    private long soundStarts, particleStarts, rejected;
    public YsmModelEffects(MinecraftClient client) { this.client = Objects.requireNonNull(client); }

    public void update(Entity player, String instance, String hash, YsmModelProfile profile) {
        requireThread();
        if (player == null || client.world == null || player.getEntityWorld() != client.world || player.isRemoved()
                || instance == null || instance.isEmpty() || instance.length() > 128 || hash == null || !hash.matches("[a-f0-9]{64}")) {
            close(); return;
        }
        YsmModelProfile currentProfile = profile == null ? YsmModelProfile.empty() : profile;
        if (owner != player || world != client.world || !this.instance.equals(instance) || !this.hash.equals(hash) || this.profile != currentProfile) close();
        owner = player; world = client.world; this.instance = instance; this.hash = hash;
        this.profile = currentProfile; tick();
    }
    public Object handle(String function, List<Object> arguments) {
        return handle(function, arguments, false);
    }
    /** Native YSM function arity/ranges are separate from the legacy bbmodel keyframe shorthand. */
    public Object handle(String function, List<Object> arguments, boolean nativeYsm) {
        return handle(function, arguments, nativeYsm, "");
    }
    public Object handle(String function, List<Object> arguments, boolean nativeYsm, String controllerScope) {
        requireThread(); tick();
        if (owner == null || world == null || arguments == null) return false;
        try {
            return switch (function) {
                case "ysm.play_sound" -> play(nativeYsm ? SoundRequest.parseNative(arguments) : SoundRequest.parse(arguments), nativeYsm, controllerScope);
                case "ysm.stop_sound" -> stop(arguments, nativeYsm, controllerScope);
                case "ysm.stop_all_sounds" -> stopAll(arguments, nativeYsm, controllerScope);
                case "ysm.particle" -> emit(ParticleSpec.parse(arguments), false);
                case "ysm.abs_particle" -> emit(ParticleSpec.parse(arguments), true);
                default -> false;
            };
        } catch (IllegalArgumentException invalid) { rejected++; return false; }
    }
    private boolean play(SoundRequest request, boolean nativeYsm, String controllerScope) {
        String scope = soundScope(nativeYsm, request.global(), controllerScope);
        if (request.name().contains(":")) {
            Identifier id = Identifier.tryParse(request.name());
            // The private namespace is issued only after this profile authorizes its bytes.
            if (id == null || YsmAudioRegistry.isPrivate(id)) return false;
        }
        Playing existing = sounds.stream().filter(playing -> !request.key().isEmpty() && playing.key.equals(request.key())
                && playing.scope.equals(scope)).findFirst().orElse(null);
        if (existing != null && !request.replace()) return false;
        if (live() - (existing == null ? 0 : 1) >= MAX_INSTANCE_LIVE || !GLOBAL.charge(true, System.nanoTime())) { rejected++; return false; }
        Identifier identifier; boolean custom = !request.name().contains(":");
        try {
            if (custom) {
                var lease = audio.get(request.name());
                if (lease == null) {
                    byte[] ogg = profile.soundResource(request.name()).orElseThrow(() -> new IllegalArgumentException("Missing model sound"));
                    lease = YsmAudioRegistry.register(ogg); audio.put(request.name(), lease);
                }
                identifier = lease.soundId();
            } else identifier = Objects.requireNonNull(Identifier.tryParse(request.name()));
        } catch (RuntimeException invalid) { rejected++; return false; }
        if (existing != null) release(existing);
        if (!GLOBAL.reserve()) { rejected++; return false; }
        try {
            ModelSound sound = new ModelSound(identifier, owner, world, custom, request.loop(), request.volume(), request.pitch());
            SoundSystem.PlayResult result = client.getSoundManager().play(sound);
            if (result == SoundSystem.PlayResult.NOT_STARTED) { sound.finish(); GLOBAL.release(); return false; }
            sounds.add(new Playing(request.key(), scope, sound, System.nanoTime())); soundStarts++; return true;
        } catch (RuntimeException invalid) { GLOBAL.release(); rejected++; return false; }
    }
    private boolean stop(List<Object> arguments, boolean nativeYsm, String controllerScope) {
        if (arguments.size() < 1 || arguments.size() > 2) throw new IllegalArgumentException("Sound stop arguments");
        String key = nativeYsm ? nativeSoundKey(arguments.getFirst()) : soundKey(arguments.getFirst());
        boolean global = arguments.size() == 2 && number(arguments.get(1)) != 0;
        String scope = soundScope(nativeYsm, global, controllerScope);
        if (key.isEmpty()) return false;
        Playing found = sounds.stream().filter(playing -> playing.key.equals(key) && playing.scope.equals(scope)).findFirst().orElse(null);
        if (found == null) return false; release(found); return true;
    }
    private boolean stopAll(List<Object> arguments, boolean nativeYsm, String controllerScope) {
        if (arguments.size() > 1) throw new IllegalArgumentException("Sound stop arguments");
        boolean global = !arguments.isEmpty() && number(arguments.getFirst()) != 0;
        String scope = soundScope(nativeYsm, global, controllerScope);
        for (Playing playing : List.copyOf(sounds)) if (playing.scope.equals(scope)) release(playing);
        return true;
    }
    private void release(Playing playing) {
        if (!sounds.remove(playing)) return;
        playing.sound.finish(); client.getSoundManager().stop(playing.sound); GLOBAL.release();
    }
    private boolean emit(ParticleSpec spec, boolean absolute) {
        ParticleEffect effect;
        try {
            StringReader reader = new StringReader(spec.id());
            effect = ParticleEffectArgumentType.readParameters(reader, world.getRegistryManager());
            reader.skipWhitespace(); if (reader.canRead()) return false;
        } catch (Exception invalid) { rejected++; return false; }
        int spawned = 0, count = spec.count() == 0 ? 1 : spec.count();
        for (int index = 0; index < count; index++) {
            if (live() >= MAX_INSTANCE_LIVE || !GLOBAL.acquire(false, System.nanoTime())) { rejected++; break; }
            double spreadX = 0, spreadY = 0, spreadZ = 0;
            double velocityX = spec.speed() * spec.deltaX(), velocityY = spec.speed() * spec.deltaY(), velocityZ = spec.speed() * spec.deltaZ();
            if (spec.count() != 0) {
                var random = owner.getRandom();
                spreadX = bounded(random.nextGaussian() * spec.deltaX(), -32, 32);
                spreadY = bounded(random.nextGaussian() * spec.deltaY(), -32, 32);
                spreadZ = bounded(random.nextGaussian() * spec.deltaZ(), -32, 32);
                velocityX = random.nextGaussian() * spec.speed(); velocityY = random.nextGaussian() * spec.speed(); velocityZ = random.nextGaussian() * spec.speed();
            }
            double yaw = spec.count() == 0 && owner instanceof LivingEntity living ? living.bodyYaw : owner.getYaw();
            double[] offset = rotate(spec.offsetX() + spreadX, spec.offsetY() + spreadY, spec.offsetZ() + spreadZ, yaw, absolute);
            Particle particle;
            try {
                particle = client.particleManager.addParticle(effect, owner.getX() + offset[0], owner.getY() + offset[1], owner.getZ() + offset[2],
                        bounded(velocityX, -16, 16), bounded(velocityY, -16, 16), bounded(velocityZ, -16, 16));
            } catch (RuntimeException invalid) { GLOBAL.release(); rejected++; continue; }
            if (particle == null) { GLOBAL.release(); continue; }
            particle.setMaxAge(spec.lifetime()); particles.add(particle); spawned++; particleStarts++;
        }
        return spawned > 0;
    }
    public void tick() {
        requireThread();
        if (owner != null && (client.world != world || owner.getEntityWorld() != world || owner.isRemoved())) { close(); return; }
        long now = System.nanoTime();
        for (Playing playing : List.copyOf(sounds)) {
            playing.sound.tick();
            if (playing.sound.isDone() || now - playing.started > SOUND_LIFE_NANOS
                    || now - playing.started > 1_000_000_000L && !client.getSoundManager().isPlaying(playing.sound)) release(playing);
        }
        for (Iterator<Particle> iterator = particles.iterator(); iterator.hasNext();) {
            if (!iterator.next().isAlive()) { iterator.remove(); GLOBAL.release(); }
        }
    }
    private int live() { return sounds.size() + particles.size(); }
    @Override public void close() {
        requireThread();
        for (Playing playing : List.copyOf(sounds)) release(playing);
        for (Particle particle : particles) { particle.markDead(); GLOBAL.release(); } particles.clear();
        audio.values().forEach(YsmAudioRegistry.Lease::close); audio.clear(); owner = null; world = null;
        instance = ""; hash = ""; profile = YsmModelProfile.empty();
    }
    public Map<String, Object> diagnostics() {
        return Map.of("soundsStarted", soundStarts, "particlesStarted", particleStarts, "rejected", rejected,
                "liveSounds", sounds.size(), "liveParticles", particles.size(), "audioAssets", audio.size(), "globalLive", GLOBAL.live(),
                "customPlaying", sounds.stream().filter(playing -> playing.sound.custom && client.getSoundManager().isPlaying(playing.sound)).count(),
                "packPlaying", sounds.stream().filter(playing -> !playing.sound.custom && client.getSoundManager().isPlaying(playing.sound)).count());
    }
    private void requireThread() { if (!client.isOnThread()) throw new IllegalStateException("Model effects require the client thread"); }
    private record Playing(String key, String scope, ModelSound sound, long started) { }
    private static final class ModelSound extends AbstractSoundInstance implements TickableSoundInstance {
        private final Entity owner;
        private final ClientWorld world;
        private final boolean custom;
        private boolean done;
        ModelSound(Identifier id, Entity owner, ClientWorld world, boolean custom, boolean loop, float volume, float pitch) {
            super(id, SoundCategory.PLAYERS, SoundInstance.createRandom()); this.owner = owner; this.world = world; this.custom = custom;
            this.repeat = loop; this.repeatDelay = 0; this.volume = volume; this.pitch = pitch;
            this.attenuationType = SoundInstance.AttenuationType.LINEAR; tick();
        }
        @Override public WeightedSoundSet getSoundSet(SoundManager manager) {
            if (!custom) return super.getSoundSet(manager);
            sound = new Sound(id, ConstantFloatProvider.create(1), ConstantFloatProvider.create(1), 1,
                    Sound.RegistrationType.FILE, true, false, 16);
            var set = new WeightedSoundSet(id, null); set.add(sound); return set;
        }
        @Override public boolean isDone() { return done; }
        @Override public boolean canPlay() { return !done && owner.getEntityWorld() == world && !owner.isRemoved(); }
        @Override public void tick() {
            if (owner.isRemoved() || owner.getEntityWorld() != world) done = true;
            x = owner.getX(); y = owner.getY(); z = owner.getZ();
        }
        void finish() { done = true; }
    }
    public record SoundRequest(String key, String name, boolean replace, boolean global, boolean loop, float volume, float pitch) {
        public static SoundRequest parse(List<Object> arguments) {
            return parse(arguments, false);
        }
        public static SoundRequest parseNative(List<Object> arguments) {
            return parse(arguments, true);
        }
        private static SoundRequest parse(List<Object> arguments, boolean nativeYsm) {
            if (arguments.isEmpty() || arguments.size() > 5) throw new IllegalArgumentException("Sound arguments");
            if (nativeYsm && arguments.size() < 2) throw new IllegalArgumentException("Native PlaySound requires id and soundName");
            boolean keyframe = arguments.size() == 1;
            String key = keyframe ? "" : nativeYsm ? nativeSoundKey(arguments.get(0)) : soundKey(arguments.get(0));
            Object sound = arguments.get(keyframe ? 0 : 1);
            if (!(sound instanceof String name) || name.isBlank() || name.length() > 256 || name.chars().anyMatch(Character::isISOControl)
                    || !name.contains(":") && (name.contains("/") || name.contains("\\") || name.contains("..")))
                throw new IllegalArgumentException("Sound name");
            int flags = arguments.size() > 2 ? nativeYsm ? nativeInteger(arguments.get(2), 0, 7)
                    : integer(arguments.get(2), 0, 7) : 0;
            float volume = (float) (arguments.size() > 3 ? bounded(number(arguments.get(3)), .001, nativeYsm ? 1000 : 4) : 1);
            float pitch = (float) (arguments.size() > 4 ? bounded(number(arguments.get(4)), nativeYsm ? .001 : .01,
                    nativeYsm ? 1000 : 4) : 1);
            return new SoundRequest(key, name, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, volume, pitch);
        }
    }
    public record ParticleSpec(String id, double offsetX, double offsetY, double offsetZ, double deltaX, double deltaY,
                               double deltaZ, double speed, int count, int lifetime) {
        public static ParticleSpec parse(List<Object> arguments) {
            if (arguments.isEmpty() || arguments.size() > 10 || !(arguments.getFirst() instanceof String id) || id.isBlank() || id.length() > 512)
                throw new IllegalArgumentException("Particle arguments");
            return new ParticleSpec(id, optional(arguments, 1, 0, -32, 32), optional(arguments, 2, 0, -32, 32), optional(arguments, 3, 0, -32, 32),
                    optional(arguments, 4, 0, -16, 16), optional(arguments, 5, 0, -16, 16), optional(arguments, 6, 0, -16, 16),
                    optional(arguments, 7, 0, -4, 4), (int) optional(arguments, 8, 0, 0, 64), (int) optional(arguments, 9, 20, 1, 200));
        }
    }
    static String soundKey(Object value) {
        if (value instanceof String name && !name.isBlank() && name.length() <= 128 && name.chars().noneMatch(Character::isISOControl)) return "s:" + name;
        double number = number(value);
        if (number < 0 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Sound id");
        int id = (int) number; return id == 0 ? "" : "n:" + id;
    }
    /** Upstream truncates numeric IDs before rejecting negative IDs; string and numeric namespaces stay distinct. */
    static String nativeSoundKey(Object value) {
        if (!(value instanceof Number)) return soundKey(value);
        double number = number(value);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Sound id");
        int id = (int) number;
        if (id < 0) throw new IllegalArgumentException("Sound id");
        return id == 0 ? "" : "n:" + id;
    }
    /** An entity's global manager is also the native fallback when no controller/playback context exists. */
    static String soundScope(boolean nativeYsm, boolean global, String controllerScope) {
        if (!nativeYsm) return global ? "bbmodel.global" : "bbmodel.local";
        if (global || controllerScope == null || controllerScope.isEmpty()) return "ysm.global";
        if (controllerScope.length() > 512) throw new IllegalArgumentException("Sound context");
        return "ysm.local:" + controllerScope;
    }
    private static int nativeInteger(Object value, int min, int max) {
        double valueNumber = number(value);
        if (valueNumber < Integer.MIN_VALUE || valueNumber > Integer.MAX_VALUE) throw new IllegalArgumentException("Effect integer");
        int integer = (int) valueNumber;
        if (integer < min || integer > max) throw new IllegalArgumentException("Effect integer");
        return integer;
    }
    private static int integer(Object value, int min, int max) {
        double number = number(value);
        if (number < min || number > max) throw new IllegalArgumentException("Effect integer"); return (int) number;
    }
    private static double number(Object value) {
        double number = value instanceof Number numeric ? numeric.doubleValue() : value instanceof Boolean bool ? bool ? 1 : 0 : Double.NaN;
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Effect number"); return number;
    }
    private static double optional(List<Object> args, int index, double fallback, double min, double max) {
        return index >= args.size() ? fallback : bounded(number(args.get(index)), min, max);
    }
    private static double bounded(double number, double min, double max) { return Math.max(min, Math.min(max, number)); }
    static double[] rotate(double x, double y, double z, double yaw, boolean absolute) {
        if (absolute) return new double[]{x, y, z};
        double angle = -yaw * Math.PI / 180, cosine = Math.cos(angle), sine = Math.sin(angle);
        return new double[]{x * cosine + z * sine, y, z * cosine - x * sine};
    }
    /** A shared monotonic window survives rebinding; new contexts cannot reset the global quota. */
    static final class Budget {
        private long window = Long.MIN_VALUE;
        private int sounds, particles, live;
        synchronized boolean charge(boolean sound, long now) {
            if (window == Long.MIN_VALUE || now - window >= 1_000_000_000L) { window = now; sounds = 0; particles = 0; }
            if (sound ? sounds >= MAX_SOUNDS_PER_SECOND : particles >= MAX_PARTICLES_PER_SECOND) return false;
            if (sound) sounds++; else particles++; return true;
        }
        synchronized boolean reserve() {
            if (live >= MAX_GLOBAL_LIVE) return false; live++; return true;
        }
        synchronized boolean acquire(boolean sound, long now) { return charge(sound, now) && reserve(); }
        synchronized void release() { if (live <= 0) throw new IllegalStateException("Effect budget underflow"); live--; }
        synchronized int live() { return live; }
    }
}
