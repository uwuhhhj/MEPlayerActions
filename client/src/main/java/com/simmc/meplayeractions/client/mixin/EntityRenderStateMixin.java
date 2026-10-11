package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.ComponentRenderState;
import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;

import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements HiddenPlayerRenderState, ComponentRenderState {
    @Unique
    private boolean meplayeractions$hidden;
    @Unique
    private UUID meplayeractions$entityUuid;
    @Unique
    private float meplayeractions$tickDelta;

    @Override
    public boolean meplayeractions$isHidden() {
        return this.meplayeractions$hidden;
    }

    @Override
    public void meplayeractions$setHidden(boolean hidden) {
        this.meplayeractions$hidden = hidden;
    }

    @Override
    public UUID meplayeractions$entityUuid() {
        return this.meplayeractions$entityUuid;
    }

    @Override
    public void meplayeractions$entityUuid(UUID uuid) {
        this.meplayeractions$entityUuid = uuid;
    }

    @Override
    public float meplayeractions$tickDelta() {
        return this.meplayeractions$tickDelta;
    }

    @Override
    public void meplayeractions$tickDelta(float tickDelta) {
        this.meplayeractions$tickDelta = tickDelta;
    }
}
