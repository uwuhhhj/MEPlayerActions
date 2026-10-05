package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.YsmNativeInputState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class YsmMouseInputMixin {
    @Inject(method="onMouseButton",at=@At("HEAD"))
    private void mpa$observeNativeMouse(long window,MouseInput input,int action,CallbackInfo callback) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(window==client.getWindow().getHandle())YsmNativeInputState.mouseButton(client,input.button(),action);
    }
}
