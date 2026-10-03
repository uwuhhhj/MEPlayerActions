package com.simmc.meplayeractions.client.mixin;

import com.simmc.meplayeractions.client.effects.YsmAudioRegistry;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** Only this module's issued model-audio locations use local immutable bytes. */
@Mixin(SoundLoader.class)
public abstract class SoundLoaderMixin {
    @Inject(method = "loadStreamed(Lnet/minecraft/util/Identifier;Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void meplayeractions$modelAudio(Identifier location, boolean repeat, CallbackInfoReturnable<CompletableFuture<AudioStream>> callback) {
        if (YsmAudioRegistry.isPrivate(location)) callback.setReturnValue(YsmAudioRegistry.openStream(location, repeat));
    }
}
