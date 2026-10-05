package com.simmc.meplayeractions.client.model.nativeysm;

import com.simmc.meplayeractions.client.model.LocalModelBudget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded little-endian reader for the imported Sparkle public binary-model decoder. */
public final class YSMByteBuf implements AutoCloseable {
    private final ByteBuffer bytes;
    private int operations;
    public YSMByteBuf(byte[] input) {
        if (input.length == 0 || input.length > LocalModelBudget.MAX_BYTES) throw new IllegalArgumentException("YSM local binary byte limit (64 MiB)");
        bytes = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
    }
    private void charge(int length) {
        if (++operations > 2_000_000 || length < 0 || length > bytes.remaining())
            throw new IllegalArgumentException("Truncated or excessive YSM binary data");
    }
    public int getOffset() { return bytes.position(); }
    public byte readByte() { charge(1); return bytes.get(); }
    public float readFloat() {
        charge(4); float value = bytes.getFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite YSM binary value");
        return value;
    }
    public float readAnimationLength() {
        charge(4); float value = bytes.getFloat();
        if (Float.isNaN(value) || value < 0) throw new IllegalArgumentException("Invalid YSM animation length");
        return value;
    }
    public long readDword() { charge(4); return Integer.toUnsignedLong(bytes.getInt()); }
    public int readVarInt() {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int next = readByte() & 255;
            if (i == 4 && (next & 0xF0) != 0) throw new IllegalArgumentException("YSM VarInt overflow");
            value |= (next & 127) << (i * 7);
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException("YSM VarInt too long");
    }
    public int readCount(int limit) {
        int count = readVarInt();
        if (count < 0 || count > limit || count > bytes.remaining()) throw new IllegalArgumentException("YSM binary count limit");
        return count;
    }
    public long readVarLong() {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            int next = readByte() & 255;
            if (i == 9 && (next & 0xFE) != 0) throw new IllegalArgumentException("YSM VarLong overflow");
            value |= (long) (next & 127) << (i * 7);
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException("YSM VarLong too long");
    }
    public byte[] readByteArray() {
        int length = readVarInt(); charge(length); byte[] result = new byte[length]; bytes.get(result); return result;
    }
    public String readString() {
        byte[] encoded = readByteArray();
        if (encoded.length > 262_144) throw new IllegalArgumentException("YSM binary string limit");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("YSM string must be UTF-8", invalid); }
    }
    public void skipBytes(int length) { charge(length); bytes.position(bytes.position() + length); }
    @Override public void close() { }
}
