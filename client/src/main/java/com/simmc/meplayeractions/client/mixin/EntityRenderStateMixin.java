package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.render.HiddenPlayerRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements HiddenPlayerRenderState {
    @Unique private boolean meplayeractions$hidden;

    @Override public boolean meplayeractions$isHidden() { return meplayeractions$hidden; }
    @Override public void meplayeractions$setHidden(boolean hidden) { meplayeractions$hidden = hidden; }
}
