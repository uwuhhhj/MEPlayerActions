package com.simmc.meplayeractions.client.effects;

import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.util.Identifier;
import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/** Ref-counted compressed OGG bytes. Private identifiers never fall through to an unrelated pack. */
public final class YsmAudioRegistry {
    public static final String NAMESPACE = "meplayeractions_model_audio";
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024, MAX_TOTAL_BYTES = 16 * 1024 * 1024, MAX_ASSETS = 64;
    private static final Map<String, Resource> RESOURCES = new HashMap<>();
    private static final Map<Identifier, Resource> ROUTES = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong STREAM_OPENS = new java.util.concurrent.atomic.AtomicLong(), STREAM_FAILURES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_STREAMS = new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService DECODER = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> { Thread thread = new Thread(runnable, "MPA-model-audio"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private static int totalBytes;
    private YsmAudioRegistry() { }
    private static final class Resource {
        final String hash; final byte[] ogg; int leases;
        Resource(String hash, byte[] ogg) { this.hash = hash; this.ogg = ogg.clone(); }
    }
    public static final class Lease implements AutoCloseable {
        private final Identifier soundId, location;
        private Resource resource;
        private Lease(Identifier soundId, Identifier location, Resource resource) {
            this.soundId = soundId; this.location = location; this.resource = resource;
        }
        public Identifier soundId() { return soundId; }
        @Override public void close() {
            synchronized (RESOURCES) {
                if (resource == null) return;
                ROUTES.remove(location, resource);
                if (--resource.leases == 0 && RESOURCES.remove(resource.hash, resource)) totalBytes -= resource.ogg.length;
                resource = null;
            }
        }
    }
    public static Lease register(byte[] ogg) {
        Objects.requireNonNull(ogg);
        if (ogg.length < 58 || ogg.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Model OGG size");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ogg)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        synchronized (RESOURCES) {
            if (ROUTES.size() >= MAX_ASSETS) throw new IllegalArgumentException("Model OGG route budget");
            Resource resource = RESOURCES.get(hash);
            if (resource == null) {
                validateOgg(ogg);
                if (RESOURCES.size() >= MAX_ASSETS || (long) totalBytes + ogg.length > MAX_TOTAL_BYTES)
                    throw new IllegalArgumentException("Model OGG registry budget");
                resource = new Resource(hash, ogg); RESOURCES.put(hash, resource); totalBytes += ogg.length;
            }
            String route = hash + "/" + UUID.randomUUID();
            Identifier sound = Identifier.of(NAMESPACE, route), location = Identifier.of(NAMESPACE, "sounds/" + route + ".ogg");
            resource.leases++; ROUTES.put(location, resource); return new Lease(sound, location, resource);
        }
    }
    public static boolean isPrivate(Identifier id) { return id != null && id.getNamespace().equals(NAMESPACE); }
    public static CompletableFuture<AudioStream> openStream(Identifier location, boolean repeat) {
        Resource resource;
        synchronized (RESOURCES) {
            resource = ROUTES.get(location);
            if (resource == null) return CompletableFuture.failedFuture(new IOException("Inactive model audio"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                AudioStream decoded = null;
                try {
                    checkRoute(location, resource);
                    decoded = repeat ? new LoopStream(resource.ogg) : new OggAudioStream(new ByteArrayInputStream(resource.ogg));
                    checkRoute(location, resource);
                    AudioStream stream = new RegisteredStream(decoded, location, resource); STREAM_OPENS.incrementAndGet();
                    return stream;
                } catch (IOException | RuntimeException invalid) {
                    if (decoded != null) try { decoded.close(); } catch (IOException ignored) { }
                    STREAM_FAILURES.incrementAndGet(); throw new CompletionException(invalid);
                }
            }, DECODER);
        } catch (RejectedExecutionException busy) { return CompletableFuture.failedFuture(busy); }
    }
    private static void checkRoute(Identifier location, Resource resource) throws IOException {
        synchronized (RESOURCES) { if (ROUTES.get(location) != resource) throw new IOException("Retired model audio"); }
    }
    private static final class RegisteredStream implements AudioStream {
        private final AudioStream delegate;
        private final Identifier location;
        private final Resource resource;
        private boolean closed;
        RegisteredStream(AudioStream delegate, Identifier location, Resource resource) {
            this.delegate = delegate; this.location = location; this.resource = resource; ACTIVE_STREAMS.incrementAndGet();
        }
        @Override public AudioFormat getFormat() { return delegate.getFormat(); }
        @Override public ByteBuffer read(int size) throws IOException {
            if (closed) throw new IOException("Closed model audio");
            try {
                checkRoute(location, resource);
                if (size < 0 || size > 1_048_576) throw new IOException("Model audio read budget");
                return delegate.read(size);
            } catch (IOException failure) { close(); throw failure; }
        }
        @Override public void close() throws IOException {
            if (!closed) { closed = true; try { delegate.close(); } finally { ACTIVE_STREAMS.decrementAndGet(); } }
        }
    }
    /** Restarts the bounded immutable input; unlike a buffering repeat stream it never copies the whole file per player. */
    private static final class LoopStream implements AudioStream {
        private final byte[] bytes;
        private final AudioFormat format;
        private OggAudioStream delegate;
        private boolean closed;
        LoopStream(byte[] bytes) throws IOException {
            this.bytes = bytes; delegate = new OggAudioStream(new ByteArrayInputStream(bytes)); format = delegate.getFormat();
        }
        @Override public AudioFormat getFormat() { return format; }
        @Override public ByteBuffer read(int count) throws IOException {
            if (closed) throw new IOException("Closed model audio");
            if (count < 0 || count > 1_048_576) throw new IOException("Model audio read budget");
            ByteBuffer data = delegate.read(count);
            if (data.hasRemaining() || count == 0) return data;
            delegate.close(); delegate = new OggAudioStream(new ByteArrayInputStream(bytes));
            return delegate.read(count);
        }
        @Override public void close() throws IOException { if (!closed) { closed = true; delegate.close(); } }
    }
    public static Map<String, Number> diagnostics() {
        synchronized (RESOURCES) { return Map.of("assets", RESOURCES.size(), "routes", ROUTES.size(), "compressedBytes", totalBytes,
                "streamsOpened", STREAM_OPENS.get(), "streamsFailed", STREAM_FAILURES.get(), "activeStreams", ACTIVE_STREAMS.get()); }
    }
    /** Validate a single Vorbis logical stream, all page bounds/sequences and OGG CRCs before registering bytes. */
    public static void validateOgg(byte[] bytes) {
        if (bytes.length < 58 || bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Model OGG size");
        int position = 0, sequence = 0, serial = 0; boolean first = true, ended = false;
        while (position < bytes.length) {
            if (ended || bytes.length - position < 27 || bytes[position] != 'O' || bytes[position + 1] != 'g'
                    || bytes[position + 2] != 'g' || bytes[position + 3] != 'S' || bytes[position + 4] != 0)
                throw new IllegalArgumentException("Model OGG page");
            int flags = bytes[position + 5] & 255, segments = bytes[position + 26] & 255;
            if ((flags & ~7) != 0 || bytes.length - position < 27 + segments) throw new IllegalArgumentException("Model OGG header");
            int length = 27 + segments;
            for (int index = 0; index < segments; index++) length += bytes[position + 27 + index] & 255;
            if (length > bytes.length - position) throw new IllegalArgumentException("Model OGG page size");
            int pageSerial = little(bytes, position + 14), pageSequence = little(bytes, position + 18);
            if (first) {
                if ((flags & 2) == 0 || (flags & 1) != 0 || pageSequence != 0 || segments == 0
                        || (bytes[position + 27] & 255) < 30) throw new IllegalArgumentException("Model OGG first page");
                serial = pageSerial; int payload = position + 27 + segments;
                if (bytes[payload] != 1 || !new String(bytes, payload + 1, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("vorbis")
                        || little(bytes, payload + 7) != 0) throw new IllegalArgumentException("Model OGG codec");
                int channels = bytes[payload + 11] & 255, rate = little(bytes, payload + 12);
                int blocks = bytes[payload + 28] & 255, small = blocks & 15, large = blocks >> 4;
                if (channels < 1 || channels > 2 || rate < 8_000 || rate > 192_000 || small < 6 || large < small
                        || large > 13 || (bytes[payload + 29] & 1) == 0) throw new IllegalArgumentException("Model OGG format");
            } else if ((flags & 2) != 0) throw new IllegalArgumentException("Model OGG chained stream");
            if (pageSerial != serial || pageSequence != sequence++) throw new IllegalArgumentException("Model OGG sequence");
            int crc = 0;
            for (int offset = 0; offset < length; offset++) {
                int value = offset >= 22 && offset < 26 ? 0 : bytes[position + offset] & 255;
                crc ^= value << 24;
                for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
            }
            if (crc != little(bytes, position + 22)) throw new IllegalArgumentException("Model OGG CRC");
            ended = (flags & 4) != 0; first = false; position += length;
        }
        if (!ended) throw new IllegalArgumentException("Model OGG truncated stream");
    }
    private static int little(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8 | (bytes[offset + 2] & 255) << 16 | (bytes[offset + 3] & 255) << 24;
    }
}
