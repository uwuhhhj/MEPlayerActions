package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.AnimationPlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ParrotEntityRenderer;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.ElytraEntityModel;
import net.minecraft.client.render.entity.model.PlayerCapeModel;
import net.minecraft.client.render.entity.model.ParrotEntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.entity.state.ParrotEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.ParrotEntity;
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
    public static void rendererContext(EntityRendererFactory.Context context) {
        rendererContext = context;
        YsmBodyTransform.rendererContext(context);
    }

    public record Attachment(Matrix4f transform, String slot, String bone, Model<?> model, EntityRenderState state,
                             ItemStack stack, RegistryKey<EquipmentAsset> asset, EquipmentModel.LayerType layer,
                             Identifier texture, ItemRenderState item) { }

    public static List<Attachment> extract(PlayerEntity entity, AnimationPlayer animation) {
        if (entity == null || rendererContext == null) return List.of();
        // Read the same immutable-in-use host frame as the body. Re-extracting here would give
        // wings and shoulder companions a different interpolation/frozen-tick state.
        NativePlayerPresentation.Snapshot frame = NativePlayerPresentation.frame(entity);
        if (frame == null) return List.of();
        PlayerEntityRenderState state = frame.renderState();
        List<Attachment> result = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = entity.getEquippedStack(slot).copy();
            if (stack.isEmpty()) continue;
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (slot == EquipmentSlot.HEAD && headItemEligible(animation.nativeYsm(),
                    equippable == null ? null : equippable.slot(), equippable != null && equippable.assetId().isPresent())) {
                headItem(result, entity, animation, stack); continue;
            }
            if (equippable == null || equippable.slot() != slot || equippable.assetId().isEmpty()) continue;
            if (slot == EquipmentSlot.CHEST && stack.contains(DataComponentTypes.GLIDER)) {
                Optional<Matrix4f> locator = animation.boneTransform("ElytraLocator");
                if (locator.isPresent() && YsmItemRenderer.usable(locator.get())) {
                    var wings = new ElytraEntityModel(rendererContext.getPart(EntityModelLayers.ELYTRA));
                    Identifier texture = state.skinTextures.elytra() != null ? state.skinTextures.elytra().texturePath()
                            : state.skinTextures.cape() != null && state.capeVisible ? state.skinTextures.cape().texturePath() : null;
                    result.add(new Attachment(wingTransform(locator.get(), animation.nativeYsm()),
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
                String bone = firstBone(animation, boneCandidates(piece, animation.nativeYsm()));
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
        shoulderParrot(result, animation, state, true);
        shoulderParrot(result, animation, state, false);
        return List.copyOf(result);
    }

    /** OpenYSM CustomPlayerParrotLayer: native shoulder pose follows only the authored shoulder locator. */
    private static void shoulderParrot(List<Attachment> result, AnimationPlayer animation,
                                       PlayerEntityRenderState player, boolean left) {
        ParrotEntity.Variant variant = left ? player.leftShoulderParrotVariant : player.rightShoulderParrotVariant;
        String bone = left ? "LeftShoulderLocator" : "RightShoulderLocator";
        Matrix4f locator = animation.boneTransform(bone).orElse(null);
        if (variant == null || locator == null || !YsmItemRenderer.usable(locator)) return;
        ParrotEntityRenderState state = new ParrotEntityRenderState();
        state.parrotPose = ParrotEntityModel.Pose.ON_SHOULDER;
        state.variant = variant;
        state.age = player.age;
        state.limbSwingAnimationProgress = player.limbSwingAnimationProgress;
        state.limbSwingAmplitude = player.limbSwingAmplitude;
        state.relativeHeadYaw = player.relativeHeadYaw;
        state.pitch = player.pitch;
        Matrix4f transform = new Matrix4f(locator).rotateZ((float) Math.PI)
                .translate(0, player.isInSneakingPose ? -1.3f : -1.5f, 0);
        result.add(new Attachment(transform, left ? "LEFT_PARROT" : "RIGHT_PARROT", bone,
                new ParrotEntityModel(rendererContext.getPart(EntityModelLayers.PARROT)), state, ItemStack.EMPTY,
                null, null, ParrotEntityRenderer.getTexture(variant), null));
    }

    static List<String> boneCandidates(String piece, boolean nativeYsm) {
        return switch (piece) {
            // YSMClientMapper's headIds selects Head; it never selects HeadLocator first.
            case "head" -> nativeYsm ? List.of("Head") : List.of("HeadLocator", "Head");
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
        String bone = firstBone(animation, boneCandidates("head", animation.nativeYsm()));
        if (bone.isEmpty()) return;
        Matrix4f transform = animation.boneTransform(bone).orElseThrow();
        if (!YsmItemRenderer.usable(transform)) return;
        ItemRenderState item = new ItemRenderState();
        MinecraftClient.getInstance().getItemModelManager().updateForLivingEntity(item, stack, ItemDisplayContext.HEAD, entity);
        if (!item.isEmpty()) result.add(new Attachment(headItemTransform(transform),
                "HEAD_ITEM", bone, null, null, stack, null, null, null, item));
    }
    /** OpenYSM 1.21.11 CustomPlayerArmorLayer.isArmorItem checks the equipped slot, not the asset ID. */
    static boolean headItemEligible(boolean nativeYsm, EquipmentSlot equippableSlot, boolean hasEquipmentAsset) {
        return nativeYsm ? equippableSlot != EquipmentSlot.HEAD : equippableSlot == null || !hasEquipmentAsset;
    }
    /** CustomPlayerArmorLayer's native Head item basis, after the complete authored chain. */
    static Matrix4f headItemTransform(Matrix4f locator) {
        return new Matrix4f(locator).scale(.625f).translate(0, .25f, 0);
    }
    /** CustomPlayerElytraLayer rotates the model 180 degrees around Z, without a guessed Z offset. */
    static Matrix4f wingTransform(Matrix4f locator, boolean nativeYsm) {
        return nativeYsm ? new Matrix4f(locator).rotateZ((float) Math.PI)
                : new Matrix4f(locator).scale(-1, -1, 1).translate(0, 0, .125f);
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
                "item", value.stack().toString(), "kind", value.item() != null ? "head-item" : value.asset() != null ? "equipment"
                        : value.slot().endsWith("PARROT") ? "shoulder-parrot" : "cape")).toList();
    }
}
