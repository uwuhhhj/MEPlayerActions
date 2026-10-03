package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.AnimationPlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.ElytraEntityModel;
import net.minecraft.client.render.entity.model.PlayerCapeModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.util.*;

/** Native armor textures, trims, glint, wings and head items submitted at sampled YSM bones. */
public final class YsmEquipmentRenderer {
    private static EntityRendererFactory.Context rendererContext;
    private static long submittedEquipment;
    private YsmEquipmentRenderer() { }
    public static void rendererContext(EntityRendererFactory.Context context) { rendererContext = context; }

    public record Attachment(Matrix4f transform, String slot, String bone, Model<?> model, EntityRenderState state,
                             ItemStack stack, RegistryKey<EquipmentAsset> asset, EquipmentModel.LayerType layer,
                             Identifier texture, ItemRenderState item) { }

    @SuppressWarnings("unchecked")
    public static List<Attachment> extract(PlayerEntity entity, AnimationPlayer animation) {
        if (entity == null || rendererContext == null) return List.of();
        EntityRenderer<Entity, EntityRenderState> renderer = (EntityRenderer<Entity, EntityRenderState>) (Object)
                MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        // A fresh state prevents mutating the states already extracted for vanilla's command queue.
        EntityRenderState fresh = renderer.createRenderState();
        renderer.updateRenderState(entity, fresh, MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false));
        if (!(fresh instanceof PlayerEntityRenderState state)) return List.of();
        List<Attachment> result = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = entity.getEquippedStack(slot).copy();
            if (stack.isEmpty()) continue;
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (slot == EquipmentSlot.HEAD && (equippable == null || equippable.assetId().isEmpty())) {
                headItem(result, entity, animation, stack); continue;
            }
            if (equippable == null || equippable.slot() != slot || equippable.assetId().isEmpty()) continue;
            if (slot == EquipmentSlot.CHEST && stack.contains(DataComponentTypes.GLIDER)) {
                Optional<Matrix4f> locator = animation.boneTransform("ElytraLocator");
                if (locator.isPresent() && YsmItemRenderer.usable(locator.get())) {
                    var wings = new ElytraEntityModel(rendererContext.getPart(EntityModelLayers.ELYTRA));
                    Identifier texture = state.skinTextures.elytra() != null ? state.skinTextures.elytra().texturePath()
                            : state.skinTextures.cape() != null && state.capeVisible ? state.skinTextures.cape().texturePath() : null;
                    result.add(new Attachment(new Matrix4f(locator.get()).scale(-1, -1, 1).translate(0, 0, .125f),
                            "CHEST_WINGS", "ElytraLocator", wings, state, stack, equippable.assetId().get(),
                            EquipmentModel.LayerType.WINGS, texture, null));
                }
                continue;
            }
            List<String> pieces = switch (slot) {
                case HEAD -> List.of("head");
                case CHEST -> List.of("body", "right_arm", "left_arm");
                case LEGS -> List.of("body", "right_leg", "left_leg");
                case FEET -> List.of("right_leg", "left_leg");
                default -> List.of();
            };
            for (String piece : pieces) {
                String bone = firstBone(animation, boneCandidates(piece));
                if (bone.isEmpty()) continue;
                Matrix4f transform = animation.boneTransform(bone).orElseThrow();
                if (!YsmItemRenderer.usable(transform)) continue;
                ModelPart root = rendererContext.getPart(EntityModelLayers.PLAYER_EQUIPMENT.getModelData(slot));
                if (!root.hasChild(piece)) continue;
                ModelPart part = root.getChild(piece);
                part.setOrigin(0, 0, 0); part.setAngles(0, 0, 0);
                Model<EntityRenderState> model = new Model<>(part, RenderLayers::entityCutoutNoCull) {
                    @Override public void setAngles(EntityRenderState ignored) { /* Final pose comes from the YSM attachment matrix. */ }
                };
                result.add(new Attachment(new Matrix4f(transform).scale(-1, -1, 1), slot.name(), bone,
                        model, state, stack, equippable.assetId().get(), slot == EquipmentSlot.LEGS
                        ? EquipmentModel.LayerType.HUMANOID_LEGGINGS : EquipmentModel.LayerType.HUMANOID, null, null));
            }
        }
        if (state.capeVisible && state.skinTextures.cape() != null && !state.equippedChestStack.contains(DataComponentTypes.GLIDER)) {
            String bone = firstBone(animation, List.of("CapeLocator", "BodyLocator", "UpBody", "Body"));
            if (!bone.isEmpty()) {
                Matrix4f matrix = animation.boneTransform(bone).orElseThrow();
                if (YsmItemRenderer.usable(matrix)) result.add(new Attachment(new Matrix4f(matrix).scale(-1, -1, 1), "CAPE", bone,
                        new PlayerCapeModel(rendererContext.getPart(EntityModelLayers.PLAYER_CAPE)), state, ItemStack.EMPTY,
                        null, null, state.skinTextures.cape().texturePath(), null));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> boneCandidates(String piece) {
        return switch (piece) {
            case "head" -> List.of("HeadLocator", "Head");
            case "body" -> List.of("BodyLocator", "UpBody", "Body", "UpperBody");
            case "right_arm" -> List.of("RightArm");
            case "left_arm" -> List.of("LeftArm");
            case "right_leg" -> List.of("RightLeg");
            case "left_leg" -> List.of("LeftLeg");
            default -> List.of();
        };
    }
    private static String firstBone(AnimationPlayer player, List<String> candidates) {
        for (String name : candidates) if (player.boneTransform(name).isPresent()) return name;
        return "";
    }
    private static void headItem(List<Attachment> result, PlayerEntity entity, AnimationPlayer animation, ItemStack stack) {
        String bone = firstBone(animation, boneCandidates("head"));
        if (bone.isEmpty()) return;
        Matrix4f transform = animation.boneTransform(bone).orElseThrow();
        if (!YsmItemRenderer.usable(transform)) return;
        ItemRenderState item = new ItemRenderState();
        MinecraftClient.getInstance().getItemModelManager().updateForLivingEntity(item, stack, ItemDisplayContext.HEAD, entity);
        if (!item.isEmpty()) result.add(new Attachment(new Matrix4f(transform).scale(.625f).translate(0, .25f, 0),
                "HEAD_ITEM", bone, null, null, stack, null, null, null, item));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void submit(List<Attachment> attachments, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        if (rendererContext == null) return;
        EquipmentRenderer equipment = rendererContext.getEquipmentRenderer();
        for (Attachment attachment : attachments) {
            matrices.push();
            try {
                matrices.multiplyPositionMatrix(attachment.transform());
                if (attachment.item() != null) attachment.item().render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
                else if (attachment.asset() != null) equipment.render(attachment.layer(), attachment.asset(), (Model) attachment.model(),
                        attachment.state(), attachment.stack(), matrices, queue, light, attachment.texture(), 0, 0);
                else queue.submitModel((Model) attachment.model(), attachment.state(), matrices, RenderLayers.entitySolid(attachment.texture()),
                        light, OverlayTexture.DEFAULT_UV, 0, null);
                submittedEquipment++;
            } finally { matrices.pop(); }
        }
    }
    public static long submittedEquipment() { return submittedEquipment; }
    public static List<Map<String, Object>> diagnostics(List<Attachment> attachments) {
        return attachments.stream().map(value -> Map.<String, Object>of("slot", value.slot(), "bone", value.bone(),
                "item", value.stack().toString(), "kind", value.item() != null ? "head-item" : value.asset() != null ? "equipment" : "cape")).toList();
    }
}
