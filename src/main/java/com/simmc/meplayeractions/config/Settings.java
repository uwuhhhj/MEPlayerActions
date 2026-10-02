package com.simmc.meplayeractions.config;

import com.simmc.meplayeractions.action.ActionState;
import com.simmc.meplayeractions.action.SyncFeature;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.regex.Pattern;

public final class Settings {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Set<String> ACTION_FIELDS = Set.of("enabled", "label", "animation", "loop",
            "speed", "permission", "max-duration-ticks");
    private static final Set<String> ANIMATION_FIELDS = Set.copyOf(Arrays.stream(ActionState.values())
            .map(ActionState::key).toList());
    public final String defaultModel;
    public final Set<String> allowedModels;
    public final double scale, manualMinSpeed, manualMaxSpeed, movementThreshold, flightSpeed;
    public final int interval, animationInterval, inTicks, outTicks, posturePriority, interactionPriority, manualPriority,
            cooldownTicks, maxManualTicks, clientMaxPayload, clientCooldownTicks, swingTicks, miningTimeoutTicks,
            visualDelayTicks, jumpMinTicks, jumpLandingTicks;
    public final boolean hideSelf, adoptOriginal, syncEnabled, interruptMove, interruptDamage,
            interruptPosture, rawPlay, allowFlight, clientEnabled, legacyNpcCrawlMigrated, visualSnapPostures;
    public final double visualMaxDistance;
    public final double clientViewDistance;
    public final boolean showSelf;
    public final double modelViewDistance;
    public final int maxViewers;
    public final Map<String, CustomAction> customActions;
    public final Map<String, String> animationLabels;
    private final EnumMap<SyncFeature, Boolean> synchronization;
    private final Map<String, EnumMap<ActionState, List<String>>> animationMaps;
    private final EnumMap<ActionState, Playback> playback = new EnumMap<>(ActionState.class);
    public record Playback(double speed, LoopMode loop, int inTicks, int outTicks) {}

    public record CustomAction(String id, String label, String animation, LoopMode loop,
                               double speed, String permission, int maxTicks) {}

