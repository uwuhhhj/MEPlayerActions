package com.simmc.meplayeractions.client.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Read the actual named Minecraft classfiles: verify host semantics required by the adapters. */
class NativePresentationHostApiTest {
    private record HostClass(Map<String, List<String>> methods, Map<String, String> fields) { }

    private static HostClass host(String name) throws IOException {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        try (var stream = NativePresentationHostApiTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, "Actual named Minecraft dependency classfile must be available: " + name);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                    fields.put(field, descriptor); return null;
                }
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    List<String> calls = new ArrayList<>(); methods.put(method + descriptor, calls);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
                            calls.add(owner + "." + called + desc);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return new HostClass(methods, fields);
    }

    @Test void nativeLivingGeometryGatesLeaveTheInheritedLabelAndDispatcherEffectsPath() throws IOException {
        HostClass living = host("net/minecraft/client/render/entity/LivingEntityRenderer");
        assertTrue(living.methods().containsKey("getRenderLayer(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/render/RenderLayer;"));
        assertTrue(living.methods().containsKey("shouldRenderFeatures(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;)Z"));
        List<String> render = living.methods().get("render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V");
        assertNotNull(render);
        assertTrue(render.stream().anyMatch(call -> call.startsWith("net/minecraft/client/render/entity/EntityRenderer.render(")),
                "Suppressing geometry/features must still allow the base renderer's native label path");
        HostClass player = host("net/minecraft/client/render/entity/PlayerEntityRenderer");
        assertFalse(player.methods().keySet().stream().anyMatch(method -> method.startsWith("getRenderLayer(")));
        String playerFeatures = "shouldRenderFeatures(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Z";
        List<String> featureBridge = player.methods().get("shouldRenderFeatures(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;)Z");
        assertNotNull(featureBridge);
        assertTrue(featureBridge.contains("net/minecraft/client/render/entity/PlayerEntityRenderer." + playerFeatures),
                "The native bridge enters the specialized player gate, which needs its own hidden-player adapter");
        List<String> specializedFeatures = player.methods().get(playerFeatures);
        assertNotNull(specializedFeatures);
        assertFalse(specializedFeatures.stream().anyMatch(call -> call.startsWith("net/minecraft/client/render/entity/LivingEntityRenderer.shouldRenderFeatures(")),
                "The player override bypasses the base feature gate; preserving the specialized injection is required");
        HostClass manager = host("net/minecraft/client/render/entity/EntityRenderManager");
        List<String> dispatch = manager.methods().get("render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/render/state/CameraRenderState;DDDLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V");
        assertNotNull(dispatch);
        assertTrue(index(dispatch, "EntityRenderer.render(") < index(dispatch, "OrderedRenderCommandQueue.submitFire("));
        assertTrue(index(dispatch, "OrderedRenderCommandQueue.submitFire(") < index(dispatch, "OrderedRenderCommandQueue.submitShadowPieces("));
    }

    @Test void orderedGroupsFinishEveryNativeDrawingCategoryBeforeTheTargetedFlush() throws IOException {
        HostClass dispatcher = host("net/minecraft/client/render/command/RenderDispatcher");
        assertEquals("Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;", dispatcher.fields().get("vertexConsumers"));
        assertEquals("Lnet/minecraft/client/render/OutlineVertexConsumerProvider;", dispatcher.fields().get("outlineVertexConsumers"));
        List<String> render = dispatcher.methods().get("render()V");
        assertNotNull(render);
        int model = index(render, "ModelCommandRenderer.render(");
        int item = index(render, "ItemCommandRenderer.render(");
        int custom = index(render, "CustomCommandRenderer.render(");
        int completed = index(render, "LayeredCustomCommandRenderer.render(");
        assertTrue(model < completed && item < completed && custom < completed,
                "The phase flush target must run after models, held items and YSM custom geometry");
        assertEquals(1, render.stream().filter(call -> call.equals("net/minecraft/client/render/command/LayeredCustomCommandRenderer.render(Lnet/minecraft/client/render/command/BatchingRenderCommandQueue;)V")).count(),
                "The required redirect must have exactly one native target site");
        HostClass queue = host("net/minecraft/client/render/command/OrderedRenderCommandQueue");
        assertTrue(queue.methods().containsKey("getBatchingQueue(I)Lnet/minecraft/client/render/command/RenderCommandQueue;"));
        HostClass itemState = host("net/minecraft/client/render/item/ItemRenderState");
        assertTrue(itemState.methods().containsKey("render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"));
        HostClass equipment = host("net/minecraft/client/render/entity/equipment/EquipmentRenderer");
        assertTrue(equipment.methods().values().stream().anyMatch(calls -> calls.stream().filter(call ->
                call.equals("net/minecraft/client/render/command/OrderedRenderCommandQueue.getBatchingQueue(I)Lnet/minecraft/client/render/command/RenderCommandQueue;")).count() >= 3),
                "Native equipment orders its texture, glint and trim batches; the phase adapter must preserve those indices");
    }

    @Test void importedPoseSamplingUsesTheActualNativePlayerStateAndVanillaPartAngles() throws IOException {
        HostClass playerModel = host("net/minecraft/client/render/entity/model/PlayerEntityModel");
        assertTrue(playerModel.methods().containsKey("setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V"),
                "The source VanillaHumanoidPoseSampler bridge must consume the actual host player state");
        HostClass humanoidModel = host("net/minecraft/client/render/entity/model/BipedEntityModel");
        for (String part : List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg"))
            assertEquals("Lnet/minecraft/client/model/ModelPart;", humanoidModel.fields().get(part));
        HostClass part = host("net/minecraft/client/model/ModelPart");
        for (String angle : List.of("pitch", "yaw", "roll")) assertEquals("F", part.fields().get(angle));
        HostClass layers = host("net/minecraft/client/render/entity/model/EntityModelLayers");
        assertTrue(layers.methods().containsKey("getLayers()Ljava/util/stream/Stream;"));
        HostClass modelLayer = host("net/minecraft/client/render/entity/model/EntityModelLayer");
        assertTrue(modelLayer.methods().containsKey("id()Lnet/minecraft/util/Identifier;"));
        assertTrue(modelLayer.methods().containsKey("name()Ljava/lang/String;"));
        HostClass pose = host("net/minecraft/entity/EntityPose");
        assertEquals("Lnet/minecraft/entity/EntityPose;", pose.fields().get("GLIDING"),
                "Mojmap FALL_FLYING is Yarn GLIDING in this host");
        HostClass entity = host("net/minecraft/entity/Entity");
        assertTrue(entity.methods().containsKey("isTouchingWater()Z"),
                "Mojmap isInWater reads the same vanilla water-contact state through Yarn isTouchingWater");
    }

    private static int index(List<String> calls, String suffix) {
        for (int index = 0; index < calls.size(); index++) if (calls.get(index).contains("/" + suffix)) return index;
        fail("Actual Minecraft bytecode lost required host path: " + suffix);
        return -1;
    }
}
