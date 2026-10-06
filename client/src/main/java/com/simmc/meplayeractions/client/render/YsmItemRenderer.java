package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.AnimationPlayer;
import com.simmc.meplayeractions.client.mixin.ItemRenderStateAccessor;
import com.simmc.meplayeractions.client.mixin.ItemLayerRenderStateAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.model.special.ShieldModelRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vanilla item models attached to the final sampled YSM hand pose, without a remote mod handshake. */
public final class YsmItemRenderer {
    private static long submittedItems;
    private YsmItemRenderer() { }

    public record Attachment(ItemRenderState state, Matrix4f transform, String hand, String item, String bone) {
        public Attachment { transform = new Matrix4f(transform); }
        @Override public Matrix4f transform() { return new Matrix4f(transform); }
    }

    /** Item resolution happens during world extraction; the later command submission reads no live inventory. */
    public static List<Attachment> extract(PlayerEntity entity, AnimationPlayer player) {
        return extract(entity, player, false);
    }
    public static List<Attachment> extract(PlayerEntity entity, AnimationPlayer player, boolean importedBbmodel) {
        if (entity == null) return List.of();
        List<Attachment> result = new ArrayList<>(2);
        boolean equipmentLocator = importedBbmodel && player.nativeYsm();
        Map<String, Matrix4f> bones = equipmentLocator ? player.equipmentLocatorTransforms() : player.boneTransforms();
        // Sparkle's CustomPlayerItemInHandLayer selects mainArm/offArm and their same-side
        // extra chains; OpenYSM's modern port still hardcodes right-main and crosses extras.
        append(result, entity, player, bones, entity.getMainArm(), entity.getMainHandStack(), "mainhand", equipmentLocator);
        append(result, entity, player, bones, entity.getMainArm().getOpposite(), entity.getOffHandStack(), "offhand", equipmentLocator);
        return List.copyOf(result);
    }

    private static void append(List<Attachment> result, PlayerEntity entity, AnimationPlayer player, Map<String, Matrix4f> bones,
                               Arm arm, ItemStack source, String hand, boolean equipmentLocator) {
        if (source.isEmpty()) return;
        String side = arm == Arm.LEFT ? "Left" : "Right";
        // Native assets follow the upstream geometry mapper's locator chains. A missing locator does not authorize
        // another drawing position; BB-to-native conversion performs its own upstream inference.
        List<String> attachmentBones = equipmentLocator ? equipmentAttachmentBones(bones, side)
                : attachmentBones(bones, side, player.hasBone(side + "HandLocator"), player.nativeYsm());
        for (String bone : attachmentBones) {
            Matrix4f transform = bones.get(bone);
            ItemRenderState state = new ItemRenderState();
            // Native use predicates compare the stack with entity.getActiveItem() by identity.
            // Resolve synchronously from that native stack; the queued render state owns no inventory reference.
            MinecraftClient.getInstance().getItemModelManager().updateForLivingEntity(state, source,
                    arm == Arm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, entity);
            if (!state.isEmpty()) result.add(new Attachment(state, itemTransform(transform), hand,
                    Registries.ITEM.getId(source.getItem()).toString(), bone));
        }
    }

    /** OpenYSM's primary locator plus its seven explicitly authored extra hand locator chains. */
    static List<String> attachmentBones(Map<String, Matrix4f> transforms, String side) {
        return attachmentBones(transforms, side, transforms.containsKey(side + "HandLocator"));
    }
    static List<String> attachmentBones(Map<String, Matrix4f> transforms, String side, boolean locatorDeclared) {
        return attachmentBones(transforms, side, locatorDeclared, true);
    }
    static List<String> attachmentBones(Map<String, Matrix4f> transforms, String side, boolean locatorDeclared, boolean nativeYsm) {
        List<String> selected = new ArrayList<>(8);
        // OpenYSM YSMClientMapper selects only the authored HandLocator. Legacy raw BBModel
        // keeps its existing hand fallback, including the author-hidden locator boundary.
        String primary = side + (nativeYsm || locatorDeclared ? "HandLocator" : "Hand");
        Matrix4f primaryTransform = transforms.get(primary);
        if (primaryTransform != null && usable(primaryTransform)) selected.add(primary);
        for (int i = 2; i <= 8; i++) {
            String name = side + "HandLocator" + i;
            Matrix4f transform = transforms.get(name);
            if (transform != null && usable(transform)) selected.add(name);
        }
        return List.copyOf(selected);
    }

