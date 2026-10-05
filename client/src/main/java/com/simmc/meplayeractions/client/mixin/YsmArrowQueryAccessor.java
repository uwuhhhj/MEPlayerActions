package com.simmc.meplayeractions.client.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Native fields used by OpenYSM/Sparkle projectile animation queries. */
@Mixin(PersistentProjectileEntity.class)
public interface YsmArrowQueryAccessor {
    @Accessor("inGroundTime") int mpa$inGroundTime();
    @Invoker("isInGround") boolean mpa$isInGround();
}
