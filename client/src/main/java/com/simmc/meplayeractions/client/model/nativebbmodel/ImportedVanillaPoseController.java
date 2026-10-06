/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * ImportedVanillaPoseController calculations retained; host adapters supply the
 * already captured native frame and existing native input state, without positions.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import com.simmc.meplayeractions.client.VanillaYsmQueries;
import com.simmc.meplayeractions.client.YsmNativeInputState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

public final class ImportedVanillaPoseController {
    private static final float GENERIC_SWING_X = 1.25f;
    private static final float GENERIC_SWING_Y = 0.35f;
    private static final float GENERIC_SWING_Z = 0.25f;
    private static final float GENERIC_SWING_RECOVERY_X = 0.35f;
    private ImportedVanillaPoseController() { }
    public record ModelData(boolean isSitting, float headPitch, float netHeadYaw) { }
    public record Frame(ModelData modelData, float limbSwing, float limbSwingAmount,
                        float currentTick, float partialTick, VanillaPose vanillaPose) {
        ModelData getModelData() { return modelData; }
        float getLimbSwing() { return limbSwing; }
        float getLimbSwingAmount() { return limbSwingAmount; }
        float getCurrentTick() { return currentTick; }
        float getPartialTick() { return partialTick; }
    }
    public record PartPose(float xRot, float yRot, float zRot) { }
    public record VanillaPose(PartPose head, PartPose body, PartPose leftArm, PartPose rightArm,
                              PartPose leftLeg, PartPose rightLeg) { }
    public static PoseValues sample(PlayerEntity player, Frame frame) {
        return player == null || frame == null ? null : calculatePose(player, frame);
    }
    private static String importedItemType(ItemStack stack) {
        String kind = VanillaYsmQueries.itemState(stack).kind();
        return kind.equals("spear") ? "trident" : kind;
    }
    /** API adapter to the same migrated InputStateKey state used by native YSM queries. */
    private static final class InputState {
        private static MinecraftClient client() { return MinecraftClient.getInstance(); }
        static boolean isLocalPlayerEntity(PlayerEntity player) {
            return client().player != null && player.getUuid().equals(client().player.getUuid());
        }
        static boolean isUsingItem(PlayerEntity player, Hand hand) { return YsmNativeInputState.isUsing(client(), player, hand); }
        static float getTicksUsingItem(PlayerEntity player, float fraction) {
            int ticks = YsmNativeInputState.useTicks(client(), player);
            return player.isUsingItem() ? ticks : ticks == 0 ? 0 : ticks + fraction;
        }
        static boolean isSwinging(PlayerEntity player, Hand hand) { return YsmNativeInputState.isSwinging(client(), player, hand); }
        static Hand getSwingingHand(PlayerEntity player) { return YsmNativeInputState.swingingHand(client(), player); }
        static float getAttackProgress(PlayerEntity player, float fraction) { return YsmNativeInputState.attackProgress(client(), player, fraction); }
        static float getSwingTicks(PlayerEntity player, float fraction) { return YsmNativeInputState.swingTicks(client(), player, fraction); }
    }
    private static PoseValues calculatePose(PlayerEntity player, Frame event) {
        VanillaPose vanillaPose = event.vanillaPose();
        if (vanillaPose != null && !shouldUseFallbackHandPose(player)) {
            return fromVanillaPose(vanillaPose);
        }
        PoseValues pose = new PoseValues();
        pose.name = "idle";
        pose.headX = (float) Math.toRadians(event.getModelData().headPitch);
        pose.headY = (float) Math.toRadians(event.getModelData().netHeadYaw);

        float limbSwing = event.getLimbSwing();
        float limbSwingAmount = MathHelper.clamp(event.getLimbSwingAmount(), 0.0f, 1.0f);
        boolean moving = limbSwingAmount > 0.05f;

        if (player.isSwimming()) {
            pose.name = "swim";
            pose.bodyX = (float) Math.toRadians(-10.0f);
            pose.leftArmX = MathHelper.cos(limbSwing * 0.333f + (float) Math.PI) * 1.1f * limbSwingAmount;
            pose.rightArmX = MathHelper.cos(limbSwing * 0.333f) * 1.1f * limbSwingAmount;
            pose.leftLegX = MathHelper.cos(limbSwing * 0.333f) * 0.9f * limbSwingAmount;
            pose.rightLegX = MathHelper.cos(limbSwing * 0.333f + (float) Math.PI) * 0.9f * limbSwingAmount;
            applyHeldItemPose(player, event, pose);
            return pose;
        }
        if (event.getModelData().isSitting || player.hasVehicle()) {
            pose.name = "sit";
            pose.leftArmX = -0.62831855f;
            pose.rightArmX = -0.62831855f;
            pose.leftLegX = -1.4137167f;
            pose.leftLegY = 0.31415927f;
            pose.leftLegZ = 0.07853982f;
            pose.rightLegX = -1.4137167f;
            pose.rightLegY = -0.31415927f;
            pose.rightLegZ = -0.07853982f;
            applyHeldItemPose(player, event, pose);
            return pose;
        }
        if (player.isGliding() || player.getPose() == EntityPose.GLIDING) {
            pose.name = "elytra_fly";
            pose.leftArmX = 0.15f;
            pose.rightArmX = 0.15f;
            pose.leftArmZ = -0.25f;
            pose.rightArmZ = 0.25f;
            applyVanillaLegPose(pose, vanillaPose, limbSwing, limbSwingAmount);
            applyHeldItemPose(player, event, pose);
            return pose;
        }

        pose.leftArmX = MathHelper.cos(limbSwing * 0.6662f + (float) Math.PI) * 2.0f * limbSwingAmount * 0.5f;
        pose.rightArmX = MathHelper.cos(limbSwing * 0.6662f) * 2.0f * limbSwingAmount * 0.5f;
        pose.leftLegX = MathHelper.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount;
        pose.rightLegX = MathHelper.cos(limbSwing * 0.6662f + (float) Math.PI) * 1.4f * limbSwingAmount;

        if (player.getPose() == EntityPose.CROUCHING) {
            pose.name = moving ? "sneak" : "sneaking";
            pose.bodyX = 0.5f;
            pose.leftArmX += 0.4f;
            pose.rightArmX += 0.4f;
        } else if (player.getAbilities().flying) {
            pose.name = "fly";
            pose.leftArmX = 0.0f;
            pose.rightArmX = 0.0f;
            pose.leftLegX = 0.0f;
            pose.rightLegX = 0.0f;
        } else if (!player.isOnGround() && !player.isTouchingWater()) {
            pose.name = "jump";
        } else if (player.isSprinting() && moving) {
            pose.name = "run";
            pose.leftArmX *= 1.25f;
            pose.rightArmX *= 1.25f;
            pose.leftLegX *= 1.15f;
            pose.rightLegX *= 1.15f;
        } else if (moving) {
            pose.name = "walk";
        }

        float idleArmSway = MathHelper.sin(event.getCurrentTick() * 0.067f) * 0.05f;
        pose.leftArmZ += -0.05f + idleArmSway;
        pose.rightArmZ += 0.05f - idleArmSway;
        pose.leftArmX += MathHelper.sin(event.getCurrentTick() * 0.067f) * 0.05f;
        pose.rightArmX -= MathHelper.sin(event.getCurrentTick() * 0.067f) * 0.05f;
        applyHeldItemPose(player, event, pose);
        return pose;
    }

