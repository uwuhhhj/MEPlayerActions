package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.config.PerformanceSettings;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Actual negotiation and budget paths through Bukkit's public Player/Server interfaces. */
class PrivateUploadCatalogServiceTest {
    @TempDir Path temporary;

    @Test void negotiatedOwnersReceiveOnlyTheirOwnReceiptsAndTheSharedRefreshDoesNotPublishOrRunOnTheMainThread() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] first=bundle(1),second=bundle(2);String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second);
            scene.store.saveValidated(scene.owner.id,"ysm:本人.ysm",firstHash,"ysm",first);
            scene.store.saveValidated(scene.viewer.id,"ysm:其他.ysm",secondHash,"ysm",second);
            scene.hello(scene.owner,true);scene.hello(scene.viewer,true);scene.hello(scene.legacy,false);
            assertEquals(firstHash,scene.owner.last("upload_catalog").getAsJsonArray("models").get(0).getAsJsonObject().get("hash").getAsString());
            assertEquals(secondHash,scene.viewer.last("upload_catalog").getAsJsonArray("models").get(0).getAsJsonObject().get("hash").getAsString());
            assertEquals(0,scene.legacy.count("upload_catalog"));assertTrue(scene.publications().isEmpty());
            assertEquals(Set.of(PrivateModelSyncService.CAPABILITY,PrivateUploadCatalog.CAPABILITY),
                    scene.owner.last("hello_ack").getAsJsonArray("capabilities").asList().stream().map(JsonElement::getAsString).collect(java.util.stream.Collectors.toSet()));
            scene.maintain();assertEquals(1,scene.workers.size());assertEquals(0,scene.callbacks.size());
            for(int tick=1;tick<100;tick++){scene.tick=tick;scene.maintain();}
            assertEquals(1,scene.workers.size(),"One pending worker serves all owners");
            scene.workers.removeFirst().run();assertEquals(1,scene.callbacks.size());scene.callbacks.removeFirst().run();scene.tick=100;scene.maintain();assertEquals(1,scene.workers.size());
            assertTrue(scene.publications().isEmpty());assertEquals(1,scene.owner.count("upload_catalog"));
        }
    }
    @Test void clearingAnActualPublicationPreservesTheReceiptButRevokingUploadPermissionSendsAnEmptyDirectory() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes);
            scene.store.saveValidated(scene.owner.id,"ysm:saved.ysm",hash,"ysm",bytes);scene.hello(scene.owner,true);
            scene.publication(scene.owner,bytes);assertEquals(1,scene.publications().size());
            scene.send(scene.owner,packet("clear"));assertTrue(scene.publications().isEmpty());assertEquals(1,scene.store.listUploaded(scene.owner.id).size());
            assertArrayEquals(bytes,scene.store.load(scene.owner.id,hash,"ysm",bytes.length));
            scene.owner.upload=false;scene.tick=1;scene.update(scene.owner);scene.pump();
            JsonObject revoked=scene.owner.last("upload_catalog");assertFalse(revoked.get("available").getAsBoolean());
            assertTrue(revoked.getAsJsonArray("models").isEmpty());assertEquals(2,revoked.get("revision").getAsLong());
            assertEquals(1,scene.store.listUploaded(scene.owner.id).size());assertTrue(scene.publications().isEmpty());
            assertEquals(0,scene.viewer.count("upload_catalog"));assertEquals(0,scene.legacy.count("upload_catalog"));
        }
    }
    @Test void metadataFragmentsSendAtMostOncePerTickAndRetryTheSameIndexAfterGlobalOrConnectionPressure() throws Exception {
        try(var scene=new Scene(temporary)) {
            List<PrivateModelStore.UploadedModel> models=PrivateUploadCatalogTest.models(64,true);
            set(scene.store,"catalog",new PrivateModelStore.CatalogSnapshot(1,Map.of(scene.owner.id,models)));
            scene.hello(scene.owner,true);assertEquals(1,scene.owner.count("upload_catalog"));scene.pump();
            assertEquals(1,scene.owner.count("upload_catalog"));
            scene.tick=1;assertTrue(scene.limits.allowOutbound(new UUID(90,90),ConnectionLimits.GLOBAL_BYTES_PER_TICK,false,scene.now(),scene.tick));
            scene.pump();assertEquals(1,scene.owner.count("upload_catalog"));assertEquals(1,((Number)field(scene.session(scene.owner),"catalogIndex")).intValue());
            scene.tick=2;scene.pump();scene.pump();assertEquals(2,scene.owner.count("upload_catalog"));
            scene.saturateOwnerWindow();int deferred=((Number)field(scene.session(scene.owner),"catalogIndex")).intValue();
            scene.pump();assertEquals(2,scene.owner.count("upload_catalog"));assertEquals(deferred,((Number)field(scene.session(scene.owner),"catalogIndex")).intValue());
            scene.resetOwnerWindow();scene.tick++;
            for(int attempts=0;attempts<64&&!((Set<?>)field(scene.service,"pendingCatalogs")).isEmpty();attempts++){scene.pump();scene.tick++;}
            List<JsonObject> fragments=scene.owner.ofType("upload_catalog");int count=fragments.getFirst().get("count").getAsInt();
            assertEquals(count,fragments.size());assertEquals(IntStream.range(0,count).boxed().toList(),fragments.stream().map(value->value.get("index").getAsInt()).toList());
            assertEquals(64,fragments.stream().mapToInt(value->value.getAsJsonArray("models").size()).sum());
            assertTrue(scene.owner.wire.stream().allMatch(bytes->bytes.length<=1024));assertTrue(scene.publications().isEmpty());
        }
    }
    @Test void sourceIdsRequireTheNegotiatedCapabilityAndOnlyAnExplicitAuthorizedCacheOfferCreatesAPublication() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes);
            scene.store.saveValidated(scene.owner.id,"ysm:original.ysm",hash,"ysm",bytes);
            scene.hello(scene.legacy,false);scene.send(scene.legacy,offer(hash,bytes.length,"ysm:renamed.ysm"));
            assertEquals("invalid_private_payload",scene.legacy.last("error").get("code").getAsString());
            assertEquals(0,scene.workers.size());assertEquals(0,((Number)field(scene.service,"uploads")).intValue());assertTrue(scene.publications().isEmpty());
            scene.hello(scene.owner,true);assertTrue(scene.publications().isEmpty());
            scene.send(scene.owner,offer(hash,bytes.length,"ysm:renamed.ysm"));assertEquals(1,scene.workers.size());
            assertTrue(scene.publications().isEmpty());scene.workers.removeFirst().run();assertEquals(1,scene.callbacks.size());
            assertTrue(scene.publications().isEmpty());scene.callbacks.removeFirst().run();
            assertEquals(Set.of(scene.owner.id),scene.publications().keySet());assertEquals(0,scene.owner.count("upload_accept"));
            assertEquals(1,scene.owner.count("upload_committed"));assertEquals("ysm:renamed.ysm",scene.store.listUploaded(scene.owner.id).getFirst().sourceId());
            scene.tick=1;scene.pump();assertEquals("ysm:renamed.ysm",scene.owner.last("upload_catalog").getAsJsonArray("models").get(0).getAsJsonObject().get("modelId").getAsString());
        }
    }
    @Test void deletingAnExactUploadRunsOnTheWorkerAndRevokesItsPublicationWithoutTouchingAnotherOwnersCopy() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes),source="ysm:saved.ysm";
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);
            scene.store.saveValidated(scene.viewer.id,"ysm:foreign.ysm",hash,"ysm",bytes);
            scene.hello(scene.owner,true);scene.hello(scene.viewer,true);scene.publication(scene.owner,bytes);
            set(scene.publications().get(scene.owner.id),"sourceId",source);scene.observe(scene.owner,scene.viewer);
            UUID request=UUID.randomUUID();JsonObject delete=scene.delete(scene.owner,request,source,hash,bytes.length);
            scene.send(scene.owner,delete);scene.send(scene.owner,delete);
            assertEquals(1,scene.workers.size());assertEquals(0,scene.callbacks.size());assertEquals(1,scene.publications().size());
            assertEquals(1,scene.store.listUploaded(scene.owner.id).size());assertTrue(Files.exists(scene.archive(scene.owner,hash)));
            assertEquals(0,scene.owner.count("upload_deleted"));
            scene.workers.removeFirst().run();assertEquals(1,scene.callbacks.size());
            assertFalse(Files.exists(scene.archive(scene.owner,hash)));assertFalse(Files.exists(scene.metadata(scene.owner,hash)));
            assertEquals(1,scene.publications().size());assertEquals(0,scene.viewer.count("private_remove"));
            scene.callbacks.removeFirst().run();assertTrue(scene.publications().isEmpty());
            assertEquals("upload_deleted",scene.viewer.last("private_remove").get("reason").getAsString());
            assertEquals(request.toString(),scene.owner.last("upload_deleted").get("requestId").getAsString());
            assertEquals(source,scene.owner.last("upload_deleted").get("modelId").getAsString());
            assertTrue(Files.exists(scene.archive(scene.viewer,hash)));assertEquals(1,scene.store.listUploaded(scene.viewer.id).size());
            scene.tick=1;scene.pump();assertTrue(scene.owner.last("upload_catalog").getAsJsonArray("models").isEmpty());
            scene.send(scene.owner,delete);assertEquals(0,scene.workers.size());assertEquals(2,scene.owner.count("upload_deleted"));
        }
    }
    @Test void staleHashesAndRevokedOrUnnegotiatedDeleteRequestsNeverStartDiskWork() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] first=bundle(1),second=bundle(2);String firstHash=PrivateModelBundle.hash(first),secondHash=PrivateModelBundle.hash(second),source="ysm:saved.ysm";
            scene.store.saveValidated(scene.owner.id,source,firstHash,"ysm",first);
            Files.setLastModifiedTime(scene.archive(scene.owner,firstHash),java.nio.file.attribute.FileTime.fromMillis(1));
            scene.store.saveValidated(scene.owner.id,source,secondHash,"ysm",second);
            scene.hello(scene.owner,true);scene.hello(scene.legacy,false);
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,firstHash,first.length));
            assertEquals("private_delete_not_found",scene.owner.last("upload_delete_failed").get("code").getAsString());
            scene.owner.upload=false;scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,secondHash,second.length));
            assertEquals("private_delete_denied",scene.owner.last("upload_delete_failed").get("code").getAsString());
            scene.send(scene.legacy,scene.delete(scene.legacy,UUID.randomUUID(),source,secondHash,second.length));
            assertEquals("private_delete_denied",scene.legacy.last("upload_delete_failed").get("code").getAsString());
            assertTrue(scene.workers.isEmpty());assertTrue(scene.publications().isEmpty());
            assertTrue(Files.exists(scene.archive(scene.owner,firstHash)));assertTrue(Files.exists(scene.archive(scene.owner,secondHash)));
            assertEquals(secondHash,scene.store.listUploaded(scene.owner.id).getFirst().hash());
        }
    }
    @Test void completedOrFailedDeleteAfterOwnerQuitReleasesSlotsWithoutRevivingAnOldSessionReply() throws Exception {
        for(boolean failed:List.of(false,true))try(var scene=new Scene(temporary.resolve("late-delete-"+failed))) {
            byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes),source="ysm:saved.ysm";
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);scene.hello(scene.owner,true);
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,hash,bytes.length));
            assertEquals(1,((Set<?>)field(scene.service,"deleting")).size());
            try {
                if(failed)Thread.currentThread().interrupt();
                scene.workers.removeFirst().run();
            }finally{Thread.interrupted();}
            assertEquals(1,scene.callbacks.size());scene.service.forget(scene.owner.player);
            assertNull(scene.session(scene.owner));scene.callbacks.removeFirst().run();
            assertTrue(((Set<?>)field(scene.service,"deleting")).isEmpty());assertTrue(((Set<?>)field(scene.service,"deletingSources")).isEmpty());
            assertTrue(((Set<?>)field(scene.service,"pendingDeleteReplies")).isEmpty());
            assertEquals(0,scene.owner.count("upload_deleted"));assertEquals(0,scene.owner.count("upload_delete_failed"));
            assertEquals(failed,Files.exists(scene.archive(scene.owner,hash)));
        }
    }
    @Test void anOwnerHashCannotBeUploadedWhileDeletingOrDeletedWhileItsCancelledValidatorStillRuns() throws Exception {
        byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes),source="ysm:saved.ysm";
        try(var scene=new Scene(temporary)) {
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);scene.hello(scene.owner,true);
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,hash,bytes.length));assertEquals(1,scene.workers.size());
            scene.send(scene.owner,offer(hash,bytes.length,source));
            assertEquals("private_upload_busy",scene.owner.last("error").get("code").getAsString());
            byte[] replacement=bundle(2);scene.send(scene.owner,offer(PrivateModelBundle.hash(replacement),replacement.length,source));
            assertEquals("private_upload_busy",scene.owner.last("error").get("code").getAsString(),"A different hash of the same source cannot restore a deleted archive");
            assertEquals(1,scene.workers.size());assertEquals(0,((Number)field(scene.service,"uploads")).intValue());
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();assertFalse(Files.exists(scene.archive(scene.owner,hash)));
        }
        try(var scene=new Scene(temporary)) {
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);scene.hello(scene.owner,true);
            scene.send(scene.owner,offer(hash,bytes.length,source));assertEquals(1,scene.workers.size());
            scene.send(scene.owner,packet("clear"));assertEquals(1,((Number)field(scene.service,"uploads")).intValue());
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,hash,bytes.length));
            assertEquals("private_delete_busy",scene.owner.last("upload_delete_failed").get("code").getAsString());
            assertEquals(1,scene.workers.size());assertTrue(Files.exists(scene.archive(scene.owner,hash)));
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();assertTrue(scene.publications().isEmpty());
            assertEquals(0,((Number)field(scene.service,"uploads")).intValue());
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,hash,bytes.length));assertEquals(1,scene.workers.size());
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();
            assertFalse(Files.exists(scene.archive(scene.owner,hash)));assertFalse(Files.exists(scene.metadata(scene.owner,hash)));
            assertTrue(scene.publications().isEmpty());assertTrue(scene.store.listUploaded(scene.owner.id).isEmpty());
        }
    }
    @Test void deletingASourcesHistoryRevokesItsOlderPublicationButLeavesADifferentPublishedSourceAlone() throws Exception {
        byte[] older=bundle(1),latest=bundle(2),other=bundle(3);String oldHash=PrivateModelBundle.hash(older),latestHash=PrivateModelBundle.hash(latest),otherHash=PrivateModelBundle.hash(other),source="ysm:saved.ysm";
        for(boolean sameSource:List.of(true,false))try(var scene=new Scene(temporary.resolve(Boolean.toString(sameSource)))) {
            scene.store.saveValidated(scene.owner.id,source,oldHash,"ysm",older);
            Files.setLastModifiedTime(scene.archive(scene.owner,oldHash),java.nio.file.attribute.FileTime.fromMillis(1));
            scene.store.saveValidated(scene.owner.id,source,latestHash,"ysm",latest);
            scene.store.saveValidated(scene.owner.id,"ysm:other.ysm",otherHash,"ysm",other);
            scene.store.refreshCatalog();
            scene.hello(scene.owner,true);scene.hello(scene.viewer,true);scene.publication(scene.owner,sameSource?older:other);
            Object published=scene.publications().get(scene.owner.id);set(published,"sourceId",sameSource?source:"ysm:other.ysm");scene.observe(scene.owner,scene.viewer);
            scene.send(scene.owner,scene.delete(scene.owner,UUID.randomUUID(),source,latestHash,latest.length));
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();
            assertFalse(Files.exists(scene.archive(scene.owner,oldHash)));assertFalse(Files.exists(scene.archive(scene.owner,latestHash)));
            assertTrue(Files.exists(scene.archive(scene.owner,otherHash)));assertEquals(List.of("ysm:other.ysm"),scene.store.listUploaded(scene.owner.id).stream().map(PrivateModelStore.UploadedModel::sourceId).toList());
            if(sameSource){assertTrue(scene.publications().isEmpty());assertEquals(1,scene.viewer.count("private_remove"));}
            else{assertSame(published,scene.publications().get(scene.owner.id));assertEquals(0,scene.viewer.count("private_remove"));}
        }
    }
    @Test void anEvictedDeleteSequenceOrPreviousHandshakeTokenCannotDeleteANewCopyOfTheSameContent() throws Exception {
        try(var scene=new Scene(temporary)) {
            byte[] bytes=bundle(1);String hash=PrivateModelBundle.hash(bytes),source="ysm:saved.ysm";
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);scene.hello(scene.owner,true);
            JsonObject original=scene.delete(scene.owner,UUID.randomUUID(),source,hash,bytes.length);scene.send(scene.owner,original);
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();assertFalse(Files.exists(scene.archive(scene.owner,hash)));
            scene.store.saveValidated(scene.owner.id,source,hash,"ysm",bytes);
            ((Map<?,?>)field(scene.session(scene.owner),"deletes")).clear();
            scene.send(scene.owner,original);
            assertEquals("private_delete_stale",scene.owner.last("upload_delete_failed").get("code").getAsString());
            assertTrue(scene.workers.isEmpty());assertTrue(Files.exists(scene.archive(scene.owner,hash)));
            Object rates=((Map<?,?>)field(scene.service,"rates")).get(scene.owner.id);
            set(field(rates,"hello"),"last",scene.now()-1_000_000_001L);scene.tick++;scene.hello(scene.owner,true);
            assertNotEquals(original.get("catalogToken").getAsString(),scene.owner.last("hello_ack").get("uploadCatalogToken").getAsString());
            scene.send(scene.owner,original);
            assertEquals("private_delete_stale",scene.owner.last("upload_delete_failed").get("code").getAsString());
            assertTrue(scene.workers.isEmpty());assertTrue(Files.exists(scene.archive(scene.owner,hash)));assertEquals(1,scene.store.listUploaded(scene.owner.id).size());
        }
    }

    private static final class Scene implements AutoCloseable {
        final Field bukkit=Bukkit.class.getDeclaredField("server");final Object previous;
        final Map<UUID,Person> people=new LinkedHashMap<>();final ConnectionLimits limits=new ConnectionLimits();
        final List<Runnable> workers=new ArrayList<>(),callbacks=new ArrayList<>();
        final Map<UUID,Long> deleteSequences=new HashMap<>();
        final Person owner=person(),viewer=person(),legacy=person();final PrivateModelStore store;final PrivateModelSyncService service;
        final Path directory;
        int tick;
        Scene(Path temporary) throws Exception {
            directory=temporary.resolve("private-models");store=new PrivateModelStore(directory,PrivateModelStore.Settings.defaults());
            BukkitTask task=proxy(BukkitTask.class,(instance,method,args)->method.getName().equals("cancel")?null:object(instance,method,args));
            BukkitScheduler scheduler=proxy(BukkitScheduler.class,(instance,method,args)->switch(method.getName()) {
                case "runTaskAsynchronously" -> {workers.add((Runnable)args[1]);yield task;}
                case "runTask" -> {callbacks.add((Runnable)args[1]);yield task;}
                default -> object(instance,method,args);
            });
            Server server=proxy(Server.class,(instance,method,args)->switch(method.getName()) {
                case "getCurrentTick" -> tick;case "isPrimaryThread" -> true;case "getScheduler" -> scheduler;
                case "getPlayer" -> {Person person=people.get(args[0]);yield person==null?null:person.player;}
                default -> object(instance,method,args);
            });
            Plugin plugin=proxy(Plugin.class,(instance,method,args)->switch(method.getName()) {
                case "getServer" -> server;case "isEnabled" -> true;case "getName" -> "UploadCatalogFixture";
                case "getLogger" -> java.util.logging.Logger.getLogger("UploadCatalogFixture");
                default -> object(instance,method,args);
            });
            bukkit.setAccessible(true);previous=bukkit.get(null);bukkit.set(null,server);
            try {
                service=new PrivateModelSyncService(plugin,Set::of,limits,this::now);
                service.configure(new PrivateModelSyncService.Policy(true,1024,PrivateModelBundle.MAX_BYTES,32L*1024*1024,64,10,"mact.private.upload","mact.private.view"));
                service.configurePerformance(PerformanceSettings.defaults());set(service,"running",true);set(service,"store",store);
            }catch(Exception|Error failure){bukkit.set(null,previous);throw failure;}
        }
        Person person(){Person person=new Person(new UUID(0,people.size()+1));people.put(person.id,person);return person;}
        void hello(Person person,boolean catalog) {
            JsonObject message=packet("hello");JsonArray capabilities=new JsonArray();capabilities.add(PrivateModelSyncService.CAPABILITY);
            if(catalog)capabilities.add(PrivateUploadCatalog.CAPABILITY);message.add("capabilities",capabilities);send(person,message);
        }
        void send(Person person,JsonObject packet){service.onPluginMessageReceived(PrivateModelSyncService.CHANNEL,person.player,packet.toString().getBytes(StandardCharsets.UTF_8));}
        JsonObject delete(Person person,UUID request,String modelId,String hash,int bytes) {
            JsonObject packet=packet("upload_delete"),ack=person.last("hello_ack");
            packet.addProperty("requestId",request.toString());packet.addProperty("modelId",modelId);packet.addProperty("hash",hash);
            packet.addProperty("kind","ysm");packet.addProperty("bytes",bytes);
            packet.addProperty("catalogToken",ack.has("uploadCatalogToken")?ack.get("uploadCatalogToken").getAsString():UUID.randomUUID().toString());
            packet.addProperty("requestSequence",deleteSequences.merge(person.id,1L,Long::sum));return packet;
        }
        void maintain() throws Exception{invoke(service,"maintain",new Class<?>[0]);}
        void pump() throws Exception{invoke(service,"pumpCatalogs",new Class<?>[]{long.class},(long)tick);}
        void update(Person person) throws Exception {Object session=session(person);invoke(service,"updateCatalog",new Class<?>[]{Player.class,session.getClass()},person.player,session);}
        Object session(Person person) throws Exception{return ((Map<?,?>)field(service,"sessions")).get(person.id);}
        @SuppressWarnings("unchecked") Map<UUID,Object> publications() throws Exception{return (Map<UUID,Object>)field(service,"publications");}
        void publication(Person person,byte[] bytes) throws Exception {
            Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Publication");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,UUID.class,String.class,String.class,byte[].class,JsonObject.class,long.class);constructor.setAccessible(true);
            publications().put(person.id,constructor.newInstance(person.id,UUID.randomUUID(),PrivateModelBundle.hash(bytes),"ysm",bytes,PrivateModelSyncService.appearance(new JsonObject()),(long)tick));
            set(service,"storedBytes",(long)bytes.length);
        }
        Path archive(Person owner,String hash){return directory.resolve(owner.id+"_"+hash+".zip");}
        Path metadata(Person owner,String hash){return directory.resolve(owner.id+"_"+hash+".meta.json");}
        @SuppressWarnings("unchecked") void observe(Person owner,Person viewer) throws Exception {
            Object publication=publications().get(owner.id);Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Offer");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,publication.getClass(),long.class);constructor.setAccessible(true);
            Object offer=constructor.newInstance(viewer.id,publication,(long)tick);
            Object ready=Arrays.stream(field(offer,"status").getClass().getEnumConstants()).filter(value->value.toString().equals("READY")).findFirst().orElseThrow();set(offer,"status",ready);
            ((Map<UUID,Object>)field(session(viewer),"offers")).put(owner.id,offer);
            ((Set<UUID>)field(publication,"offeredViewers")).add(viewer.id);
        }
        long now(){return 10_000_000_000L+tick*50_000_000L;}
        private Object ownerWindow() throws Exception{return field(((Map<?,?>)field(limits,"connections")).get(owner.id),"outbound");}
        void saturateOwnerWindow() throws Exception {
            Object window=ownerWindow();set(window,"startedAt",now()+60_000_000_000L);
            int remaining=ConnectionLimits.OUTBOUND_BYTES_PER_SECOND-((Number)field(window,"bytes")).intValue();
            while(remaining>0){tick++;int charge=Math.min(ConnectionLimits.GLOBAL_BYTES_PER_TICK,remaining);assertTrue(limits.allowOutbound(owner.id,charge,false,now(),tick));remaining-=charge;}
        }
        void resetOwnerWindow() throws Exception{set(ownerWindow(),"startedAt",now()-1_000_000_001L);}
        @Override public void close() throws Exception{bukkit.set(null,previous);}
    }
    private static final class Person {
        final UUID id;final Player player;final List<JsonObject> messages=new ArrayList<>();final List<byte[]> wire=new ArrayList<>();boolean upload=true;
        Person(UUID id) {
            this.id=id;player=proxy(Player.class,(instance,method,args)->switch(method.getName()) {
                case "getUniqueId" -> id;case "isOnline" -> true;case "isFlying" -> false;
                case "hasPermission" -> args[0].equals("mact.private.upload")?upload:args[0].equals("mact.private.view");
                case "sendPluginMessage" -> {byte[] bytes=((byte[])args[2]).clone();wire.add(bytes);messages.add(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject());yield null;}
                default -> object(instance,method,args);
            });
        }
        List<JsonObject> ofType(String type){return messages.stream().filter(value->value.get("type").getAsString().equals(type)).toList();}
        int count(String type){return ofType(type).size();}
        JsonObject last(String type){return ofType(type).getLast();}
    }
    private static JsonObject offer(String hash,int bytes,String modelId) {
        JsonObject packet=packet("upload_offer");packet.addProperty("generation",UUID.randomUUID().toString());packet.addProperty("hash",hash);
        packet.addProperty("bytes",bytes);packet.addProperty("kind","ysm");packet.addProperty("modelId",modelId);packet.add("appearance",new JsonObject());return packet;
    }
    private static byte[] bundle(int marker) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            Map<String,String> files=Map.of("manifest.json","{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}","ysm.json","{\"marker\":"+marker+"}");
            for(var file:files.entrySet()){ZipEntry entry=new ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        byte[] bytes=output.toByteArray();PrivateModelBundle.validate(bytes,"ysm");return bytes;
    }
    private static JsonObject packet(String type){JsonObject result=new JsonObject();result.addProperty("protocol",1);result.addProperty("type",type);return result;}
    private static Object field(Object target,String name) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
    private static Object object(Object instance,Method method,Object[] args) {
        return switch(method.getName()){case "equals"->instance==args[0];case "hashCode"->System.identityHashCode(instance);case "toString"->"UploadCatalogFixture";default->throw new AssertionError("Unexpected fixture call: "+method);};
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
