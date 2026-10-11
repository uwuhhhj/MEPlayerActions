package com.simmc.meplayeractions.client.render;

import com.simmc.meplayeractions.client.model.BuiltinYsmModels;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

/** Exact OpenYSM PlayerSkinTextureManager model identities; never guesses from a user filename. */
public final class YsmPlayerSkin
{
    private YsmPlayerSkin()
    {
    }

    public static Identifier resolve(String modelId, PlayerEntity player)
    {
        String source = BuiltinYsmModels.logicalSourceId(modelId);
        if (source == null)
        {
            return null;
        }
        String fallback = switch (source)
        {
        case "misc/1_alex" -> "textures/entity/player/slim/alex.png";
        case "misc/2_steve" -> "textures/entity/player/wide/steve.png";
        default -> null;
        };
        if (fallback == null)
        {
            return null;
        }
        if (player instanceof AbstractClientPlayerEntity clientPlayer && clientPlayer.getSkin().body() != null)
        {
            return clientPlayer.getSkin().body().texturePath();
        }
        return Identifier.ofVanilla(fallback);
    }
}
