package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Optional;

/** Exact OpenYSM PlayerSkinTextureManager model identities; never guesses from a user filename. */
public final class YsmPlayerSkin {
    private YsmPlayerSkin() { }

    public static Optional<Identifier> resolve(String modelId, PlayerEntity player) {
        String source = BuiltinYsmModels.logicalSourceId(modelId);
        if (source == null) return Optional.empty();
        String fallback = switch (source) {
            case "misc/1_alex" -> "textures/entity/player/slim/alex.png";
            case "misc/2_steve" -> "textures/entity/player/wide/steve.png";
            default -> null;
        };
        if (fallback == null) return Optional.empty();
        if (player instanceof AbstractClientPlayerEntity clientPlayer && clientPlayer.getSkin().body() != null)
            return Optional.of(clientPlayer.getSkin().body().texturePath());
        return Optional.of(Identifier.ofVanilla(fallback));
    }
}
