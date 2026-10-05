package com.simmc.meplayeractions.client.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Optional versioned channel; it has no dependency on a particular server model engine. */
public record PrivateModelPayload(byte[] data) implements CustomPayload {
    public static final Id<PrivateModelPayload> ID = new Id<>(Identifier.of("meplayeractions", "private"));
    public static final PacketCodec<RegistryByteBuf, PrivateModelPayload> CODEC = new PacketCodec<>() {
        @Override public PrivateModelPayload decode(RegistryByteBuf buffer) {
            int size = buffer.readableBytes();
            if (size < 1 || size > ActionPayload.MAX_BYTES) throw new IllegalArgumentException("Private payload size");
            byte[] data = new byte[size]; buffer.readBytes(data); return new PrivateModelPayload(data);
        }
        @Override public void encode(RegistryByteBuf buffer, PrivateModelPayload payload) {
            if (payload.data.length < 1 || payload.data.length > ActionPayload.MAX_BYTES)
                throw new IllegalArgumentException("Private payload size");
            buffer.writeBytes(payload.data);
        }
    };
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
