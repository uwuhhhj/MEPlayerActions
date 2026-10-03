package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.VanillaYsmAnimations;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.mixin.PersistentProjectileEntityInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractChestBoatEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, owner-authorized local YSM arm/vehicle/projectile mesh extraction. */
public final class YsmComponentRenderer {
    private static final Map<Key, Playback> PLAYERS = new HashMap<>();
    private static List<ComponentFrame> frame = List.of();
    private static Map<Arm, ArmFrame> arms = Map.of();
    private static Set<UUID> replaced = Set.of();
    private static Set<Key> live = new HashSet<>();
    private static Map<Arm, ArmFrame> nextArms = new EnumMap<>(Arm.class);
    private static long submittedComponents, submittedArms;
    private static final AtomicLong emittedComponentVertices = new AtomicLong(), emittedArmVertices = new AtomicLong();
    private static volatile List<Draw> componentDraws = List.of(), armDraws = List.of();
    private static List<Map<String, Object>> info = List.of();

    private YsmComponentRenderer() { }
    private record Key(UUID owner, String instance, UUID entity, String hash) { }
    private static final class Playback {
        final AnimationPlayer player;
        final Map<String, BbModel.Layer> clocks = new LinkedHashMap<>();
        final Map<String, String> handKeys = new HashMap<>();
        Playback(BbModel model) { player = new AnimationPlayer(model); }
        BbModel.Layer layer(String slot, String clip, double tick) {
            BbModel.Layer current = clocks.get(slot);
            if (current == null || !current.animation().equals(clip)) {
                current = new BbModel.Layer(slot, clip, (long) tick, 1, "LOOP", 0, 0);
                clocks.put(slot, current);
            }
            return current;
        }
    }
    private record ComponentFrame(UUID entity, UUID owner, String instance, String kind, String id, String hash,
                                  String nativeType, double x, double y, double z,
                                  float yaw, float pitch, int light, List<BbModel.Vertex> vertices) { }
    private record ArmFrame(UUID owner, String instance, String kind, String hash, List<BbModel.Vertex> vertices, Matrix4f basis) { }
    private record Draw(Map<String, Object> source, AtomicLong emitted) {
        Map<String, Object> diagnostics() {
            Map<String, Object> row = new LinkedHashMap<>(source); row.put("emittedVertices", emitted.get());
            return Map.copyOf(row);
        }
    }

