package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.ClientRuntime;
import com.simmc.meplayeractions.client.VanillaYsmAnimations;
import com.simmc.meplayeractions.client.VanillaYsmQueries;
import com.simmc.meplayeractions.client.AnimationFormatValidator;
import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.model.YsmQueryDiagnostics;
import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.client.mixin.YsmFishingLineRendererInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.render.entity.state.FishingBobberEntityState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractChestBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.DefaultMinecartController;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, owner-authorized local YSM arm/vehicle/projectile mesh extraction. */
public final class YsmComponentRenderer {
    private static final Map<Key, Playback> PLAYERS = new HashMap<>();
    private static List<ComponentFrame> frame = List.of();
    private static List<FishingLine> fishingLines = List.of();
    private static Map<Arm, ArmFrame> arms = Map.of();
    private static Set<UUID> replaced = Set.of();
    private static Map<UUID, PassengerModel> passengerModels = Map.of();
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
        final BbModel model;
        final Map<String, BbModel.Layer> clocks = new LinkedHashMap<>();
        final VanillaYsmAnimations.HandPlayback handPlayback = new VanillaYsmAnimations.HandPlayback();
        Playback(BbModel model) { this.model = model; player = new AnimationPlayer(model); player.enableQueryDiagnostics(); }
        BbModel.Layer layer(String slot, String clip, double tick) {
            BbModel.Layer current = clocks.get(slot);
            if (current == null || !current.animation().equals(clip)) {
                String loop = AnimationFormatValidator.validate(model.animationFormatVersion(), model.animationFromPrimaryAssembly(clip))
                        ? model.animationLoop(clip) : "LOOP";
                current = new BbModel.Layer(slot, clip, (long) tick, 1, loop, 2, 2);
                clocks.put(slot, current);
            }
            return current;
        }
    }
    private record ComponentFrame(UUID entity, UUID owner, String instance, String kind, String id, String hash,
                                  String nativeType, double x, double y, double z,
                                  float yaw, float pitch, int light, List<BbModel.Vertex> vertices) { }
    private record ArmFrame(UUID owner, String instance, String kind, String hash, List<BbModel.Vertex> vertices,
                            Matrix4f basis, Identifier playerSkin) { }
    private record PassengerModel(float yaw, Map<String, Matrix4f> locators) { }
    private record FishingLine(double x, double y, double z, Vec3d handOffset, float width) { }
    private record Draw(Map<String, Object> source, AtomicLong emitted) {
        Map<String, Object> diagnostics() {
            Map<String, Object> row = new LinkedHashMap<>(source); row.put("emittedVertices", emitted.get());
            return Map.copyOf(row);
        }
    }

    public static List<Map<String,Object>> queryDiagnostics(UUID owner) {
        return PLAYERS.entrySet().stream().filter(entry -> entry.getKey().owner().equals(owner))
                .flatMap(entry -> YsmQueryDiagnostics.describe(entry.getValue().player).stream()).toList();
    }

    static void beginExtraction(ClientRuntime runtime, WorldExtractionContext context) {
        live = new HashSet<>(); nextArms = new EnumMap<>(Arm.class);
        armDraws = List.of();
        List<ComponentFrame> next = new ArrayList<>();
        List<FishingLine> lines = new ArrayList<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        Set<UUID> hidden = new HashSet<>();
        Map<UUID, PassengerModel> passengers = new HashMap<>();
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
                playback.player.enableNativeYsm();
                playback.player.configureFrame(expressions -> {
                    runtime.configureComponentExpressionContext(owner.getUuid(), entity, component.hash(), expressions);
                    // Spatial queries read this component's interpolated native entity frame.
                    expressions.query("query.life_time", (entity.age + (double) delta) / 20);
                    expressions.query("query.is_on_ground", entity.isOnGround() ? 1 : 0);
                    expressions.query("query.is_in_water", entity.isTouchingWater() ? 1 : 0);
                    expressions.query("query.is_on_fire", entity.isOnFire() ? 1 : 0);
                    expressions.query("query.has_rider", entity.hasPassengers() ? 1 : 0);
                });
                List<BbModel.Layer> layers = componentLayers(playback, component.model(), entity, entity.age + delta);
                Map<String, Double> queries = new HashMap<>(runtime.expressionQueries(owner.getUuid()));
                queries.put("query.life_time", (entity.age + (double) delta) / 20);
                List<BbModel.Vertex> vertices = playback.player.sample(entity.age + delta, layers, 0, 0, queries);
                float yaw = bodyYaw(entity, delta);
                if (!kind.equals("projectile")) passengers.put(entity.getUuid(),
                        new PassengerModel(yaw, playback.player.boneTransforms()));
                Vec3d pos = entity.getLerpedPos(delta);
                Box bounds = meshBounds(pos, vertices);
                if (vertices.isEmpty() || !context.frustum().isVisible(bounds)) continue;
                Vec3d camera = context.camera().getCameraPos();
                int light = WorldRenderer.getLightmapCoordinates(context.world(), BlockPos.ofFloored(pos));
                next.add(new ComponentFrame(entity.getUuid(), owner.getUuid(), binding.instance(), kind, component.id(), component.hash(),
                        Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                        pos.x - camera.x, pos.y - camera.y, pos.z - camera.z, yaw,
                        kind.equals("projectile") && !(entity instanceof FishingBobberEntity) ? entity.getLerpedPitch(delta) : 0,
                        light, List.copyOf(vertices)));
                if (entity instanceof FishingBobberEntity bobber && bobber.getPlayerOwner() != null) {
                    var renderer = MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(bobber);
                    if (renderer instanceof FishingBobberEntityRenderer nativeRenderer) {
                        FishingBobberEntityState state = nativeRenderer.createRenderState();
                        nativeRenderer.updateRenderState(bobber, state, delta);
                        if (state.pos != null) lines.add(new FishingLine(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z,
                                state.pos, MinecraftClient.getInstance().getWindow().getMinimumLineWidth()));
                    }
                }
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
        fishingLines = List.copyOf(lines);
        passengerModels = Map.copyOf(passengers);
    }

    /** CustomVehicleRenderer uses living body yaw and the original minecart rail tangent. */
    static float bodyYaw(Entity entity, float delta) {
        if (entity instanceof LivingEntity living) {
            float yaw = MathHelper.lerpAngleDegrees(delta, living.lastBodyYaw, living.bodyYaw);
            if (living.getVehicle() instanceof LivingEntity vehicle) {
                float head = MathHelper.lerpAngleDegrees(delta, living.lastHeadYaw, living.headYaw);
                float vehicleYaw = MathHelper.lerpAngleDegrees(delta, vehicle.lastBodyYaw, vehicle.bodyYaw);
                float difference = MathHelper.clamp(MathHelper.wrapDegrees(head - vehicleYaw), -85, 85);
                yaw = head - difference;
                if (difference * difference > 2500) yaw += difference * .2f;
            }
            return yaw;
        }
        float yaw = entity.getLerpedYaw(delta);
        if (entity instanceof AbstractMinecartEntity minecart && minecart.getController() instanceof DefaultMinecartController controller) {
            Vec3d position = entity.getLerpedPos(delta);
            Vec3d center = controller.snapPositionToRail(position.x, position.y, position.z);
            if (center != null) {
                Vec3d front = controller.simulateMovement(position.x, position.y, position.z, .30000001192092896);
                Vec3d back = controller.simulateMovement(position.x, position.y, position.z, -.30000001192092896);
                Vec3d direction = (back == null ? center : back).subtract(front == null ? center : front);
                if (direction.lengthSquared() > 0) yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x));
            }
        }
        return yaw;
    }

    /** Source ModelPreviewRenderer.renderVehicleModel, applied before the rider's own body rotation. */
    static Matrix4f passengerTransform(PlayerEntity rider) {
        if (rider == null || rider.getVehicle() == null) return new Matrix4f();
        Entity vehicle = rider.getVehicle();
        PassengerModel source = passengerModels.get(vehicle.getUuid());
        int index = vehicle.getPassengerList().indexOf(rider);
        if (source == null || index < 0 || index >= 8) return new Matrix4f();
        String locatorName = index == 0 ? "PassengerLocator" : "PassengerLocator" + (index + 1);
        Matrix4f locator = source.locators().get(locatorName);
        if (locator == null || !YsmItemRenderer.usable(locator)) return new Matrix4f();
        double ridingOffset = -(vehicle.getPassengerRidingPos(rider).y - vehicle.getY()) - .5;
        return passengerTransform(source.yaw(), locator, ridingOffset);
    }

    static Matrix4f passengerTransform(float yaw, Matrix4f locator, double ridingOffset) {
        float rotation = (float) Math.toRadians(180 - yaw);
        return new Matrix4f().rotateY(rotation).mul(locator).rotateY(-rotation).translate(0, (float) ridingOffset, 0);
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
        String environment = entity instanceof ProjectileEntity ? VanillaYsmQueries.projectileAnimationState(entity)
                : environment(false, entity.isTouchingWater(), entity.isOnFire(), entity.isOnGround(), false);
        add(layers, playback, model, "posture", environment, tick);
        if (!(entity instanceof ProjectileEntity)) {
            add(layers, playback, model, "vehicle.ride", entity.hasPassengers() ? "has_ride" : "not_ride", tick);
            add(layers, playback, model, "vehicle.move", entity.getVelocity().horizontalLengthSquared() > .0025 ? "forward" : "idle", tick);
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
        playback.player.enableNativeYsm();
        List<BbModel.Layer> layers = new ArrayList<>(binding.layers().stream()
                .filter(layer -> !layer.layer().startsWith("player.") && component.model().animations().contains(layer.animation())).toList());
        var state = runtime.vanillaState(binding.owner());
        var decision = VanillaYsmAnimations.select(state, component.model().animationCatalog());
        String family = component.model().controllerFamily();
        boolean manual = layers.stream().anyMatch(layer -> layer.layer().equals("manual"));
        for (var entry : decision.slots().entrySet()) {
            String slot = entry.getKey(); var choice = entry.getValue();
            playback.handPlayback.observe(slot, choice, state);
            if (choice.directive() == VanillaYsmAnimations.Directive.STOP || manual && (slot.equals("player.swing") || slot.equals("player.use"))) {
                playback.clocks.remove(slot); playback.handPlayback.stop(slot); continue;
            }
            BbModel.Layer previous = playback.clocks.get(slot);
            if (choice.directive() == VanillaYsmAnimations.Directive.PAUSE || choice.directive() == VanillaYsmAnimations.Directive.CONTINUE) {
                if (previous != null) layers.add(componentLayer(family, previous));
                continue;
            }
            long tick = (long) binding.serverTick();
            long started = playback.handPlayback.start(slot, choice, state, tick);
            var layer = new BbModel.Layer(slot, choice.animation(), started, 1, choice.loop(), slot.equals("player.swing") ? 0 : 2, 2);
            playback.clocks.put(slot, layer); layers.add(componentLayer(family, layer));
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
        for (Arm arm : Arm.values()) {
            String side = arm == Arm.LEFT ? "Left" : "Right";
            if (!component.model().hasBone(side + "Arm")) continue;
            Set<String> bones = component.model().authoredArmBones(arm == Arm.LEFT);
            List<BbModel.Vertex> vertices = playback.player.filteredVertices(bones);
            if (vertices.isEmpty()) continue;
            Matrix4f transform = armBasis(arm == Arm.LEFT);
            Identifier playerSkin = YsmPlayerSkin.resolve(runtime.appearanceModelId(binding.owner()), client.player).orElse(null);
            nextArms.put(arm, new ArmFrame(binding.owner(), binding.instance(), component.kind(), component.hash(), vertices, transform, playerSkin));
        }
    }

    private static BbModel.Layer componentLayer(String family, BbModel.Layer layer) {
        return new BbModel.Layer(family + layer.layer().substring(layer.layer().indexOf('.')), layer.animation(), layer.startedAtTick(),
                layer.speed(), layer.loop(), layer.inTicks(), layer.outTicks());
    }

    /**
     * OpenYSM HandItemRenderer's native hand entry: preserve the complete authored arm coordinates.
     * This path has neither shoulder normalization nor the player's BODY/user scale.
     */
    static Matrix4f armBasis(boolean left) {
        return new Matrix4f().translation(left ? .25f : -.25f, 1.8f, 0).scale(-1, -1, 1);
    }

    static void finishExtraction() {
        arms = Map.copyOf(nextArms);
        PLAYERS.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey())) return false;
            entry.getValue().player.dispose(); return true;
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
            boolean submitted = ModelRenderer.submitMesh(armFrame.hash(), armFrame.vertices(), matrices, queue, light, armFrame.playerSkin(), count -> {
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
        for (FishingLine line : fishingLines) {
            MatrixStack matrices = context.matrices(); matrices.push();
            try {
                matrices.translate(line.x(), line.y(), line.z());
                context.commandQueue().submitCustom(matrices, RenderLayers.lines(), (entry, vertices) -> {
                    // Minecraft 1.21.11 uses two vertices per each of its 16 line segments.
                    for (int segment = 0; segment < 16; segment++) {
                        float start = segment / 16f, end = (segment + 1) / 16f;
                        YsmFishingLineRendererInvoker.meplayeractions$renderFishingLine((float) line.handOffset().x,
                                (float) line.handOffset().y, (float) line.handOffset().z, vertices, entry, start, end, line.width());
                        YsmFishingLineRendererInvoker.meplayeractions$renderFishingLine((float) line.handOffset().x,
                                (float) line.handOffset().y, (float) line.handOffset().z, vertices, entry, end, start, line.width());
                    }
                });
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
        PLAYERS.values().forEach(value -> value.player.dispose()); PLAYERS.clear();
        frame = List.of(); fishingLines = List.of(); arms = Map.of(); replaced = Set.of(); passengerModels = Map.of(); info = List.of();
        componentDraws = List.of(); armDraws = List.of();
    }
    public static Map<String, Object> diagnostics() {
        return Map.of("models", info, "activeReplacements", replaced.stream().map(UUID::toString).toList(),
                "submittedComponents", submittedComponents, "submittedArms", submittedArms,
                "emittedComponentVertices", emittedComponentVertices.get(), "emittedArmVertices", emittedArmVertices.get(),
                "submittedComponentDraws", componentDraws.stream().map(Draw::diagnostics).toList(),
                "submittedArmDraws", armDraws.stream().map(Draw::diagnostics).toList(),
                "fishingLines", fishingLines.size(),
                "arms", arms.entrySet().stream().map(entry -> Map.of("side", entry.getKey().name(),
                        "hash", entry.getValue().hash(), "vertices", entry.getValue().vertices().size())).toList());
    }
}
