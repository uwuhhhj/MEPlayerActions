package com.simmc.meplayeractions.client;

import org.bukkit.configuration.ConfigurationSection;
import com.simmc.meplayeractions.protection.ResourceProtection;
import com.simmc.meplayeractions.protection.ResourceError;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded owner-scoped storage. Disk operations run on a worker; directory snapshots never perform I/O. */
public final class PrivateModelStore {
    private static final Pattern FILE = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}_[a-f0-9]{64}\\.zip");
    private static final Pattern METADATA_FILE = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}_[a-f0-9]{64}\\.meta\\.json");
    private static final Set<String> BUILTIN_IDS = Set.of("openysm_default","wine_fox_01_taisho_maid","wine_fox_02_new_year",
            "wine_fox_03_astronaut","openysm_alex","openysm_steve");
    private static final int MAX_METADATA_BYTES=4096, MAX_SCANNED_FILES=16_384, MAX_VALIDATIONS_PER_REFRESH=8;
    private static final long MAX_VALIDATION_BYTES_PER_REFRESH=16L*1024*1024;
    private final Path directory;
    private final Settings settings;
    private final ResourceProtection resources;
    private final Map<Path,VerifiedMetadata> verified=new HashMap<>();
    private final Map<Path,ValidatedLoad> validatedLoads=new HashMap<>();
    private volatile CatalogSnapshot catalog=new CatalogSnapshot(0,Map.of());
    private long cacheHits,cacheMisses;
    private volatile Statistics statistics=new Statistics(0,0,0,0);
    public record Statistics(long bytes,int models,long hits,long misses) {}

    /** Receipt metadata is not a publication and never grants another owner access to this bundle. */
    public record UploadedModel(String sourceId,String hash,String kind,int bytes) {}
    public record CatalogSnapshot(long revision,Map<UUID,List<UploadedModel>> modelsByOwner) {
        public CatalogSnapshot {
            Map<UUID,List<UploadedModel>> copy=new HashMap<>();
            modelsByOwner.forEach((owner,models)->copy.put(owner,List.copyOf(models)));
            modelsByOwner=Map.copyOf(copy);
        }
        public List<UploadedModel> models(UUID owner){return modelsByOwner.getOrDefault(owner,List.of());}
    }
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
        this(directory,settings,null);
    }
    public PrivateModelStore(Path directory,Settings settings,ResourceProtection resources) {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();this.settings=Objects.requireNonNull(settings);
        this.resources=resources;
    }
    public boolean enabled(){return settings.enabled();}
    public CatalogSnapshot snapshot(){return catalog;}
    /** Cached counters only; status never scans or waits for the storage worker monitor. */
    public Statistics statistics(){return statistics;}
    public List<UploadedModel> listUploaded(UUID owner){return catalog.models(owner);}

    /** Matches the current local model identifier contract without treating an identifier as a disk path. */
    static boolean validSourceId(String id) {
        if(BUILTIN_IDS.contains(id==null?"":id))return true;
        if(id==null || id.length()>128)return false;
        if(id.startsWith("local:")) {
            String filename=id.substring(6);
            return filename.length()>8 && filename.endsWith(".bbmodel") && safeSourceName(filename);
        }
        if(!id.startsWith("ysm:"))return false;
        String[] segments=id.substring(4).split("/",-1);
        if(segments.length>9)return false;
        for(String segment:segments)if(!safeSourceName(segment))return false;
        return true;
    }
    private static boolean safeSourceName(String name) {
        return !name.isBlank() && !name.startsWith(".") && !name.contains("..") && name.equals(name.strip())
                && name.chars().noneMatch(Character::isISOControl) && !name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*");
    }
    /** An owner can only reuse bytes that were previously stored under that owner's UUID. */
    synchronized byte[] load(UUID owner,String hash,String kind,int expectedBytes) throws IOException {
        Path path=path(owner,hash);
        cacheMisses++;statistics=new Statistics(statistics.bytes,statistics.models,cacheHits,cacheMisses);
        if(!enabled() || !Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return null;
        requireDirectory();
        List<Entry> entries=entries();trimToLimits(entries);publishVerified(entries);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=expectedBytes || expectedBytes>PrivateModelBundle.MAX_BYTES)return null;
        ResourceProtection.checkCancelled();byte[] bytes=readBundle(path,expectedBytes);
        try {
            if(!PrivateModelBundle.hash(bytes).equals(hash))throw new IOException("cache_hash");
            validateBundle(bytes,kind);
        }catch(IOException | RuntimeException invalid){ResourceProtection.checkCancelled();deleteEntry(path);publishVerified(entries());return null;}
        Files.setAttribute(path,"basic:lastModifiedTime",FileTime.fromMillis(System.currentTimeMillis()),LinkOption.NOFOLLOW_LINKS);
        Fingerprint identity=fingerprint(path);
        validatedLoads.put(path,new ValidatedLoad(hash,kind,expectedBytes,identity));
        VerifiedMetadata metadata=verified.get(path);
        if(metadata!=null && metadata.model.hash().equals(hash) && metadata.model.kind().equals(kind))
            verified.put(path,new VerifiedMetadata(metadata.model,identity,metadata.sidecar));
        cacheMisses--;cacheHits++;publishVerified(entries());
        return bytes;
    }
    /** Only the relay's successful whole-bundle validator may call this method. */
    synchronized void saveValidated(UUID owner,String hash,byte[] bytes) throws IOException {
        saveValidated(owner,null,hash,null,bytes);
    }
    /** Only a successfully validated upload can create an owner-scoped persistent directory receipt. */
    synchronized void saveValidated(UUID owner,String sourceId,String hash,String kind,byte[] bytes) throws IOException {
        Path target=path(owner,hash);
        if(!enabled())return;
        if(sourceId!=null && (!validSourceId(sourceId) || !Set.of("ysm","bbmodel").contains(kind==null?"":kind)))
            throw new IOException("cache_metadata_identity");
        if(bytes==null || bytes.length<22 || bytes.length>PrivateModelBundle.MAX_BYTES || !PrivateModelBundle.hash(bytes).equals(hash))
            throw new IOException("cache_identity");
        Path sidecar=metadataPath(target);
        if(sourceId!=null && Files.exists(sidecar,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("cache_metadata_file");
        Files.createDirectories(directory);requireDirectory();
        List<Entry> entries=entries();trimToLimits(entries);
        entries.removeIf(entry->entry.path.equals(target));
        long total=entries.stream().mapToLong(Entry::bytes).sum();
        int owned=(int)entries.stream().filter(entry->entry.owner.equals(owner)).count();
        // Each successful write first bounds the per-owner library, then the shared disk budget.
        for(Iterator<Entry> iterator=entries.iterator();owned>=settings.maxModelsPerPlayer() && iterator.hasNext();) {
            Entry entry=iterator.next();if(!entry.owner.equals(owner))continue;
            deleteEntry(entry.path);iterator.remove();total-=entry.bytes;owned--;
        }
        while(!entries.isEmpty() && (entries.size()>=settings.maxModels() || total+bytes.length>settings.maxBytes())) {
            Entry entry=entries.removeFirst();deleteEntry(entry.path);total-=entry.bytes;
        }
        if(entries.size()>=settings.maxModels() || total+bytes.length>settings.maxBytes())throw new IOException("cache_capacity");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("cache_file");
        atomicWrite(target,bytes,".private-upload-");
        validatedLoads.remove(target);
        if(sourceId!=null) {
            UploadedModel model=new UploadedModel(sourceId,hash,kind,bytes.length);
            try {
                atomicWrite(sidecar,encodeMetadata(owner,model),".private-metadata-");
                verified.put(target,new VerifiedMetadata(model,fingerprint(target),fingerprint(sidecar)));
            }catch(IOException failure) {
                verified.remove(target);deleteMetadata(target);publishVerified(entries());throw failure;
            }
        } else {
            VerifiedMetadata previous=verified.get(target);
            if(previous!=null)verified.put(target,new VerifiedMetadata(previous.model,fingerprint(target),previous.sidecar));
        }
        publishVerified(entries());
    }

    /** A cache probe has already checked the ZIP; associate its exact owner/hash with a name without rewriting bytes. */
    synchronized void recordValidatedIdentity(UUID owner,String sourceId,String hash,String kind,int expectedBytes) throws IOException {
        Path target=path(owner,hash);if(!enabled())return;
        if(!validSourceId(sourceId) || !Set.of("ysm","bbmodel").contains(kind==null?"":kind))throw new IOException("cache_metadata_identity");
        requireDirectory();ValidatedLoad proof=validatedLoads.get(target);
        if(proof==null || !proof.hash.equals(hash) || !proof.kind.equals(kind) || proof.bytes!=expectedBytes
                || !proof.bundle.equals(fingerprint(target)))throw new IOException("cache_unvalidated_identity");
        Path sidecar=metadataPath(target);
        UploadedModel model=new UploadedModel(sourceId,hash,kind,expectedBytes);
        atomicWrite(sidecar,encodeMetadata(owner,model),".private-metadata-");
        verified.put(target,new VerifiedMetadata(model,proof.bundle,fingerprint(sidecar)));publishVerified(entries());
    }

    /** The current exact receipt guards deletion of this owner's verified versions of the same source. */
    synchronized boolean deleteValidated(UUID owner,String sourceId,String hash,String kind,int expectedBytes) throws IOException {
        Path target=path(owner,hash);
        if(!enabled())return false;
        if(!validSourceId(sourceId) || !Set.of("ysm","bbmodel").contains(kind==null?"":kind)
                || expectedBytes<22 || expectedBytes>PrivateModelBundle.MAX_BYTES)throw new IOException("cache_metadata_identity");
        UploadedModel expected=new UploadedModel(sourceId,hash,kind,expectedBytes);
        if(!catalog.models(owner).contains(expected))return false;
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) {
            verified.clear();validatedLoads.clear();publish(Map.of());return false;
        }
        try {
            requireDirectory();
            VerifiedMetadata current=verified.get(target);
            if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS) || !Files.exists(metadataPath(target),LinkOption.NOFOLLOW_LINKS)) {
                invalidateReceipt(target);return false;
            }
            if(current==null || !current.model.equals(expected)) {
                invalidateReceipt(target);throw new IOException("cache_delete_changed");
            }
            List<Deletion> candidates=new ArrayList<>();
            for(var entry:verified.entrySet()) {
                UploadedModel model=entry.getValue().model;
                if(model.sourceId().equals(sourceId) && entry.getKey().equals(path(owner,model.hash())))
                    candidates.add(new Deletion(entry.getKey(),entry.getValue()));
            }
            // Preflight every version before deleting any bytes. Another source or an unverified file is never selected.
            for(Deletion candidate:candidates) {
                Path bundle=candidate.path,sidecar=metadataPath(bundle);VerifiedMetadata metadata=candidate.metadata;
                if(!Files.exists(bundle,LinkOption.NOFOLLOW_LINKS) || !Files.exists(sidecar,LinkOption.NOFOLLOW_LINKS)) {
                    invalidateReceipt(bundle);return false;
                }
                if(!Files.isRegularFile(bundle,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS)
                        || !metadata.bundle.equals(fingerprint(bundle)) || !metadata.sidecar.equals(fingerprint(sidecar))) {
                    invalidateReceipt(bundle);throw new IOException("cache_delete_changed");
                }
            }
            // A progressive bootstrap may not have checked an older version yet. Wait instead of resurrecting it later.
            for(Entry entry:entries()) {
                if(!entry.owner.equals(owner))continue;
                Path sidecar=metadataPath(entry.path);if(!Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS))continue;
                VerifiedMetadata previous=verified.get(entry.path);
                if(previous!=null && previous.bundle.equals(entry.identity) && previous.sidecar.equals(fingerprint(sidecar)))continue;
                UploadedModel pending;
                try{pending=readMetadata(entry,sidecar);}catch(IOException | RuntimeException invalid){continue;}
                if(pending.sourceId().equals(sourceId))throw new IOException("cache_delete_pending_validation");
            }
            // Keep the visible current version until all older versions have been removed successfully.
            candidates.sort(Comparator.comparing((Deletion candidate)->candidate.path.equals(target))
                    .thenComparing(candidate->candidate.metadata.bundle.modified).thenComparing(candidate->candidate.path.toString()));
            try {
                for(Deletion candidate:candidates)deleteEntry(candidate.path);
            }finally{publishVerified(entries());}
            return true;
        }catch(IOException failure) {
            if("cache_delete_pending_validation".equals(failure.getMessage()))throw failure;
            try{publishVerified(entries());}catch(IOException unavailable){verified.clear();validatedLoads.clear();publish(Map.of());}
            throw failure;
        }
    }
    private void invalidateReceipt(Path target) throws IOException {
        verified.remove(target);validatedLoads.remove(target);publishVerified(entries());
    }

    /** One shared background refresh; unchanged metadata and bundles are reused from their verified fingerprints. */
    public synchronized void refreshCatalog() throws IOException {
        try{refreshCatalogContents();}
        catch(IOException failure){verified.clear();validatedLoads.clear();publish(Map.of());throw failure;}
    }
    private void refreshCatalogContents() throws IOException {
        if(!enabled()){verified.clear();validatedLoads.clear();publish(Map.of());return;}
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){verified.clear();validatedLoads.clear();publish(Map.of());return;}
        requireDirectory();
        List<Entry> entries=entries();trimToLimits(entries);
        Set<Path> present=new HashSet<>();long validationBytes=0;int validations=0;
        // Recent uploads become available first when a large cache must be checked over several refreshes.
        for(int index=entries.size()-1;index>=0;index--) {
            Entry entry=entries.get(index);Path path=entry.path,sidecar=metadataPath(path);present.add(path);
            if(entry.bytes<22 || entry.bytes>PrivateModelBundle.MAX_BYTES || !Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS)) {
                verified.remove(path);if(entry.bytes<22 || entry.bytes>PrivateModelBundle.MAX_BYTES)deleteMetadata(path);continue;
            }
            Fingerprint bundleIdentity=fingerprint(path),metadataIdentity=fingerprint(sidecar);
            VerifiedMetadata previous=verified.get(path);
            if(previous!=null && previous.bundle.equals(bundleIdentity) && previous.sidecar.equals(metadataIdentity))continue;
            verified.remove(path);
            UploadedModel model;
            try{model=readMetadata(entry,sidecar);}catch(IOException | RuntimeException invalid){ResourceProtection.checkCancelled();deleteMetadata(path);continue;}
            if(validations>=MAX_VALIDATIONS_PER_REFRESH || validationBytes+entry.bytes>MAX_VALIDATION_BYTES_PER_REFRESH)continue;
            validations++;validationBytes+=entry.bytes;
            try {
                byte[] bytes=readBundle(path,model.bytes());
                if(!PrivateModelBundle.hash(bytes).equals(model.hash()))throw new IOException("cache_hash");
                ResourceProtection.checkCancelled();validateBundle(bytes,model.kind());
                if(!bundleIdentity.equals(fingerprint(path)) || !metadataIdentity.equals(fingerprint(sidecar)))continue;
                verified.put(path,new VerifiedMetadata(model,bundleIdentity,metadataIdentity));
            }catch(IOException | RuntimeException invalid){ResourceProtection.checkCancelled();deleteEntry(path);}
        }
        verified.keySet().retainAll(present);validatedLoads.keySet().retainAll(present);publishVerified(entries);
    }

    private void publishVerified(List<Entry> entries) {
        Map<UUID,LinkedHashMap<String,UploadedModel>> newest=new HashMap<>();
        Set<Path> present=new HashSet<>();for(Entry entry:entries)present.add(entry.path);validatedLoads.keySet().retainAll(present);
        for(int index=entries.size()-1;index>=0;index--) {
            Entry entry=entries.get(index);VerifiedMetadata metadata=verified.get(entry.path);if(metadata==null)continue;
            if(!metadata.bundle.equals(entry.identity))continue;
            newest.computeIfAbsent(entry.owner,ignored->new LinkedHashMap<>()).putIfAbsent(metadata.model.sourceId(),metadata.model);
        }
        Map<UUID,List<UploadedModel>> models=new HashMap<>();
        newest.forEach((owner,bySource)->models.put(owner,List.copyOf(bySource.values())));publish(models);
    }
    private void publish(Map<UUID,List<UploadedModel>> models) {
        if(!catalog.modelsByOwner().equals(models))catalog=new CatalogSnapshot(catalog.revision()+1,models);
    }
    private UploadedModel readMetadata(Entry entry,Path sidecar) throws IOException {
        Fingerprint attributes=fingerprint(sidecar);
        if(attributes.bytes<2 || attributes.bytes>MAX_METADATA_BYTES)throw new IOException("cache_metadata_size");
        byte[] bytes=readBundle(sidecar,(int)attributes.bytes);
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        Map<String,String> strings=new HashMap<>();Map<String,Long> numbers=new HashMap<>();Set<String> names=new HashSet<>();
        try(JsonReader reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),decoder))) {
            reader.setStrictness(Strictness.STRICT);reader.beginObject();
            while(reader.hasNext()) {
                String name=reader.nextName();if(!names.add(name))throw new IOException("cache_metadata_duplicate");
                if(Set.of("owner","sourceId","hash","kind").contains(name)) {
                    if(reader.peek()!=JsonToken.STRING)throw new IOException("cache_metadata_string");strings.put(name,reader.nextString());
                } else if(Set.of("format","bytes").contains(name)) {
                    if(reader.peek()!=JsonToken.NUMBER)throw new IOException("cache_metadata_number");
                    String number=reader.nextString();if(!number.matches("[0-9]{1,10}"))throw new IOException("cache_metadata_number");
                    numbers.put(name,Long.parseLong(number));
                } else throw new IOException("cache_metadata_field");
            }
            reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("cache_metadata_trailing");
        }
        String hash=entry.path.getFileName().toString().substring(37,101),sourceId=strings.get("sourceId"),kind=strings.get("kind");
        if(names.size()!=6 || !Objects.equals(numbers.get("format"),1L) || !entry.owner.toString().equals(strings.get("owner"))
                || !hash.equals(strings.get("hash")) || !validSourceId(sourceId) || !Set.of("ysm","bbmodel").contains(kind==null?"":kind)
                || !Objects.equals(numbers.get("bytes"),entry.bytes))throw new IOException("cache_metadata_identity");
        return new UploadedModel(sourceId,hash,kind,(int)entry.bytes);
    }
    private static byte[] encodeMetadata(UUID owner,UploadedModel model) throws IOException {
        StringWriter output=new StringWriter();
        try(JsonWriter writer=new JsonWriter(output)) {
            writer.beginObject();writer.name("format").value(1);writer.name("owner").value(owner.toString());
            writer.name("sourceId").value(model.sourceId());writer.name("hash").value(model.hash());
            writer.name("kind").value(model.kind());writer.name("bytes").value(model.bytes());writer.endObject();
        }
        byte[] bytes=output.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_METADATA_BYTES)throw new IOException("cache_metadata_size");return bytes;
    }
    private static byte[] readBundle(Path path,int expectedBytes) throws IOException {
        ResourceProtection.checkCancelled();
        byte[] bytes=new byte[expectedBytes];
        try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            int offset=0;while(offset<bytes.length) {
                ResourceProtection.checkCancelled();int count=input.read(bytes,offset,Math.min(64*1024,bytes.length-offset));
                if(count<0)throw new IOException("cache_size");offset+=count;ResourceProtection.progress();
            }
            if(input.read()!=-1)throw new IOException("cache_size");
        }
        return bytes;
    }
    private void validateBundle(byte[] bytes,String kind) throws IOException {
        if(resources==null)PrivateModelBundle.validate(bytes,kind);else PrivateModelBundle.validate(bytes,kind,resources.complexityLimits());
    }
    private void atomicWrite(Path target,byte[] bytes,String prefix) throws IOException {
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("cache_file");
        ResourceProtection.checkCancelled();
        ResourceError rejection=resources==null?null:resources.reserveTemporary(null,bytes.length,directory);
        if(rejection!=null)throw new IOException(rejection.code());
        Path temporary=null;
        try {
            temporary=Files.createTempFile(directory,prefix,".tmp");
            try(var output=Files.newOutputStream(temporary,StandardOpenOption.TRUNCATE_EXISTING)) {
                for(int offset=0;offset<bytes.length;offset+=64*1024){ResourceProtection.checkCancelled();output.write(bytes,offset,Math.min(64*1024,bytes.length-offset));ResourceProtection.progress();}
            }
            ResourceProtection.checkCancelled();
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{try{if(temporary!=null)Files.deleteIfExists(temporary);}finally{if(resources!=null)resources.releaseTemporary(null,bytes.length);}}
    }
    private Path metadataPath(Path bundle){return directory.resolve(bundle.getFileName().toString().replace(".zip",".meta.json"));}
    private void deleteMetadata(Path bundle) throws IOException {
        Path sidecar=metadataPath(bundle);
        if(Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sidecar))Files.deleteIfExists(sidecar);
    }
    private void deleteEntry(Path path) throws IOException {verified.remove(path);validatedLoads.remove(path);Files.deleteIfExists(path);deleteMetadata(path);}
    private static Fingerprint fingerprint(Path path) throws IOException {
        BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile())throw new IOException("cache_file");
        return new Fingerprint(attributes.size(),attributes.lastModifiedTime(),attributes.fileKey());
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
        requireDirectory();
        List<Entry> entries=new ArrayList<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(directory)) {
            int scanned=0;
            for(Path path:stream) {
                ResourceProtection.checkCancelled();
                // A manually polluted directory must not turn a client upload into unbounded I/O.
                if(++scanned>MAX_SCANNED_FILES)throw new IOException("cache_directory_limit");
                String name=path.getFileName().toString();
                if(METADATA_FILE.matcher(name).matches() && (Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))) {
                    Path bundle=directory.resolve(name.substring(0,name.length()-10)+".zip");
                    if(!Files.isRegularFile(bundle,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(path);
                    continue;
                }
                if(!FILE.matcher(name).matches() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                Fingerprint identity=fingerprint(path);
                entries.add(new Entry(path,UUID.fromString(name.substring(0,36)),identity.bytes,identity.modified.toMillis(),identity));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::accessed).thenComparing(entry->entry.path.toString()));
        statistics=new Statistics(entries.stream().mapToLong(Entry::bytes).sum(),entries.size(),cacheHits,cacheMisses);return entries;
    }
    private void trimToLimits(List<Entry> entries) throws IOException {
        Map<UUID,Integer> owned=new HashMap<>();for(Entry entry:entries)owned.merge(entry.owner,1,Integer::sum);
        long total=entries.stream().mapToLong(Entry::bytes).sum();
        for(Iterator<Entry> iterator=entries.iterator();iterator.hasNext();) {
            Entry entry=iterator.next();
            if(owned.get(entry.owner)<=settings.maxModelsPerPlayer())continue;
            deleteEntry(entry.path);iterator.remove();total-=entry.bytes;owned.merge(entry.owner,-1,Integer::sum);
        }
        while(!entries.isEmpty() && (entries.size()>settings.maxModels() || total>settings.maxBytes())) {
            Entry entry=entries.removeFirst();deleteEntry(entry.path);total-=entry.bytes;
        }
    }
    private record Entry(Path path,UUID owner,long bytes,long accessed,Fingerprint identity) {}
    private record Fingerprint(long bytes,FileTime modified,Object fileKey) {}
    private record VerifiedMetadata(UploadedModel model,Fingerprint bundle,Fingerprint sidecar) {}
    private record ValidatedLoad(String hash,String kind,int bytes,Fingerprint bundle) {}
    private record Deletion(Path path,VerifiedMetadata metadata) {}
}