    static void beginExtraction(ClientRuntime runtime, WorldExtractionContext context) {
        live = new HashSet<>(); nextArms = new EnumMap<>(Arm.class);
        armDraws = List.of();
        List<ComponentFrame> next = new ArrayList<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        Set<UUID> hidden = new HashSet<>();
        float delta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        for (Entity entity : context.world().getEntities()) {
            if (MinecraftClient.getInstance().player != null && entity.isInvisibleTo(MinecraftClient.getInstance().player)) continue;
            PlayerEntity owner = owner(entity);
            if (owner == null) continue;
            ClientRuntime.RenderBinding binding = runtime.appearanceBinding(owner.getUuid());
            // showSelf controls the player's body, not a boat or arrow owned by that player.
            if (binding == null) continue;
            String kind = entity instanceof ProjectileEntity ? "projectile" : "vehicle";
            YsmModelProfile.Component component = runtime.modelProfile(owner.getUuid()).components().stream()
                    .filter(value -> value.kind().equals(kind) && matches(value, entity)).findFirst().orElse(null);
            if (component == null) continue;
            try {
                if (!ModelRenderer.prepare(component.hash(), component.model())) continue;
                Key key = new Key(owner.getUuid(), binding.instance(), entity.getUuid(), component.hash());
                live.add(key);
                Playback playback = PLAYERS.computeIfAbsent(key, ignored -> new Playback(component.model()));
                playback.player.configureFrame(expressions -> {
                    runtime.configureComponentExpressionContext(owner.getUuid(), entity, component.hash(), expressions);
                    // Spatial queries read this component's interpolated native entity frame.
                    expressions.query("query.life_time", (entity.age + (double) delta) / 20);
                    expressions.query("query.is_on_ground", entity.isOnGround() ? 1 : 0);
                    expressions.query("query.is_in_water", entity.isTouchingWater() ? 1 : 0);
                    expressions.query("query.is_on_fire", entity.isOnFire() ? 1 : 0);
                    expressions.query("query.has_rider", entity.hasPassengers() ? 1 : 0);
                    if (entity instanceof PersistentProjectileEntity projectile) {
                        var weapon = projectile.getWeaponStack();
                        expressions.stringQuery("ysm.shoot_item_id", weapon == null || weapon.isEmpty() ? ""
                                : Registries.ITEM.getId(weapon.getItem()).toString());
                    }
                });
                List<BbModel.Layer> layers = componentLayers(playback, component.model(), entity, entity.age + delta);
                Map<String, Double> queries = new HashMap<>(runtime.expressionQueries(owner.getUuid()));
                queries.put("query.life_time", (entity.age + (double) delta) / 20);
                List<BbModel.Vertex> vertices = playback.player.sample(entity.age + delta, layers, 0, 0, queries);
                Vec3d pos = entity.getLerpedPos(delta);
                Box bounds = meshBounds(pos, vertices);
                if (vertices.isEmpty() || !context.frustum().isVisible(bounds)) continue;
                Vec3d camera = context.camera().getCameraPos();
                int light = WorldRenderer.getLightmapCoordinates(context.world(), BlockPos.ofFloored(pos));
                next.add(new ComponentFrame(entity.getUuid(), owner.getUuid(), binding.instance(), kind, component.id(), component.hash(),
                        Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                        pos.x - camera.x, pos.y - camera.y, pos.z - camera.z, entity.getLerpedYaw(delta),
                        kind.equals("projectile") ? entity.getLerpedPitch(delta) : 0, light, List.copyOf(vertices)));
                hidden.add(entity.getUuid());
                diagnostics.add(Map.of("entity", entity.getUuid().toString(), "owner", owner.getUuid().toString(),
                        "instance", binding.instance(), "kind", kind, "id", component.id(), "hash", component.hash(),
                        "vertices", vertices.size(), "layers", layers, "nativeType", Registries.ENTITY_TYPE.getId(entity.getType()).toString()));
            } catch (RuntimeException failure) {
                // Component failure leaves the native entity visible and cannot revoke the primary model's lease.
                diagnostics.add(Map.of("entity", entity.getUuid().toString(), "kind", kind, "error", failure.toString(), "nativeFallback", true));
            }
        }
        frame = List.copyOf(next); replaced = Set.copyOf(hidden); info = List.copyOf(diagnostics);
    }

    private static PlayerEntity owner(Entity entity) {
        if (entity instanceof ProjectileEntity projectile) return projectile.getOwner() instanceof PlayerEntity player ? player : null;
        for (Entity passenger : entity.getPassengerList()) if (passenger instanceof PlayerEntity player) return player;
        return null;
    }

    static boolean matches(YsmModelProfile.Component component, Entity entity) {
        String id = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        return matches(component.matches(), id, tag -> {
            Identifier name = Identifier.tryParse(tag);
            return name != null && entity.getType().isIn(TagKey.of(RegistryKeys.ENTITY_TYPE, name));
        }) || legacyBoatMatch(component.matches(), entity instanceof AbstractBoatEntity, entity instanceof AbstractChestBoatEntity);
    }

    /** Only these two source descriptors need the pre-wood-species registry compatibility mapping. */
    static boolean legacyBoatMatch(List<String> rules, boolean boat, boolean chestBoat) {
        return rules.contains("#minecraft:boat") && boat && !chestBoat
                || rules.contains("minecraft:chest_boat") && chestBoat;
    }

