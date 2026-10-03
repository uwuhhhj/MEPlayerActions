package com.simmc.meplayeractions.client.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read the native tracked embedded state, which is independent of Entity.isOnGround(). */
@Mixin(PersistentProjectileEntity.class)
public interface PersistentProjectileEntityInvoker {
    @Invoker("isInGround") boolean meplayeractions$isInGround();
}
