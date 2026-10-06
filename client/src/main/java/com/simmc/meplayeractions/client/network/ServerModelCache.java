package com.simmc.meplayeractions.client.network;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

/** Content-addressed server assets; a cache hit does not authorize a server model binding. */
public final class ServerModelCache {
    public static final int MAX_RAW = 8 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    public static final int MAX_FILES = 512;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FILE = Pattern.compile("[0-9a-f]{64}\\.bbmodel");
    private static final String TEMP_PREFIX = ".mpa-cache-";
    private static final String INDEX_FILE = "server-models.json";
    private static final int MAX_INDEX_BYTES = 128 * 1024;
    public record GalleryEntry(String id, String hash, long cachedAt, boolean identified) { }
    public record Fingerprint(long size, FileTime modified, FileTime created, Object fileKey) { }
    private static final Comparator<Entry> OLDEST = Comparator.comparingLong(Entry::lastUse)
            .thenComparing(entry -> entry.file().getFileName().toString());
    private final Path directory;
    private final long budget;
    private final DirectoryListing listing;

    public ServerModelCache(Path directory) { this(directory, MAX_TOTAL_BYTES); }

    // A smaller budget exercises eviction without creating 128 MiB in every unit test.
    ServerModelCache(Path directory, long budget) {
        this(directory, budget, Files::newDirectoryStream);
    }

    ServerModelCache(Path directory, long budget, DirectoryListing listing) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (budget < 1 || budget > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Cache budget");
        this.budget = budget;
        this.listing = Objects.requireNonNull(listing);
    }

    /** Corruption, unsafe paths and unavailable storage are cache misses, never trusted bytes. */
    public synchronized Optional<byte[]> readValidated(String hash) { return readValidated(hash, true); }

    /** Inspection neither refreshes LRU timestamps nor grants network/binding authority. */
    public synchronized Optional<byte[]> inspectValidated(String hash) { return readValidated(hash, false); }