    private static boolean shouldUseFallbackHandPose(PlayerEntity player) {
        if (player.isUsingItem()
                || InputState.isUsingItem(player, Hand.MAIN_HAND)
                || InputState.isUsingItem(player, Hand.OFF_HAND)
                || InputState.isSwinging(player, Hand.MAIN_HAND)
                || InputState.isSwinging(player, Hand.OFF_HAND)) {
            return true;
        }
        return !player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty();
    }

    private static PoseValues fromVanillaPose(VanillaPose vanillaPose) {
        PoseValues pose = new PoseValues();
        pose.name = "vanilla";
        pose.headX = vanillaPose.head.xRot;
        pose.headY = vanillaPose.head.yRot;
        pose.headZ = vanillaPose.head.zRot;
        pose.bodyX = vanillaPose.body.xRot;
        pose.bodyY = vanillaPose.body.yRot;
        pose.bodyZ = vanillaPose.body.zRot;
        pose.leftArmX = vanillaPose.leftArm.xRot;
        pose.leftArmY = vanillaPose.leftArm.yRot;
        pose.leftArmZ = vanillaPose.leftArm.zRot;
        pose.rightArmX = vanillaPose.rightArm.xRot;
        pose.rightArmY = vanillaPose.rightArm.yRot;
        pose.rightArmZ = vanillaPose.rightArm.zRot;
        pose.leftLegX = vanillaPose.leftLeg.xRot;
        pose.leftLegY = vanillaPose.leftLeg.yRot;
        pose.leftLegZ = vanillaPose.leftLeg.zRot;
        pose.rightLegX = vanillaPose.rightLeg.xRot;
        pose.rightLegY = vanillaPose.rightLeg.yRot;
        pose.rightLegZ = vanillaPose.rightLeg.zRot;
        return pose;
    }