    static boolean matches(List<String> rules, String type, Predicate<String> inTag) {
        for (String rule : rules) if (rule.startsWith("#") ? inTag.test(rule.substring(1)) : rule.equals(type)) return true;
        return false;
    }

    private static List<BbModel.Layer> componentLayers(Playback playback, BbModel model, Entity entity, double tick) {
        List<BbModel.Layer> layers = new ArrayList<>();
        String environment = environment(entity instanceof ProjectileEntity, entity.isTouchingWater(), entity.isOnFire(),
                entity.isOnGround(), entity instanceof PersistentProjectileEntity projectile
                        && ((PersistentProjectileEntityInvoker) projectile).meplayeractions$isInGround());
        add(layers, playback, model, "posture", environment, tick);
        if (entity instanceof AbstractBoatEntity) {
            add(layers, playback, model, "vehicle.ride", entity.hasPassengers() ? "has_ride" : "not_ride", tick);
            add(layers, playback, model, "vehicle.move", entity.getVelocity().horizontalLengthSquared() > .0004 ? "forward" : "idle", tick);
        }
        return List.copyOf(layers);
    }
    /** Native vehicles use water/ground/fly; arrow ground means its synced embedded flag, not entity.onGround. */
    static String environment(boolean projectile, boolean water, boolean fire, boolean onGround, boolean embedded) {
        if (water) return "water";
        return projectile ? fire ? "fire" : embedded ? "ground" : "air" : onGround ? "ground" : "fly";
    }
    private static void add(List<BbModel.Layer> layers, Playback playback, BbModel model, String slot, String clip, double tick) {
        if (model.animations().contains(clip)) layers.add(playback.layer(slot, clip, tick));
    }