    private Optional<byte[]> readValidated(String hash, boolean touch) {
        if (!validHash(hash)) return Optional.empty();
        Path file = directory.resolve(hash + ".bbmodel");
        try {
            checkDirectory(false);
            BasicFileAttributes before = regular(file);
            if (before.size() < 1 || before.size() > MAX_RAW) {
                if(touch){deleteOwned(file, before); prune();} return Optional.empty();
            }
            byte[] raw;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                raw = input.readNBytes(MAX_RAW + 1);
            }
            checkDirectory(false);
            BasicFileAttributes after = regular(file);
            if (!sameFile(before, after)) return Optional.empty();
            if (raw.length < 1 || raw.length > MAX_RAW || !hash.equals(AssetTransfer.hash(raw))) {
                if(touch){deleteOwned(file, after); prune();} return Optional.empty();
            }
            // Modification time is the portable, persisted last-use time for this cache.
            if (touch) {
                Files.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setTimes(FileTime.fromMillis(System.currentTimeMillis()), null, null);
                prune();
            }
            return Optional.of(raw);
        } catch (IOException | SecurityException unavailable) {
            return Optional.empty();
        }
    }

    /** Installs complete hash-validated bytes atomically; model parsing remains the caller's job. */
    public synchronized void writeValidated(String hash, byte[] raw) throws IOException {
        try { write(hash, raw); }
        catch (SecurityException unavailable) { throw new IOException("Cache storage unavailable", unavailable); }
    }

    /** Call only after an authorized push was parsed and prepared. Older hash-only files remain anonymous. */
    public synchronized boolean writeValidated(String modelId, String hash, byte[] raw) throws IOException {
        if (!ServerModelCatalogSnapshot.validId(modelId)) throw new IOException("Invalid cached server model ID");
        boolean existed = validHash(hash) && ordinaryAssetExists(hash);
        writeValidated(hash, raw); return rememberModel(modelId, hash) || !existed;
    }

    /** Persist identity for a validated cache hit; this metadata is never command authorization. */
    public synchronized boolean rememberModel(String modelId, String hash) throws IOException {
        if (!ServerModelCatalogSnapshot.validId(modelId) || !validHash(hash)) throw new IOException("Invalid cached server model identity");
        Map<String, GalleryEntry> entries = readIdentities(); GalleryEntry previous = entries.get(modelId);
        if(previous != null && previous.hash().equals(hash) && ordinaryAssetExists(hash))return false;
        if(inspectValidated(hash).isEmpty())throw new IOException("Missing validated server model");
        long cachedAt = System.currentTimeMillis();
        if (previous != null) cachedAt = Math.max(cachedAt, previous.cachedAt() + 1);
        entries.put(modelId, new GalleryEntry(modelId, hash, cachedAt, true));
        List<GalleryEntry> kept = entries.values().stream()
                .filter(entry -> ordinaryAssetExists(entry.hash())).sorted(Comparator.comparingLong(GalleryEntry::cachedAt).reversed()
                        .thenComparing(GalleryEntry::id)).limit(MAX_FILES).toList();
        JsonObject root = new JsonObject(); root.addProperty("version", 1); JsonArray models = new JsonArray();
        for (GalleryEntry entry : kept) {
            JsonObject value = new JsonObject(); value.addProperty("modelId", entry.id());
            value.addProperty("hash", entry.hash()); value.addProperty("cachedAt", entry.cachedAt()); models.add(value);
        }
        root.add("models", models); byte[] raw = root.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_INDEX_BYTES) throw new IOException("Server model cache index size");
        checkDirectory(false); Path target = directory.resolve(INDEX_FILE); checkTarget(target);
        Path temporary = Files.createTempFile(directory, TEMP_PREFIX, ".tmp"); BasicFileAttributes identity = regular(temporary);
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(raw); while (bytes.hasRemaining()) output.write(bytes); output.force(true);
            }
            checkDirectory(false);
            if (!sameFile(identity, regular(temporary))) throw new IOException("Cache index temporary changed");
            checkTarget(target); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { deleteTemporary(temporary, identity); }
        return true;
    }

    public synchronized Optional<Fingerprint> fingerprint(String hash) {
        if(!validHash(hash))return Optional.empty();
        try {
            checkDirectory(false); BasicFileAttributes attributes = regular(directory.resolve(hash + ".bbmodel"));
            if(attributes.size()<1 || attributes.size()>MAX_RAW)return Optional.empty();
            return Optional.of(new Fingerprint(attributes.size(),attributes.lastModifiedTime(),attributes.creationTime(),attributes.fileKey()));
        }catch(IOException | SecurityException unavailable){return Optional.empty();}
    }

    /** Bounded candidates; the gallery additionally validates the complete hash and parses the model off-thread. */
    public synchronized List<GalleryEntry> galleryEntries() {
        try {
            checkDirectory(false); Map<String, GalleryEntry> identities = readIdentities();
            Map<String, List<GalleryEntry>> byHash = new LinkedHashMap<>();
            for (GalleryEntry entry : identities.values()) byHash.computeIfAbsent(entry.hash(), ignored -> new ArrayList<>()).add(entry);
            var newest = new PriorityQueue<Entry>(OLDEST); long now = System.currentTimeMillis();
            try (var files = listing.open(directory)) {
                for (Path file : files) {
                    if (!FILE.matcher(file.getFileName().toString()).matches()) continue;
                    BasicFileAttributes attributes;
                    try { attributes = regular(file); } catch (IOException ignored) { continue; }
                    if (attributes.size() < 1 || attributes.size() > MAX_RAW) continue;
                    newest.add(new Entry(file,attributes,Math.min(now,attributes.lastModifiedTime().toMillis())));
                    if(newest.size()>MAX_FILES)newest.remove();
                }
            }
            var result = new ArrayList<GalleryEntry>(); long bytes = 0;
            for(Entry entry : newest.stream().sorted(OLDEST.reversed()).toList()) {
                if(entry.attributes().size()>budget-bytes)continue; bytes+=entry.attributes().size();
                String hash = entry.file().getFileName().toString().substring(0, 64);
                List<GalleryEntry> known = byHash.get(hash);
                if (known != null) result.addAll(known);
                else result.add(new GalleryEntry("cache:" + hash, hash, entry.lastUse(), false));
            }
            return result.stream().sorted(Comparator.comparingLong(GalleryEntry::cachedAt).reversed().thenComparing(GalleryEntry::id))
                    .limit(MAX_FILES).toList();
        } catch (IOException | SecurityException | DirectoryIteratorException unavailable) { return List.of(); }
    }

    private boolean ordinaryAssetExists(String hash) {
        try { BasicFileAttributes attributes = regular(directory.resolve(hash + ".bbmodel"));
            return attributes.size() > 0 && attributes.size() <= MAX_RAW;
        } catch (IOException | SecurityException absent) { return false; }
    }

    private Map<String, GalleryEntry> readIdentities() {
        var result = new LinkedHashMap<String, GalleryEntry>(); Path file = directory.resolve(INDEX_FILE);
        try {
            checkDirectory(false); BasicFileAttributes before = regular(file);
            if (before.size() < 1 || before.size() > MAX_INDEX_BYTES) return result;
            byte[] raw;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { raw = input.readNBytes(MAX_INDEX_BYTES + 1); }
            checkDirectory(false); if (raw.length > MAX_INDEX_BYTES || !sameFile(before, regular(file))) return result;
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT); reader.beginObject(); boolean version = false, models = false;
                long now = System.currentTimeMillis();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.equals("version") && !version) {
                        if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1")) throw new IOException("Cache index version");
                        version = true;
                    } else if (key.equals("models") && !models) {
                        models = true; reader.beginArray(); int count = 0;
                        while (reader.hasNext()) {
                            if (++count > MAX_FILES) throw new IOException("Cache index count");
                            reader.beginObject(); String id = null, hash = null; Long cachedAt = null;
                            while (reader.hasNext()) {
                                String field = reader.nextName();
                                if (field.equals("modelId") && id == null && reader.peek() == JsonToken.STRING) id = reader.nextString();
                                else if (field.equals("hash") && hash == null && reader.peek() == JsonToken.STRING) hash = reader.nextString();
                                else if (field.equals("cachedAt") && cachedAt == null && reader.peek() == JsonToken.NUMBER) cachedAt = Long.parseLong(reader.nextString());
                                else throw new IOException("Cache index model fields");
                            }
                            reader.endObject();
                            if (!ServerModelCatalogSnapshot.validId(id) || !validHash(hash) || cachedAt == null || cachedAt < 0) continue;
                            GalleryEntry next = new GalleryEntry(id, hash, Math.min(now, cachedAt), true), previous = result.get(id);
                            if (previous == null || next.cachedAt() > previous.cachedAt()) result.put(id, next);
                        }
                        reader.endArray();
                    } else throw new IOException("Cache index fields");
                }
                reader.endObject();
                if (!version || !models || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Cache index envelope");
            }
        } catch (IOException | RuntimeException invalid) { result.clear(); }
        return result;
    }

    private void write(String hash, byte[] raw) throws IOException {
        if (!validHash(hash) || raw == null || raw.length < 1 || raw.length > MAX_RAW || raw.length > budget)
            throw new IOException("Invalid cached model hash or size");
        byte[] content = raw.clone();
        if (!hash.equals(AssetTransfer.hash(content))) throw new IOException("Cached model SHA-256 mismatch");
        checkDirectory(true);
        Path target = directory.resolve(hash + ".bbmodel");
        checkTarget(target);
        prune();
        Path temporary = Files.createTempFile(directory, TEMP_PREFIX, ".tmp");
        BasicFileAttributes temporaryIdentity = regular(temporary);
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            checkDirectory(false);
            if (!sameFile(temporaryIdentity, regular(temporary))) throw new IOException("Cache temporary file changed");
            checkTarget(target);
            // No non-atomic fallback: interrupted writes must not publish a partial model.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            prune();
        } finally {
            deleteTemporary(temporary, temporaryIdentity);
        }
    }

    private static boolean validHash(String hash) { return hash != null && HASH.matcher(hash).matches(); }

    /** Creates one directory at a time, checking existing ancestors before using them. */
    private void checkDirectory(boolean create) throws IOException {
        Path current = directory.getRoot();
        checkOrdinaryDirectory(current);
        for (Path component : directory) {
            current = current.resolve(component);
            if (create && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current); }
                catch (java.nio.file.FileAlreadyExistsException appeared) { /* Validate what appeared. */ }
            }
            checkOrdinaryDirectory(current);
        }
    }

    private static void checkOrdinaryDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Cache and its ancestors must be ordinary directories");
    }

    private static BasicFileAttributes regular(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Cache entries must be ordinary files");
        return attributes;
    }

    private static void checkTarget(Path file) throws IOException {
        try { regular(file); }
        catch (NoSuchFileException absent) { /* A new, hash-named file is allowed. */ }
    }

    private static boolean sameFile(BasicFileAttributes expected, BasicFileAttributes actual) {
        if (expected.fileKey() != null || actual.fileKey() != null)
            return Objects.equals(expected.fileKey(), actual.fileKey());
        return expected.creationTime().equals(actual.creationTime());
    }

    private void deleteOwned(Path file, BasicFileAttributes identity) throws IOException {
        if (!directory.equals(file.getParent()) || !FILE.matcher(file.getFileName().toString()).matches())
            throw new IOException("Not a cache-owned entry");
        checkDirectory(false);
        try {
            if (!sameFile(identity, regular(file))) throw new IOException("Cache entry changed before deletion");
            Files.delete(file);
        } catch (NoSuchFileException absent) { /* Already removed. */ }
    }

    private void deleteTemporary(Path file, BasicFileAttributes identity) {
        // Only remove this call's temporary file; never sweep unknown files or links.
        try {
            checkDirectory(false);
            if (directory.equals(file.getParent()) && file.getFileName().toString().startsWith(TEMP_PREFIX)
                    && sameFile(identity, regular(file))) Files.delete(file);
        } catch (IOException | SecurityException unavailable) { /* Nothing unsafe is removed. */ }
    }

    private void prune() throws IOException {
        while (true) {
            checkDirectory(false);
            long now = System.currentTimeMillis(), total = 0, count = 0;
            // Retain at most 512 globally oldest candidates, not the first 512 encountered.
            var candidates = new PriorityQueue<Entry>(OLDEST.reversed());
            try (var files = listing.open(directory)) {
                for (Path file : files) {
                    if (!FILE.matcher(file.getFileName().toString()).matches()) continue;
                    BasicFileAttributes attributes;
                    try { attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                    catch (NoSuchFileException removed) { continue; }
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) continue;
                    if (attributes.size() < 1 || attributes.size() > MAX_RAW) {
                        deleteOwned(file, attributes); continue;
                    }
                    candidates.add(new Entry(file, attributes, Math.min(now, attributes.lastModifiedTime().toMillis())));
                    if (candidates.size() > MAX_FILES) candidates.remove();
                    total += attributes.size(); count++;
                }
            } catch (DirectoryIteratorException unavailable) { throw unavailable.getCause(); }
            if (total <= budget && count <= MAX_FILES) return;
            for (Entry remove : candidates.stream().sorted(OLDEST).toList()) {
                if (total <= budget && count <= MAX_FILES) return;
                deleteOwned(remove.file(), remove.attributes());
                total -= remove.attributes().size(); count--;
            }
            // Pre-existing directories with more than one batch are rescanned, with bounded memory.
        }
    }

    @FunctionalInterface interface DirectoryListing { DirectoryStream<Path> open(Path directory) throws IOException; }
    private record Entry(Path file, BasicFileAttributes attributes, long lastUse) { }
}
