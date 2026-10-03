package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;
import com.simmc.meplayeractions.client.render.ComponentRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements HiddenPlayerRenderState, ComponentRenderState {
    @Unique private boolean meplayeractions$hidden;
    @Unique private java.util.UUID meplayeractions$entityUuid;

    @Override public boolean meplayeractions$isHidden() { return meplayeractions$hidden; }
    @Override public void meplayeractions$setHidden(boolean hidden) { meplayeractions$hidden = hidden; }
    @Override public java.util.UUID meplayeractions$entityUuid() { return meplayeractions$entityUuid; }
    @Override public void meplayeractions$entityUuid(java.util.UUID uuid) { meplayeractions$entityUuid = uuid; }
}