    /**
     * 鞘翅飞行时腿部遵循原版机制：优先直接采用原版 {@code PlayerModel.setupAnim} 计算出的腿部旋转
     * （与空手路径完全同源，基于行走速度 {@code limbSwingAmount} 摆动，站定时归零）；
     * 仅当原版采样不可用（反射失败）时，退回到原版同款的行走摆动公式。
     * 这样无论手上是否持物（如烟花），腿部表现都与空手时一致，而不是被写死为固定角度。
     */
    private static void applyVanillaLegPose(PoseValues pose, VanillaPose vanillaPose,
                                            float limbSwing, float limbSwingAmount) {
        if (vanillaPose != null) {
            pose.leftLegX = vanillaPose.leftLeg.xRot;
            pose.leftLegY = vanillaPose.leftLeg.yRot;
            pose.leftLegZ = vanillaPose.leftLeg.zRot;
            pose.rightLegX = vanillaPose.rightLeg.xRot;
            pose.rightLegY = vanillaPose.rightLeg.yRot;
            pose.rightLegZ = vanillaPose.rightLeg.zRot;
            return;
        }
        pose.leftLegX = MathHelper.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount;
        pose.rightLegX = MathHelper.cos(limbSwing * 0.6662f + (float) Math.PI) * 1.4f * limbSwingAmount;
    }

    private static void applyHeldItemPose(PlayerEntity player, Frame event, PoseValues pose) {
        applyPassiveHoldPose(player, pose, Hand.MAIN_HAND);
        applyPassiveHoldPose(player, pose, Hand.OFF_HAND);
        Hand useHand = getActiveUseHand(player);
        if (useHand != null) {
            applyUsePose(player, event, pose, useHand);
            return;
        }
        applySwingPose(player, event, pose);
    }

    @Nullable
    private static Hand getActiveUseHand(PlayerEntity player) {
        if (player.isUsingItem()) {
            return player.getActiveHand();
        }
        if (InputState.isUsingItem(player, Hand.OFF_HAND)) {
            return Hand.OFF_HAND;
        }
        if (shouldIgnoreSyntheticMainHandUse(player)) {
            return null;
        }
        if (InputState.isUsingItem(player, Hand.MAIN_HAND)) {
            return Hand.MAIN_HAND;
        }
        return null;
    }

    private static boolean shouldIgnoreSyntheticMainHandUse(PlayerEntity player) {
        if (!InputState.isLocalPlayerEntity(player) || !InputState.isUsingItem(player, Hand.MAIN_HAND)) {
            return false;
        }
        ItemStack offhand = player.getOffHandStack();
        if (offhand.isEmpty()) {
            return false;
        }
        return isToolOrWeapon(importedItemType(player.getMainHandStack()));
    }