    private Settings(FileConfiguration c) {
        defaultModel = id(c.getString("models.default", "ysm_01_jk_player"));
        Set<String> models = new LinkedHashSet<>();
        for (String name : c.getStringList("models.allowed")) models.add(id(name));
        if (models.isEmpty()) throw new IllegalArgumentException("models.allowed 至少需要一个模型");
        if (!models.contains(defaultModel)) throw new IllegalArgumentException("默认模型必须包含在 models.allowed");
        allowedModels = Collections.unmodifiableSet(models);
        scale = number(c, "disguise.scale", 1, 0.05, 8);
        hideSelf = c.getBoolean("disguise.hide-self", true);
        showSelf = c.getBoolean("disguise.show-self", true);
        modelViewDistance = number(c, "disguise.view-distance", 8, 0.1, 256);
        maxViewers = integer(c, "disguise.max-viewers", 10, 0, 1000);
        adoptOriginal = c.getBoolean("disguise.adopt-original", true);
        interval = c.contains("controller.sample-interval-ticks", true)
                ? integer(c, "controller.sample-interval-ticks", 1, 1, 20)
                : integer(c, "controller.interval-ticks", 1, 1, 20);
        animationInterval = integer(c, "controller.animation-update-interval-ticks", 1, 1, 20);
        inTicks = integer(c, "controller.transition-in-ticks", 2, 0, 100);
        outTicks = integer(c, "controller.transition-out-ticks", 2, 0, 100);
        posturePriority = integer(c, "controller.posture-priority", 100, 1, 10000);
        interactionPriority = integer(c, "controller.interaction-priority", 150, 1, 10000);
        manualPriority = integer(c, "controller.manual-priority", 200, 1, 10000);
        if (interactionPriority <= posturePriority || manualPriority <= interactionPriority)
            throw new IllegalArgumentException("优先级须满足 posture-priority < interaction-priority < manual-priority");
        swingTicks = integer(c, "controller.swing-duration-ticks", 8, 1, 100);
        miningTimeoutTicks = integer(c, "controller.mining-timeout-ticks", 12, 2, 1200);
        visualDelayTicks = integer(c, "visual-follow.delay-ticks", 2, 0, 20);
        visualMaxDistance = number(c, "visual-follow.max-distance-blocks", 2, 0.1, 8);
        visualSnapPostures = c.getBoolean("visual-follow.snap-on-posture-change", true);
        jumpMinTicks = integer(c, "jump.minimum-play-ticks", 0, 0, 100);
        jumpLandingTicks = integer(c, "jump.landing-grace-ticks", 6, 0, 40);
        movementThreshold = number(c, "controller.movement-threshold", 0.015, 0.0001, 1);
        syncEnabled = c.getBoolean("synchronization.enabled", true);
        synchronization = new EnumMap<>(SyncFeature.class);
        for (SyncFeature f : SyncFeature.values()) synchronization.put(f, c.getBoolean("synchronization." + f.key(), true));
        interruptMove = c.getBoolean("manual.interrupt-on-movement", true);
        interruptDamage = c.getBoolean("manual.interrupt-on-damage", true);
        interruptPosture = c.getBoolean("manual.interrupt-on-posture", true);
        rawPlay = c.getBoolean("manual.allow-raw-animation", true);
        cooldownTicks = integer(c, "manual.cooldown-ticks", 4, 0, 1200);
        maxManualTicks = integer(c, "manual.max-duration-ticks", 600, 1, 72000);
        manualMinSpeed = number(c, "manual.min-speed", 0.1, 0.01, 10);
        manualMaxSpeed = number(c, "manual.max-speed", 5, manualMinSpeed, 20);
        allowFlight = c.getBoolean("gameplay.allow-flight-command", false);
        flightSpeed = number(c, "gameplay.flight-speed", 0.1, 0.001, 1);
        clientEnabled = c.getBoolean("client-sync.enabled", true);
        clientMaxPayload = integer(c, "client-sync.max-payload-bytes", 16000, 1024, 30000);
        clientCooldownTicks = integer(c, "client-sync.request-cooldown-ticks", 4, 1, 1200);
        clientViewDistance = number(c, "client-sync.view-distance-blocks", 64, 1, 256);
        animationMaps = new HashMap<>();
        animationMaps.put("defaults", parseAnimations(c.getConfigurationSection("animations.defaults")));
        // New mappings also work when an administrator retains a 0.1.0 configuration.
        var defaults = animationMaps.get("defaults");
        defaults.putIfAbsent(ActionState.SLEEP, List.of("sleep", "lay"));
        defaults.putIfAbsent(ActionState.BOAT, List.of("sit_boat", "boat", "sit"));
        defaults.putIfAbsent(ActionState.MINECART, List.of("sit_minecart", "minecart", "sit"));
        defaults.putIfAbsent(ActionState.RIDE, List.of("ride", "sit"));
        defaults.putIfAbsent(ActionState.SWING_MAINHAND, List.of("attack", "swing", "use_mainhand"));
        defaults.putIfAbsent(ActionState.SWING_OFFHAND, List.of("attack_offhand", "use_offhand"));
        defaults.putIfAbsent(ActionState.MINING, List.of("mining", "dig", "attack", "use_mainhand"));
        defaults.putIfAbsent(ActionState.FALL, List.of("fall", "jump", "idle"));
        defaults.putIfAbsent(ActionState.SWIM_PRONE_IDLE, List.of("swim_prone_idle", "swim"));
        if (configVersionFor(c) < 3 && defaults.getOrDefault(ActionState.JUMP, List.of()).equals(List.of("jump")))
            defaults.put(ActionState.JUMP, List.of("player_jump", "jump"));
        ConfigurationSection profile = c.getConfigurationSection("animations.models");
        boolean migrated = false;
        int configVersion = configVersionFor(c);
        if (profile != null) for (String model : profile.getKeys(false)) {
            if (!profile.isConfigurationSection(model))
                throw new IllegalArgumentException("animations.models." + model + " 必须是动画状态配置节");
            var mappings = parseAnimations(profile.getConfigurationSection(model));
            // Versions 1/2 shipped NPC crawl as []. Restore only that known legacy
            // default; version 3 and all other explicit per-model disables remain valid.
            if (configVersion < 3 && model.equals("ysm_01_jk_npc")) {
                for (ActionState state : List.of(ActionState.CRAWL_IDLE, ActionState.CRAWL_WALK)) {
                    if (mappings.containsKey(state) && mappings.get(state).isEmpty()) {
                        mappings.remove(state); migrated = true;
                    }
                }
            }
            animationMaps.put(id(model), mappings);
        }
        legacyNpcCrawlMigrated = migrated;
        ConfigurationSection tuning = c.getConfigurationSection("animation-settings");
        if (tuning != null) for (String key : tuning.getKeys(false)) {
            if (!ANIMATION_FIELDS.contains(key) || !tuning.isConfigurationSection(key))
                throw new IllegalArgumentException("animation-settings." + key + " 必须是已知状态配置节");
        }
        for (ActionState state : ActionState.values()) {
            ConfigurationSection options = tuning == null ? null : tuning.getConfigurationSection(state.key());
            LoopMode loop = switch (state) {
                case JUMP, SWING_MAINHAND, SWING_OFFHAND -> LoopMode.ONCE;
                case FALL, SIT, SLEEP, BOAT, MINECART, RIDE, CRAWL_IDLE, CROUCH_IDLE -> LoopMode.HOLD;
                default -> LoopMode.LOOP;
            };
            if (options == null) { playback.put(state, new Playback(1, loop, inTicks, outTicks)); continue; }
            validateFields(options, Set.of("speed", "loop", "transition-in-ticks", "transition-out-ticks"));
            try { loop = LoopMode.valueOf(options.getString("loop", loop.name()).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("animation-settings." + state.key() + ".loop 无效"); }
            playback.put(state, new Playback(number(options, "speed", 1, 0.05, 20), loop,
                    integer(options, "transition-in-ticks", inTicks, 0, 100),
                    integer(options, "transition-out-ticks", outTicks, 0, 100)));
        }
        Map<String, String> labels = new LinkedHashMap<>(AnimationLabels.DEFAULTS);
        ConfigurationSection labelSection = c.getConfigurationSection("menu.animation-labels");
        if (labelSection != null) for (String clip : labelSection.getKeys(false)) {
            id(clip);
            Object label = labelSection.get(clip);
            if (!(label instanceof String text) || text.isBlank())
                throw new IllegalArgumentException("menu.animation-labels." + clip + " 必须是非空名称");
            labels.put(clip, text);
        }
        animationLabels = Collections.unmodifiableMap(labels);
        Map<String, CustomAction> actions = new LinkedHashMap<>();
        ConfigurationSection section = c.getConfigurationSection("custom-actions");
        if (section != null) for (String name : section.getKeys(false)) {
            id(name);
            ConfigurationSection actionSection = section.getConfigurationSection(name);
            if (actionSection == null)
                throw new IllegalArgumentException("custom-actions." + name + " 必须是动作配置节");
            validateFields(actionSection, ACTION_FIELDS);
            if (!section.getBoolean(name + ".enabled", true)) continue;
            String prefix = name + ".";
            String clip = id(section.getString(prefix + "animation", name));
            LoopMode loop;
            try { loop = LoopMode.valueOf(section.getString(prefix + "loop", "ONCE").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("custom-actions." + name + ".loop 无效"); }
            double speed = section.getDouble(prefix + "speed", 1);
            validateSpeed(speed);
            int maxTicks = section.getInt(prefix + "max-duration-ticks", maxManualTicks);
            if (maxTicks < 1 || maxTicks > maxManualTicks) throw new IllegalArgumentException("自定义动作持续时间超出 manual.max-duration-ticks");
            String key = id(name);
            actions.put(key, new CustomAction(key, section.getString(prefix + "label", animationLabel(clip)), clip, loop,
                    speed, section.getString(prefix + "permission", "mact.use"), maxTicks));
        }
        customActions = Collections.unmodifiableMap(actions);
    }

    public static Settings load(FileConfiguration c) { return new Settings(c); }
    public String animationLabel(String clip) { return animationLabels.getOrDefault(clip, "自定义动作"); }
    public String actionLabel(String action) {
        return customActions.containsKey(action) ? customActions.get(action).label() : animationLabel(action);
    }
    private static int configVersionFor(FileConfiguration c) {
        return c.contains("config-version", true) ? c.getInt("config-version") : 1;
    }
    public boolean sync(SyncFeature f) { return synchronization.get(f); }
    public Playback playback(ActionState state) { return playback.get(state); }
    public void validateSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < manualMinSpeed || speed > manualMaxSpeed)
            throw new IllegalArgumentException("动作速度范围：" + manualMinSpeed + " ~ " + manualMaxSpeed);
    }
    public void requireAllowedModel(String model) {
        if (!allowedModels.contains(model)) throw new IllegalArgumentException("模型未列入 models.allowed：" + model);
    }
    public String animation(String model, ActionState state, Collection<String> existing) {
        for (String name : animationCandidates(model, state)) if (existing.contains(name)) return name;
        return null;
    }
    public List<String> animationCandidates(String model, ActionState state) {
        EnumMap<ActionState, List<String>> custom = animationMaps.get(model);
        return custom != null && custom.containsKey(state) ? custom.get(state)
                : animationMaps.get("defaults").getOrDefault(state, List.of());
    }
    private static EnumMap<ActionState, List<String>> parseAnimations(ConfigurationSection section) {
        EnumMap<ActionState, List<String>> result = new EnumMap<>(ActionState.class);
        if (section == null) return result;
        validateFields(section, ANIMATION_FIELDS);
        for (ActionState state : ActionState.values()) {
            String key = state.key();
            if (!section.contains(key)) continue;
            List<String> names = section.isList(key) ? section.getStringList(key)
                    : List.of(Objects.requireNonNull(section.getString(key)));
            result.put(state, names.stream().map(Settings::id).toList());
        }
        return result;
    }
    private static void validateFields(ConfigurationSection section, Set<String> allowed) {
        for (String field : section.getKeys(false)) {
            String path = section.getCurrentPath() + "." + field;
            if (!allowed.contains(field))
                throw new IllegalArgumentException(path + " 是未知配置字段；模型和动作 ID 不支持点号（.）");
            if (section.isConfigurationSection(field))
                throw new IllegalArgumentException(path + " 不允许嵌套配置字段；模型和动作 ID 不支持点号（.）");
        }
    }
    public static String id(String text) {
        if (text == null || !ID.matcher(text).matches())
            throw new IllegalArgumentException("模型和动作 ID 请使用 1–64 位小写英文、数字、_、-，不支持点号（.）");
        return text;
    }
    private static int integer(ConfigurationSection c, String key, int fallback, int min, int max) {
        int value = c.getInt(key, fallback);
        if (value < min || value > max) throw new IllegalArgumentException(key + " 范围：" + min + " ~ " + max);
        return value;
    }
    private static double number(ConfigurationSection c, String key, double fallback, double min, double max) {
        double value = c.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " 范围：" + min + " ~ " + max);
        return value;
    }
}