    static void extractArms(ClientRuntime runtime, ClientRuntime.RenderBinding binding) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !binding.owner().equals(client.player.getUuid()) || !client.options.getPerspective().isFirstPerson()) return;
        YsmModelProfile.Component component = runtime.modelProfile(binding.owner()).components().stream()
                .filter(value -> value.kind().equals("fp_arm")).findFirst().orElseGet(() -> runtime.modelProfile(binding.owner()).components().stream()
                        .filter(value -> value.kind().equals("arm")).findFirst().orElse(null));
        if (component == null || !ModelRenderer.prepare(component.hash(), component.model())) return;
        Key key = new Key(binding.owner(), binding.instance(), binding.owner(), component.hash());
        live.add(key);
        Playback playback = PLAYERS.computeIfAbsent(key, ignored -> new Playback(component.model()));
        List<BbModel.Layer> layers = new ArrayList<>(binding.layers().stream()
                .filter(layer -> !layer.layer().startsWith("player.") && component.model().animations().contains(layer.animation())).toList());
        var state = runtime.vanillaState(binding.owner());
        var decision = VanillaYsmAnimations.select(state, new VanillaYsmAnimations.Catalog(component.model().animations()));
        String family = component.model().controllerFamily();
        boolean manual = layers.stream().anyMatch(layer -> layer.layer().equals("manual"));
        for (var entry : decision.slots().entrySet()) {
            String slot = entry.getKey(); var choice = entry.getValue();
            if (choice.directive() == VanillaYsmAnimations.Directive.STOP || manual && (slot.equals("player.swing") || slot.equals("player.use"))) {
                playback.clocks.remove(slot); playback.handKeys.remove(slot); continue;
            }
            BbModel.Layer previous = playback.clocks.get(slot);
            if (choice.directive() == VanillaYsmAnimations.Directive.PAUSE || choice.directive() == VanillaYsmAnimations.Directive.CONTINUE) {
                if (previous != null) layers.add(componentLayer(family, previous));
                continue;
            }
            long tick = (long) binding.serverTick();
            long started = slot.equals("player.swing") ? tick - state.swingTicks()
                    : slot.equals("player.use") ? tick - Math.max(0, state.useTicks() - 1L)
                    : previous != null && choice.animation().equals(previous.animation()) && choice.eventKey().equals(playback.handKeys.get(slot))
                    ? previous.startedAtTick() : tick;
            var layer = new BbModel.Layer(slot, choice.animation(), started, 1, choice.loop(), slot.equals("player.swing") ? 0 : 2, 2);
            playback.clocks.put(slot, layer); playback.handKeys.put(slot, choice.eventKey()); layers.add(componentLayer(family, layer));
        }
        Map<String, Double> queries = new HashMap<>(runtime.expressionQueries(binding.owner()));
        for (String slot : decision.slots().keySet()) queries.put("ysm.pause." + family + slot.substring(slot.indexOf('.')), decision.pauseSlots().contains(slot) ? 1d : 0d);
        playback.player.configureFrame(expressions -> {
            runtime.configureExpressionContext(binding.owner(), expressions);
            for (String slot : decision.slots().keySet()) expressions.query("ysm.pause." + family + slot.substring(slot.indexOf('.')),
                    decision.pauseSlots().contains(slot) ? 1d : 0d);
        });
        playback.player.sample(binding.serverTick(), layers, binding.headYaw() - binding.bodyYaw(), binding.headPitch(),
                queries, runtime.accessoryState(binding.owner()), runtime.localParameters(binding.owner()));
        Map<String, Matrix4f> basis = component.model().basisBoneTransforms();
        for (Arm arm : Arm.values()) {
            String side = arm == Arm.LEFT ? "Left" : "Right";
            Matrix4f shoulder = basis.get(side + "Arm");
            if (shoulder == null) continue;
            Set<String> bones = new HashSet<>();
            component.model().boneNames().stream().filter(name -> name.startsWith(side)).forEach(bones::add);
            List<BbModel.Vertex> vertices = playback.player.filteredVertices(bones);
            if (vertices.isEmpty()) continue;
            Vector3f pivot = shoulder.getTranslation(new Vector3f());
            Matrix4f transform = armBasis(arm == Arm.LEFT, pivot, binding.scale());
            nextArms.put(arm, new ArmFrame(binding.owner(), binding.instance(), component.kind(), component.hash(), vertices, transform));
        }
    }

    private static BbModel.Layer componentLayer(String family, BbModel.Layer layer) {
        return new BbModel.Layer(family + layer.layer().substring(layer.layer().indexOf('.')), layer.animation(), layer.startedAtTick(),
                layer.speed(), layer.loop(), layer.inTicks(), layer.outTicks());
    }

    /** Convert YSM shoulder-relative positive-Y geometry into the native arm ModelPart basis. */
    static Matrix4f armBasis(boolean left, Vector3f authoredShoulder) {
        return armBasis(left, authoredShoulder, 1);
    }
    static Matrix4f armBasis(boolean left, Vector3f authoredShoulder, float scale) {
        return new Matrix4f().translation(left ? 5 / 16f : -5 / 16f, 2 / 16f, 0)
                .scale(-scale, -scale, scale).translate(-authoredShoulder.x, -authoredShoulder.y, -authoredShoulder.z);
    }

    static void finishExtraction() {
        arms = Map.copyOf(nextArms);
        PLAYERS.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey())) return false;
            entry.getValue().player.reset(); return true;
        });
    }
    public static boolean replaces(UUID entity) { return entity != null && replaced.contains(entity); }
    public static boolean hasArm(Arm arm) { return arms.containsKey(arm); }
    public static boolean renderArm(Arm arm, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        ArmFrame armFrame = arms.get(arm);
        if (armFrame == null || !ModelRenderer.has(armFrame.hash())) return false;
        matrices.push();
        try {
            matrices.multiplyPositionMatrix(armFrame.basis());
            Draw draw = new Draw(Map.of("owner", armFrame.owner().toString(), "instance", armFrame.instance(),
                    "kind", armFrame.kind(), "side", arm.name(), "hash", armFrame.hash(), "vertices", armFrame.vertices().size(),
                    "finalTransform", matrices.peek().getPositionMatrix().get(new float[16])), new AtomicLong());
            boolean submitted = ModelRenderer.submitMesh(armFrame.hash(), armFrame.vertices(), matrices, queue, light, count -> {
                draw.emitted().addAndGet(count); emittedArmVertices.addAndGet(count);
            });
            if (submitted) {
                submittedArms++;
                List<Draw> next = new ArrayList<>(armDraws); next.add(draw); armDraws = List.copyOf(next);
            }
            return submitted;
        } finally { matrices.pop(); }
    }
    static void submit(WorldRenderContext context) {
        List<Draw> draws = new ArrayList<>();
        for (ComponentFrame component : frame) {
            MatrixStack matrices = context.matrices(); matrices.push();
            try {
                matrices.translate(component.x(), component.y(), component.z());
                matrices.multiplyPositionMatrix(componentBasis(component.kind().equals("projectile"), component.yaw(), component.pitch()));
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("entity", component.entity().toString()); source.put("owner", component.owner().toString());
                source.put("instance", component.instance()); source.put("kind", component.kind()); source.put("id", component.id());
                source.put("hash", component.hash()); source.put("nativeType", component.nativeType());
                source.put("vertices", component.vertices().size()); source.put("finalTransform", matrices.peek().getPositionMatrix().get(new float[16]));
                Draw draw = new Draw(Map.copyOf(source), new AtomicLong());
                if (ModelRenderer.submitMesh(component.hash(), component.vertices(), matrices, context.commandQueue(), component.light(), count -> {
                    draw.emitted().addAndGet(count); emittedComponentVertices.addAndGet(count);
                })) { submittedComponents++; draws.add(draw); }
            } finally { matrices.pop(); }
        }
        componentDraws = List.copyOf(draws);
    }
    /** Source projectile geometry faces +X; its native yaw/pitch basis differs from the vehicle's -Z front. */
    static Matrix4f componentBasis(boolean projectile, float yaw, float pitch) {
        return projectile ? new Matrix4f().rotateY((float) Math.toRadians(yaw - 90)).rotateZ((float) Math.toRadians(pitch))
                : new Matrix4f().rotateY((float) Math.toRadians(180 - yaw));
    }
    private static Box meshBounds(Vec3d position, List<BbModel.Vertex> vertices) {
        double radius = .05;
        for (BbModel.Vertex vertex : vertices) radius = Math.max(radius, Math.sqrt(vertex.x() * vertex.x() + vertex.y() * vertex.y() + vertex.z() * vertex.z()));
        return new Box(position.x - radius, position.y - radius, position.z - radius,
                position.x + radius, position.y + radius, position.z + radius);
    }
    static void clear() {
        PLAYERS.values().forEach(value -> value.player.reset()); PLAYERS.clear();
        frame = List.of(); arms = Map.of(); replaced = Set.of(); info = List.of();
        componentDraws = List.of(); armDraws = List.of();
    }
    public static Map<String, Object> diagnostics() {
        return Map.of("models", info, "activeReplacements", replaced.stream().map(UUID::toString).toList(),
                "submittedComponents", submittedComponents, "submittedArms", submittedArms,
                "emittedComponentVertices", emittedComponentVertices.get(), "emittedArmVertices", emittedArmVertices.get(),
                "submittedComponentDraws", componentDraws.stream().map(Draw::diagnostics).toList(),
                "submittedArmDraws", armDraws.stream().map(Draw::diagnostics).toList(),
                "arms", arms.entrySet().stream().map(entry -> Map.of("side", entry.getKey().name(),
                        "hash", entry.getValue().hash(), "vertices", entry.getValue().vertices().size())).toList());
    }
}