    private static void applyPassiveHoldPose(PlayerEntity player, PoseValues pose, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            return;
        }
        String itemType = importedItemType(stack);
        Arm arm = armForHand(player, hand);
        if ("bow".equals(itemType)) {
            setArm(pose, arm, -0.2f, 0.0f, 0.0f);
            return;
        }
        if ("crossbow".equals(itemType) && stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
            setArm(pose, arm, -0.85f, arm == Arm.RIGHT ? -0.25f : 0.25f, arm == Arm.RIGHT ? 0.1f : -0.1f);
            return;
        }
        if ("shield".equals(itemType)) {
            setArm(pose, arm, -0.65f, arm == Arm.RIGHT ? -0.25f : 0.25f, 0.0f);
            return;
        }
        if (isToolOrWeapon(itemType)) {
            addArm(pose, arm, -0.25f, 0.0f, arm == Arm.RIGHT ? 0.08f : -0.08f);
        }
    }

    private static void applyUsePose(PlayerEntity player, Frame event, PoseValues pose, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            return;
        }
        String itemType = importedItemType(stack);
        Arm arm = armForHand(player, hand);
        Arm other = arm.getOpposite();
        if ("bow".equals(itemType)) {
            pose.name = "use_bow";
            setArm(pose, arm, 1.5708f + pose.headX, pose.headY + (arm == Arm.RIGHT ? -0.1f : 0.1f), arm == Arm.RIGHT ? 0.1f : -0.1f);
            setArm(pose, other, 1.4708f + pose.headX, pose.headY + (other == Arm.RIGHT ? -0.4f : 0.4f), other == Arm.RIGHT ? 0.25f : -0.25f);
            return;
        }
        if ("crossbow".equals(itemType)) {
            pose.name = "use_crossbow";
            float pull = MathHelper.clamp(InputState.getTicksUsingItem(player, event.getPartialTick()) / 20.0f, 0.0f, 1.0f);
            setArm(pose, arm, 1.2f + pose.headX, pose.headY + (arm == Arm.RIGHT ? -0.35f : 0.35f), arm == Arm.RIGHT ? 0.15f : -0.15f);
            setArm(pose, other, 0.95f + pose.headX, pose.headY + (other == Arm.RIGHT ? -0.65f : 0.65f) * pull, other == Arm.RIGHT ? 0.25f : -0.25f);
            return;
        }
        if ("shield".equals(itemType)) {
            pose.name = "block";
            setArm(pose, arm, 1.15f, arm == Arm.RIGHT ? -0.35f : 0.35f, 0.0f);
            return;
        }
        if ("trident".equals(itemType)) {
            pose.name = "use_trident";
            setArm(pose, arm, 3.1416f + pose.headX, pose.headY + (arm == Arm.RIGHT ? -0.1f : 0.1f), 0.0f);
            addForeArm(pose, arm, 0.1f, 0.0f, 0.0f);
            return;
        }
        if ("lance".equals(itemType)) {
            pose.name = "use_lance";
            setArm(pose, arm, 2.35f, arm == Arm.RIGHT ? -0.35f : 0.35f, arm == Arm.RIGHT ? 0.05f : -0.05f);
            addForeArm(pose, arm, 0.15f, 0.0f, 0.0f);
            return;
        }
        if (isToolOrWeapon(itemType)) {
            pose.name = "use_item";
            applyGenericUsePose(player, event, pose, arm);
            return;
        }
        pose.name = "use_item";
        applyGenericUsePose(player, event, pose, arm);
    }

    private static void applyGenericUsePose(PlayerEntity player, Frame event, PoseValues pose, Arm arm) {
        float ticks = InputState.getTicksUsingItem(player, event.getPartialTick());
        float progress = MathHelper.clamp(ticks / 10.0f, 0.0f, 1.0f);
        float reach = player.isUsingItem() ? 1.0f : MathHelper.sin(progress * (float) Math.PI);
        applyGenericSwingPose(pose, arm, reach, MathHelper.sin(progress * (float) Math.PI));
    }

    private static void applySwingPose(PlayerEntity player, Frame event, PoseValues pose) {
        Hand hand = getActiveSwingingHand(player);
        if (hand == null) {
            return;
        }
        float attack = getSwingProgress(player, hand, event.getPartialTick());
        if (attack <= 0.0f) {
            return;
        }
        Arm arm = armForHand(player, hand);
        float swing = MathHelper.sin(MathHelper.sqrt(attack) * (float) Math.PI);
        float recovery = MathHelper.sin(attack * (float) Math.PI);
        pose.name = "swing";
        applyGenericSwingPose(pose, arm, swing, recovery);
    }

    private static void applyGenericSwingPose(PoseValues pose, Arm arm, float swing, float recovery) {
        addArm(pose, arm,
                GENERIC_SWING_X * swing + GENERIC_SWING_RECOVERY_X * recovery,
                arm == Arm.RIGHT ? GENERIC_SWING_Y * swing : -GENERIC_SWING_Y * swing,
                arm == Arm.RIGHT ? GENERIC_SWING_Z * swing : -GENERIC_SWING_Z * swing);
    }

    @Nullable
    private static Hand getActiveSwingingHand(PlayerEntity player) {
        Hand hand = InputState.getSwingingHand(player);
        if (InputState.isSwinging(player, hand)) {
            return hand;
        }
        if (InputState.isSwinging(player, Hand.OFF_HAND)) {
            return Hand.OFF_HAND;
        }
        if (InputState.isSwinging(player, Hand.MAIN_HAND)) {
            return Hand.MAIN_HAND;
        }
        return null;
    }

    private static float getSwingProgress(PlayerEntity player, Hand hand, float partialTick) {
        if (InputState.isLocalPlayerEntity(player) && hand == Hand.MAIN_HAND) {
            return player.getHandSwingProgress(partialTick);
        }
        float attack = InputState.getAttackProgress(player, partialTick);
        if (attack > 0.0f && (InputState.getSwingingHand(player) == hand
                || (!InputState.isLocalPlayerEntity(player) && hand == Hand.MAIN_HAND))) {
            return attack;
        }
        if (InputState.isSwinging(player, hand)) {
            return MathHelper.clamp(InputState.getSwingTicks(player, partialTick) / 6.0f, 0.0f, 1.0f);
        }
        return 0.0f;
    }

    private static boolean isToolOrWeapon(String itemType) {
        return "sword".equals(itemType)
                || "axe".equals(itemType)
                || "pickaxe".equals(itemType)
                || "shovel".equals(itemType)
                || "hoe".equals(itemType)
                || "trident".equals(itemType)
                || "lance".equals(itemType)
                || "mace".equals(itemType);
    }

    private static Arm armForHand(PlayerEntity player, Hand hand) {
        return hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
    }

    private static void setArm(PoseValues pose, Arm arm, float x, float y, float z) {
        if (arm == Arm.LEFT) {
            pose.leftArmX = x;
            pose.leftArmY = y;
            pose.leftArmZ = z;
        } else {
            pose.rightArmX = x;
            pose.rightArmY = y;
            pose.rightArmZ = z;
        }
    }

    private static void addArm(PoseValues pose, Arm arm, float x, float y, float z) {
        if (arm == Arm.LEFT) {
            pose.leftArmX += x;
            pose.leftArmY += y;
            pose.leftArmZ += z;
        } else {
            pose.rightArmX += x;
            pose.rightArmY += y;
            pose.rightArmZ += z;
        }
    }

    private static void addForeArm(PoseValues pose, Arm arm, float x, float y, float z) {
        if (arm == Arm.LEFT) {
            pose.leftForeArmX += x;
            pose.leftForeArmY += y;
            pose.leftForeArmZ += z;
        } else {
            pose.rightForeArmX += x;
            pose.rightForeArmY += y;
            pose.rightForeArmZ += z;
        }
    }

    private static void addBodyYaw(PoseValues pose, float y) {
        pose.bodyY += y;
    }

    public static final class PoseValues {
        public String name;
        public float headX;
        public float headY;
        public float headZ;
        public float bodyX;
        public float bodyY;
        public float bodyZ;
        public float leftArmX;
        public float leftArmY;
        public float leftArmZ;
        public float leftForeArmX;
        public float leftForeArmY;
        public float leftForeArmZ;
        public float leftHandX;
        public float leftHandY;
        public float leftHandZ;
        public float rightArmX;
        public float rightArmY;
        public float rightArmZ;
        public float rightForeArmX;
        public float rightForeArmY;
        public float rightForeArmZ;
        public float rightHandX;
        public float rightHandY;
        public float rightHandZ;
        public float leftLegX;
        public float leftLegY;
        public float leftLegZ;
        public float leftLowerLegX;
        public float leftLowerLegY;
        public float leftLowerLegZ;
        public float leftFootX;
        public float leftFootY;
        public float leftFootZ;
        public float rightLegX;
        public float rightLegY;
        public float rightLegZ;
        public float rightLowerLegX;
        public float rightLowerLegY;
        public float rightLowerLegZ;
        public float rightFootX;
        public float rightFootY;
        public float rightFootZ;
    }
}
