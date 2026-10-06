package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.nativebbmodel.ImportedVanillaPoseController;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

/** Sparkle VanillaHumanoidPoseSampler's pose read, using the actual 1.21.11 native frame. */
public final class NativeHumanoidPoseSampler {
    private static PlayerEntityModel sampler;
    private NativeHumanoidPoseSampler() { }
    static void rendererContext(EntityRendererFactory.Context context) {
        // The source sampler uses the slim vanilla model. Reuse a private pose sampler;
        // never mutate the renderer's own model or rebuild the player's render-state.
        // Yarn currently gives the main and equipment fields the same PLAYER_SLIM
        // name. Read the actual registered main layer instead of the equipment set.
        var mainLayer = EntityModelLayers.getLayers().filter(layer -> layer.id().equals(Identifier.ofVanilla("player_slim"))
                && layer.name().equals("main")).findFirst().orElseThrow();
        sampler = new PlayerEntityModel(context.getPart(mainLayer), true);
    }

    public static ImportedVanillaPoseController.Frame frame(PlayerEntity player) {
        var snapshot = NativePlayerPresentation.frame(player);
        if (snapshot == null) return null;
        var state = snapshot.renderState();
        ImportedVanillaPoseController.VanillaPose pose = null;
        if (sampler != null) {
            sampler.setAngles(state);
            pose = new ImportedVanillaPoseController.VanillaPose(part(sampler.head), part(sampler.body),
                    part(sampler.leftArm), part(sampler.rightArm), part(sampler.leftLeg), part(sampler.rightLeg));
        }
        return new ImportedVanillaPoseController.Frame(new ImportedVanillaPoseController.ModelData(
                state.hasVehicle, snapshot.headPitch(), snapshot.headYaw() - snapshot.bodyYaw()),
                state.limbSwingAnimationProgress, state.limbSwingAmplitude, state.age, snapshot.tickDelta(), pose);
    }
    private static ImportedVanillaPoseController.PartPose part(ModelPart part) {
        return new ImportedVanillaPoseController.PartPose(part.pitch, part.yaw, part.roll);
    }
}
