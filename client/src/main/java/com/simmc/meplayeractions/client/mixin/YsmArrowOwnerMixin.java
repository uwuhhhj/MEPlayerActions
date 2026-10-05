package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.YsmArrowFiringItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Port of Sparkle's AbstractArrowEntityMixin: capture once at the real owner assignment. */
@Mixin(PersistentProjectileEntity.class)
public abstract class YsmArrowOwnerMixin implements YsmArrowFiringItem {
    @Unique private String mpa$ownerItem = "";
    @Override public String mpa$firingItemId() { return mpa$ownerItem; }
    @Inject(method="setOwner",at=@At("RETURN"))
    private void mpa$captureFiringItem(Entity owner,CallbackInfo callback) {
        mpa$ownerItem=owner instanceof LivingEntity living?Registries.ITEM.getId(living.getMainHandStack().getItem()).toString():"";
    }
}
