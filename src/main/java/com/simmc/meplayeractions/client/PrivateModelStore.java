package com.simmc.meplayeractions.client;

import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded owner-scoped storage for validated private bundles. All calls belong on a worker thread. */
public final class PrivateModelStore {
    private static final Pattern FILE = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}_[a-f0-9]{64}\\.zip");
    private final Path directory;
    private final Settings settings;
    public record Settings(boolean enabled, long maxBytes, int maxModels, int maxModelsPerPlayer) {
        public Settings {
            if(maxBytes<PrivateModelBundle.MAX_BYTES || maxBytes>4L*1024*1024*1024 || maxModels<1 || maxModels>4096
                    || maxModelsPerPlayer<1 || maxModelsPerPlayer>64 || maxModelsPerPlayer>maxModels)
                throw new IllegalArgumentException("Invalid private model cache settings");
        }
        public static Settings defaults(){return new Settings(true,128L*1024*1024,512,4);}
        public static Settings fromConfiguration(ConfigurationSection config) {
            Objects.requireNonNull(config);
            return new Settings(config.getBoolean("client-sync.private-models.cache-enabled",true),
                    config.getLong("client-sync.private-models.max-cache-bytes",128L*1024*1024),
                    config.getInt("client-sync.private-models.max-cache-models",512),
                    config.getInt("client-sync.private-models.max-cache-models-per-player",4));
        }
    }
    public PrivateModelStore(Path directory,Settings settings) {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();this.settings=Objects.requireNonNull(settings);
    }
    public boolean enabled(){return settings.enabled();}
    /** An owner can only reuse bytes that were previously stored under that owner's UUID. */
    synchronized byte[] load(UUID owner,String hash,String kind,int expectedBytes) throws IOException {
        Path path=path(owner,hash);
        if(!enabled() || !Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return null;
        requireDirectory();
        trimToLimits(entries());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=expectedBytes || expectedBytes>PrivateModelBundle.MAX_BYTES)return null;
        byte[] bytes=new byte[expectedBytes];
        try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            if(input.readNBytes(bytes,0,bytes.length)!=bytes.length || input.read()!=-1)return null;
        }
        try {
            if(!PrivateModelBundle.hash(bytes).equals(hash))throw new IOException("cache_hash");
            PrivateModelBundle.validate(bytes,kind);
        }catch(IOException | RuntimeException invalid){Files.deleteIfExists(path);return null;}
        Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()));
        return bytes;
    }
    /** Only the relay's successful whole-bundle validator may call this method. */
    synchronized void saveValidated(UUID owner,String hash,byte[] bytes) throws IOException {
        Path target=path(owner,hash);
        if(!enabled())return;
        if(bytes==null || bytes.length<22 || bytes.length>PrivateModelBundle.MAX_BYTES || !PrivateModelBundle.hash(bytes).equals(hash))
            throw new IOException("cache_identity");
        Files.createDirectories(directory);requireDirectory();
        List<Entry> entries=entries();trimToLimits(entries);
        entries.removeIf(entry->entry.path.equals(target));
        long total=entries.stream().mapToLong(Entry::bytes).sum();
        int owned=(int)entries.stream().filter(entry->entry.owner.equals(owner)).count();
        // Each successful write first bounds the per-owner library, then the shared disk budget.
        for(Iterator<Entry> iterator=entries.iterator();owned>=settings.maxModelsPerPlayer() && iterator.hasNext();) {
            Entry entry=iterator.next();if(!entry.owner.equals(owner))continue;
            Files.delete(entry.path);iterator.remove();total-=entry.bytes;owned--;
        }
        while(!entries.isEmpty() && (entries.size()>=settings.maxModels() || total+bytes.length>settings.maxBytes())) {
            Entry entry=entries.removeFirst();Files.delete(entry.path);total-=entry.bytes;
        }
        if(entries.size()>=settings.maxModels() || total+bytes.length>settings.maxBytes())throw new IOException("cache_capacity");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("cache_file");
        Path temporary=Files.createTempFile(directory,".private-upload-",".tmp");
        try {
            Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    private Path path(UUID owner,String hash) throws IOException {
        if(owner==null || hash==null || !hash.matches("[a-f0-9]{64}"))throw new IOException("cache_identity");
        Path path=directory.resolve(owner+"_"+hash+".zip").normalize();
        if(!Objects.equals(path.getParent(),directory))throw new IOException("cache_path");return path;
    }
    private void requireDirectory() throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))throw new IOException("cache_directory");
    }
    private List<Entry> entries() throws IOException {
        List<Entry> entries=new ArrayList<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(directory)) {
            int scanned=0;
            for(Path path:stream) {
                // A manually polluted directory must not turn a client upload into unbounded I/O.
                if(++scanned>8192)throw new IOException("cache_directory_limit");
                String name=path.getFileName().toString();
                if(!FILE.matcher(name).matches() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                entries.add(new Entry(path,UUID.fromString(name.substring(0,36)),Files.size(path),Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::accessed).thenComparing(entry->entry.path.toString()));return entries;
    }
    private void trimToLimits(List<Entry> entries) throws IOException {
        Map<UUID,Integer> owned=new HashMap<>();for(Entry entry:entries)owned.merge(entry.owner,1,Integer::sum);
        long total=entries.stream().mapToLong(Entry::bytes).sum();
        for(Iterator<Entry> iterator=entries.iterator();iterator.hasNext();) {
            Entry entry=iterator.next();
            if(owned.get(entry.owner)<=settings.maxModelsPerPlayer())continue;
            Files.delete(entry.path);iterator.remove();total-=entry.bytes;owned.merge(entry.owner,-1,Integer::sum);
        }
        while(!entries.isEmpty() && (entries.size()>settings.maxModels() || total>settings.maxBytes())) {
            Entry entry=entries.removeFirst();Files.delete(entry.path);total-=entry.bytes;
        }
    }
    private record Entry(Path path,UUID owner,long bytes,long accessed) {}
}
