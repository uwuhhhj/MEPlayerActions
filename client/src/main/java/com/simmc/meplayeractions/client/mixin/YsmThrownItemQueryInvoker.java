package com.simmc.meplayeractions.client.mixin;

import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThrownItemEntity.class)
public interface YsmThrownItemQueryInvoker {
    @Invoker("getDefaultItem") Item mpa$defaultItem();
}
