package com.simmc.meplayeractions.me;

import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.PrivateModelBundle;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import com.simmc.meplayeractions.protection.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Main-thread lookups only dispatch work. Compiled models remain charged until every ME session has stopped. */
public final class ServerTemplatePreparation implements AutoCloseable {
    private static final String KIND="native_template",STAGE="template_prepare";
    private static final long WORK_BYTES=96L*1024*1024,MAX_COMPILED_BYTES=48L*1024*1024;
    private final ResourceProtection protection;
    private final Source source;
    private final Predicate<String> sourceExists;
    private final Consumer<Runnable> testPreparation;
    private final ModelComplexityLimits limits;
    private final int maxModels;
    private final long maxBytes;
    private final LongSupplier clock;
    private final LinkedHashMap<String,Entry> cache=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<String,Boolean> sourcePresence=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashSet<String> wanted=new LinkedHashSet<>();
    private long retainedBytes;
    private boolean closed;
    private BukkitTask maintenance;
    @FunctionalInterface interface Source {InputStream open(String name)throws IOException;}
    public ServerTemplatePreparation(JavaPlugin plugin,ResourceProtection protection) {
        this.protection=Objects.requireNonNull(protection);source=plugin::getResource;testPreparation=null;
        sourceExists=path->plugin.getClass().getClassLoader().getResource(path)!=null;
        limits=protection.complexityLimits();maxModels=protection.settings().models().maxAssetCacheModels();
        maxBytes=protection.settings().models().maxAssetCacheBytes();clock=System::nanoTime;
        protection.gauge("models.nativeTemplateBytes",()->metrics().bytes());
        protection.gauge("models.nativeTemplateEntries",()->metrics().entries());
        maintenance=plugin.getServer().getScheduler().runTaskTimer(plugin,this::pumpPreload,20,20);
    }
    ServerTemplatePreparation(Source source,Consumer<Runnable> preparation,ModelComplexityLimits limits,
                              int maxModels,long maxBytes,LongSupplier clock) {
        this(source,path->true,preparation,limits,maxModels,maxBytes,clock);
    }
    ServerTemplatePreparation(Source source,Predicate<String> sourceExists,Consumer<Runnable> preparation,ModelComplexityLimits limits,
                              int maxModels,long maxBytes,LongSupplier clock) {
        this.protection=null;this.source=Objects.requireNonNull(source);this.sourceExists=Objects.requireNonNull(sourceExists);
        testPreparation=Objects.requireNonNull(preparation);
        this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);this.maxModels=maxModels;this.maxBytes=maxBytes;
        if(maxModels<1||maxBytes<1)throw new IllegalArgumentException("Invalid template cache limits");
    }
    /** A packaged author template is optional. Its absence must never delay a registered ME disguise. */
    public Optional<YsmModelTemplates.Model> ensureIfPresent(String name) {
        return hasSource(name)?ensure(name):Optional.empty();
    }
    private synchronized boolean hasSource(String name) {
        if(name==null||!name.matches("[a-z0-9_-]{1,64}"))throw new ResourceRejectedException(new ResourceError("invalid_model",STAGE,false,0));
        if(closed)throw new ResourceRejectedException(new ResourceError("request_cancelled",STAGE,false,0));
        Boolean present=sourcePresence.get(name);
        if(present!=null)return present;
        // JavaPlugin.getResource uses the same classloader URL lookup. Do not open or parse a file on the main thread.
        present=sourceExists.test("models/"+name+".bbmodel");
        if(sourcePresence.size()>=maxModels)sourcePresence.remove(sourcePresence.keySet().iterator().next());
        sourcePresence.put(name,present);return present;
    }
    public Optional<YsmModelTemplates.Model> ensure(String name) {
        if(name==null||!name.matches("[a-z0-9_-]{1,64}"))throw new ResourceRejectedException(new ResourceError("invalid_model",STAGE,false,0));
        Entry entry;
        synchronized(this) {
            if(closed)throw new ResourceRejectedException(new ResourceError("request_cancelled",STAGE,false,0));
            entry=cache.get(name);
            if(entry!=null) {
                if(entry.ready)return entry.model;
                if(entry.error==null)throw preparing();
                if(!entry.error.retryable()||clock.getAsLong()<entry.retryAt)throw new ResourceRejectedException(entry.error);
                remove(name);entry=null;
            }
            if(cache.size()>=maxModels&&!evictEmpty(name))throw new ResourceRejectedException(ResourceError.busy(STAGE));
            entry=new Entry();cache.put(name,entry);
        }
        Entry token=entry;
        if(protection!=null)protection.submit(null,WORK_BYTES,STAGE,()->load(name),result->publish(name,token,result),error->fail(name,token,error));
        else try{testPreparation.accept(()->publish(name,token,load(name)));}catch(RuntimeException rejected){fail(name,token,ResourceError.busy(STAGE));}
        synchronized(this){if(entry.error!=null)throw new ResourceRejectedException(entry.error);}
        throw preparing();
    }
    private static ResourceRejectedException preparing(){return new ResourceRejectedException(new ResourceError("asset_preparing",STAGE,true,20));}
    public void preload(Collection<String> names) {
        synchronized(this) {
            if(closed)return;
            for(String name:names)if(wanted.size()<maxModels&&name!=null&&name.matches("[a-z0-9_-]{1,64}")&&hasSource(name))wanted.add(name);
        }
        pumpPreload();
    }
    void pumpPreload() {
        String next;
        synchronized(this){if(closed||wanted.isEmpty())return;next=wanted.iterator().next();wanted.remove(next);}
        if(protection!=null&&(!protection.acceptingNewWork()||!protection.tryMainWork(1))) {synchronized(this){wanted.add(next);}return;}
        try{ensureIfPresent(next);}catch(ResourceRejectedException blocked){if(blocked.error().retryable())synchronized(this){if(!closed)wanted.add(next);}}
    }
    private Prepared load(String name) {
        try(InputStream input=source.open("models/"+name+".bbmodel")) {
            ResourceProtection.checkCancelled();
            if(input==null)return new Prepared(Optional.empty(),64,null);
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[8192];int size;
            while((size=input.read(block))!=-1) {
                ResourceProtection.progress();if(size>limits.maxExpandedBytes()-output.size())return invalid("model_complexity");output.write(block,0,size);
            }
            JsonObject raw=PrivateModelBundle.validateModelJson(output.toByteArray(),limits);
            if(!raw.has("mpa_runtime"))return new Prepared(Optional.empty(),64,null);
            long bytes=YsmModelTemplates.retainedBytes(raw);
            if(bytes>maxBytes)return invalid("asset_too_large");
            if(bytes>MAX_COMPILED_BYTES)return invalid("model_complexity");
            ResourceProtection.progress();YsmModelTemplates.Model model=YsmModelTemplates.read(raw);ResourceProtection.checkCancelled();
            return new Prepared(Optional.of(model),bytes,null);
        }catch(IOException invalid){return invalid(invalid.getMessage()!=null&&invalid.getMessage().startsWith("model_complexity:")?"model_complexity":"invalid_model");}
        catch(java.util.concurrent.CancellationException canceled){return new Prepared(Optional.empty(),0,new ResourceError("request_cancelled",STAGE,false,0));}
        catch(RuntimeException invalid){return invalid("invalid_model");}
    }
    private static Prepared invalid(String code){return new Prepared(Optional.empty(),0,new ResourceError(code,STAGE,false,0));}
    private void publish(String name,Entry token,Prepared result) {
        synchronized(this) {
            if(closed||cache.get(name)!=token)return;
            if(result.error!=null){fail(name,token,result.error);return;}
            while(result.bytes>maxBytes-retainedBytes&&evictEmpty(name)){}
            if(result.bytes>maxBytes-retainedBytes||protection!=null&&!protection.reserveCache(KIND,token,result.bytes)) {
                fail(name,token,new ResourceError("memory_limit",STAGE,true,100));return;
            }
            token.model=result.model;token.bytes=result.bytes;token.ready=true;retainedBytes+=result.bytes;
        }
    }
    private synchronized void fail(String name,Entry token,ResourceError error) {
        if(closed||cache.get(name)!=token)return;token.error=error;token.retryAt=clock.getAsLong()+error.retryAfterTicks()*50_000_000L;
    }
    /** Empty entries have no session-owned model references and may safely be evicted. */
    private boolean evictEmpty(String preserve) {
        String candidate=null;
        for(var entry:cache.entrySet())if(!entry.getKey().equals(preserve)&&entry.getValue().ready&&entry.getValue().model.isEmpty()){candidate=entry.getKey();break;}
        if(candidate==null)return false;remove(candidate);return true;
    }
    private void remove(String name) {
        Entry removed=cache.remove(name);if(removed==null||!removed.ready)return;
        retainedBytes-=removed.bytes;if(protection!=null)protection.releaseCache(KIND,removed);
    }
    record Metrics(int entries,long bytes){}
    synchronized Metrics metrics(){return new Metrics(cache.size(),retainedBytes);}
    @Override public synchronized void close() {
        if(closed)return;closed=true;if(maintenance!=null){maintenance.cancel();maintenance=null;}
        wanted.clear();sourcePresence.clear();for(String name:List.copyOf(cache.keySet()))remove(name);
    }
    private static final class Entry {Optional<YsmModelTemplates.Model> model=Optional.empty();boolean ready;long bytes,retryAt;ResourceError error;}
    private record Prepared(Optional<YsmModelTemplates.Model> model,long bytes,ResourceError error){}
}