    /** Sparkle's VANILLA_EQUIPMENT direct-anchor branch wins over extras; extras are used only without a primary. */
    static List<String> equipmentAttachmentBones(Map<String, Matrix4f> transforms, String side) {
        String primary = side + "HandLocator";
        if (transforms.containsKey(primary)) {
            Matrix4f transform = transforms.get(primary);
            return transform != null && usable(transform) ? List.of(primary) : List.of();
        }
        List<String> selected = new ArrayList<>(7);
        for (int i = 2; i <= 8; i++) {
            String extra = side + "HandLocator" + i;
            Matrix4f transform = transforms.get(extra);
            if (transform != null && usable(transform)) selected.add(extra);
        }
        return List.copyOf(selected);
    }

    /** Bedrock locator axes to the native third-person item basis, after the model's body transform. */
    static Matrix4f itemTransform(Matrix4f locator) {
        return new Matrix4f(locator).translate(0, -.0625f, -.1f).rotateX((float) (-Math.PI / 2));
    }

    static boolean usable(Matrix4f transform) {
        float[] values = transform.get(new float[16]);
        for (float value : values) if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) return false;
        return Math.abs(transform.determinant3x3()) > 1e-12;
    }

    public static List<Map<String, Object>> submit(List<Attachment> items, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        List<Map<String, Object>> draws = new ArrayList<>(items.size());
        for (Attachment item : items) {
            if (item.state().isEmpty()) continue;
            matrices.push();
            try {
                matrices.multiplyPositionMatrix(item.transform());
                item.state().render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
                submittedItems++;
                Map<String, Object> draw = new LinkedHashMap<>();
                draw.put("hand", item.hand()); draw.put("item", item.item()); draw.put("bone", item.bone());
                draw.put("nonempty", true); draw.put("submission", submittedItems);
                draw.put("locatorTransform", item.transform().get(new float[16]));
                draw.put("finalTransform", matrices.peek().getPositionMatrix().get(new float[16]));
                draw.put("resolvedLayers", resolvedLayers(item.state()));
                draws.add(Map.copyOf(draw));
            } finally { matrices.pop(); }
        }
        return List.copyOf(draws);
    }

    public static long submittedItems() { return submittedItems; }
    private static List<Map<String,Object>> resolvedLayers(ItemRenderState state) {
        ItemRenderStateAccessor resolved = (ItemRenderStateAccessor)(Object)state;
        var layers = resolved.meplayeractions$getLayers();
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i = 0; i < resolved.meplayeractions$getLayerCount() && i < layers.length; i++) {
            ItemLayerRenderStateAccessor layer = (ItemLayerRenderStateAccessor)(Object)layers[i];
            var transform = layer.meplayeractions$getTransform();
            if (transform == null) continue;
            var rotation = transform.rotation(); var translation = transform.translation(); var scale = transform.scale();
            var special = layer.meplayeractions$getSpecialModel();
            result.add(Map.of("layer", i, "displayContext", resolved.meplayeractions$getDisplayContext().asString(),
                    "rotationDegrees", List.of(rotation.x(), rotation.y(), rotation.z()),
                    "translationBlocks", List.of(translation.x(), translation.y(), translation.z()),
                    "scale", List.of(scale.x(), scale.y(), scale.z()),
                    "specialRenderer", special == null ? "" : special.getClass().getName(),
                    "shieldSpecialRenderer", special instanceof ShieldModelRenderer,
                    "source", "Actual native ItemRenderState.LayerRenderState after ItemModelManager resolution"));
        }
        return List.copyOf(result);
    }
    public static List<Map<String, Object>> diagnostics(List<Attachment> items) {
        return items.stream().map(item -> Map.<String, Object>of("hand", item.hand(), "item", item.item(), "bone", item.bone(),
                "nonempty", !item.state().isEmpty(), "transform", item.transform().get(new float[16]),
                "resolvedLayers", resolvedLayers(item.state()))).toList();
    }
}
