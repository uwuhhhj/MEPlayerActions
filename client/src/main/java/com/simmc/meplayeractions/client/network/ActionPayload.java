package com.simmc.meplayeractions.client.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Paper plugin messages already have a framing length: do not add another VarInt. */
public record ActionPayload(byte[] data) implements CustomPayload {
    public static final int MAX_BYTES = 32_766;
    public static final Id<ActionPayload> ID = new Id<>(Identifier.of("meplayeractions", "main"));
    public static final PacketCodec<RegistryByteBuf, ActionPayload> CODEC = new PacketCodec<>() {
        @Override public ActionPayload decode(RegistryByteBuf buf) {
            int size = buf.readableBytes();
            if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("Invalid MPA payload size");
            byte[] bytes = new byte[size];
            buf.readBytes(bytes);
            return new ActionPayload(bytes);
        }
        @Override public void encode(RegistryByteBuf buf, ActionPayload value) {
            if (value.data.length < 1 || value.data.length > MAX_BYTES)
                throw new IllegalArgumentException("Invalid MPA payload size");
            buf.writeBytes(value.data);
        }
    };
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
