package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.config.PerformanceSettings;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Targeted cadence/authorization checks using the actual relay, without a running server. */
class PrivateModelPerformanceTest {
    @Test void nearestAudienceIsCachedAndRefreshesInFortyDistinctPublisherPhases() {
        PrivateAudienceCache cache=new PrivateAudienceCache();UUID generation=new UUID(1,1),viewer=new UUID(2,2);
        AtomicInteger discoveries=new AtomicInteger();
        for(int owner=0;owner<40;owner++)assertTrue(cache.refreshIfDue(new UUID(0,owner),generation,0,()->{discoveries.incrementAndGet();return Set.of(viewer);}));
        assertEquals(40,discoveries.get());
        for(int tick=1;tick<=40;tick++) {
            int before=discoveries.get();
            for(int owner=0;owner<40;owner++)cache.refreshIfDue(new UUID(0,owner),generation,tick,()->{discoveries.incrementAndGet();return Set.of(viewer);});
            assertEquals(1,discoveries.get()-before,"Exactly one publisher refreshes in this phase");
        }
        assertThrows(UnsupportedOperationException.class,()->cache.viewers(new UUID(0,0),generation).clear());
    }
    @Test void audienceInvalidationIsGenerationScopedAndDisconnectDoesNotRediscoverUnrelatedOwners() {
        PrivateAudienceCache cache=new PrivateAudienceCache();UUID owner=new UUID(0,0),other=new UUID(0,40),generation=new UUID(1,1);
        UUID viewer=new UUID(2,2),unrelated=new UUID(2,3);
        cache.refreshIfDue(owner,generation,0,()->Set.of(viewer));
        cache.refreshIfDue(other,generation,0,()->Set.of(unrelated));
        cache.removeViewer(viewer);assertFalse(cache.contains(owner,generation,viewer));assertTrue(cache.contains(other,generation,unrelated));
        assertFalse(cache.refreshIfDue(other,generation,20,()->{fail("Unrelated audience should remain cached");return Set.of();}));
        cache.invalidate(owner,generation,20);assertFalse(cache.refreshIfDue(owner,generation,39,()->{fail("Invalidated publisher keeps its refresh phase");return Set.of();}));
        assertTrue(cache.refreshIfDue(owner,generation,40,()->Set.of(viewer)));
        UUID replacement=new UUID(1,2);assertTrue(cache.refreshIfDue(owner,replacement,41,()->Set.of(unrelated)));
        assertTrue(cache.viewers(owner,generation).isEmpty());assertTrue(cache.contains(owner,replacement,unrelated));
        cache.remove(owner);assertTrue(cache.viewers(owner,replacement).isEmpty());
    }
    @Test void audienceCadenceRemainsBoundedAcrossTheUnsignedServerTickWrapAndConfigurationChanges() {
        PrivateAudienceCache cache=new PrivateAudienceCache();UUID owner=new UUID(0,0),generation=new UUID(1,1);
        cache.refreshIfDue(owner,generation,0xfffffff0L,Set::of);
        assertFalse(cache.refreshIfDue(owner,generation,23,()->{fail("Only 39 ticks elapsed");return Set.of();}));
        assertTrue(cache.refreshIfDue(owner,generation,24,Set::of));
        cache.configure(100);assertTrue(cache.refreshIfDue(owner,generation,25,Set::of));
        assertFalse(cache.refreshIfDue(owner,generation,99,()->{fail("Configured phase is tick 100");return Set.of();}));
        assertTrue(cache.refreshIfDue(owner,generation,100,Set::of));
    }
    @Test void disabledMaintenanceNeverRequestsTheBackendOwnersEvenWhenDeniedClientsHaveNegotiated() throws Exception {
        try(var scene=new Scene(false,10)) {
            scene.maintain();assertEquals(0,scene.snapshotReads);
            scene.hello(scene.owner);scene.hello(scene.viewer);
            for(int tick=0;tick<=100;tick++){scene.tick=tick;scene.maintain();}
            assertEquals(0,scene.snapshotReads);assertTrue(scene.publications().isEmpty());
        }
    }
    @Test void nearestSortingRunsOncePerPublisherWhileStateEventAndHeartbeatRemainImmediate() throws Exception {
        try(var scene=new Scene(true,1)) {
            scene.start();
            for(int index=2;index<=20;index++){Person candidate=scene.person(index);scene.hello(candidate);scene.owner.tracked.add(candidate.player);}
            scene.ready();int iterations=scene.owner.discoveryIterations;
            for(int tick=1;tick<40;tick++){scene.tick=tick;scene.maintain();}
            assertEquals(iterations,scene.owner.discoveryIterations);
            int states=scene.viewer.count("private_state"),events=scene.viewer.count("private_event");
            scene.send(scene.owner,scene.state());scene.send(scene.owner,scene.event());scene.heartbeat(scene.viewer);
            assertEquals(states+1,scene.viewer.count("private_state"));assertEquals(events+1,scene.viewer.count("private_event"));
            assertEquals(iterations,scene.owner.discoveryIterations,"Immediate protocol paths must not sort a nearest audience");
            scene.tick=40;scene.maintain();assertEquals(iterations+1,scene.owner.discoveryIterations);
            assertEquals(1,scene.offers(scene.viewer).size());
            for(Person candidate:scene.people.values())if(candidate!=scene.owner&&candidate!=scene.viewer)assertEquals(0,candidate.count("private_offer"));
        }
    }
    @Test void activeTransfersPumpBetweenValidationTicksAndRecheckPermissionBeforeEveryChunk() throws Exception {
        try(var scene=new Scene(true,10)) {
            scene.start();scene.feedback("missing");
            scene.tick=1;scene.maintain();assertEquals(2,scene.viewer.count("asset_chunk"));
            int iterations=scene.owner.discoveryIterations;
            scene.tick=2;scene.maintain();assertEquals(4,scene.viewer.count("asset_chunk"));assertEquals(iterations,scene.owner.discoveryIterations);
            scene.viewer.onChunk=()->scene.viewer.allowed=false;
            scene.tick=3;scene.maintain();assertEquals(5,scene.viewer.count("asset_chunk"));
            assertNull(scene.offer());assertTrue(((Set<?>)field(scene.service,"activeTransfers")).isEmpty());
            assertTrue(scene.limits.reserveTransfer(scene.viewer.id));assertTrue(scene.limits.reserveTransfer(scene.viewer.id));
            assertFalse(scene.limits.reserveTransfer(scene.viewer.id));scene.limits.releaseTransfers(scene.viewer.id);
        }
    }
    @Test void observationChangesRevokeBothViewerAndPublisherRelationshipsWithoutRediscoveryOrDeletingTheAsset() throws Exception {
        try(var scene=new Scene(true,10)) {
            scene.start();scene.ready();Person second=scene.person(3);scene.hello(second);scene.owner.tracked.add(second.player);
            scene.tick=40;scene.maintain();assertNotNull(scene.offers(second).get(scene.owner.id));
            int iterations=scene.owner.discoveryIterations;
            scene.service.observationChanged(scene.viewer.player);assertNull(scene.offer());assertNotNull(scene.offers(second).get(scene.owner.id));
            scene.service.observationChanged(scene.owner.player);assertTrue(scene.offers(second).isEmpty());
            assertEquals(iterations,scene.owner.discoveryIterations);assertTrue(scene.publications().containsKey(scene.owner.id));
            scene.tick=41;scene.maintain();assertEquals(iterations,scene.owner.discoveryIterations);
        }
    }
    @Test void theWholeBundleHashIsDeferredIntoTheAsyncValidationJob() throws Exception {
        try(var scene=new Scene(true,10)) {
            scene.hello(scene.owner);scene.upload(scene.owner,"b".repeat(64));
            assertEquals(1,scene.workers.size());assertEquals(0,scene.owner.count("error"));
            assertEquals(1,((Number)field(scene.service,"uploads")).intValue());
            scene.workers.removeFirst().run();assertEquals(0,scene.owner.count("error"));
            scene.callbacks.removeFirst().run();assertEquals("private_upload_integrity",scene.owner.last("error").get("code").getAsString());
            assertEquals(0,((Number)field(scene.service,"uploads")).intValue());assertEquals(0,((Number)field(scene.service,"reservedBytes")).longValue());
        }
    }
    @Test void cancelledAsyncValidationsKeepTheTwoJobAndGlobalExpandedMemoryReservations() throws Exception {
        try(var scene=new Scene(true,10)) {
            scene.hello(scene.owner);scene.hello(scene.viewer);
            scene.upload(scene.owner,scene.hash);scene.send(scene.owner,packet("clear"));scene.upload(scene.viewer,scene.hash);
            long reservation=2L*(scene.bundle.length+PrivateModelBundle.MAX_BYTES);
            assertEquals(2,((Number)field(scene.service,"uploads")).intValue());assertEquals(reservation,((Number)field(scene.service,"reservedBytes")).longValue());
            Person third=scene.person(3);scene.hello(third);scene.uploadOffer(third,scene.hash);
            assertEquals("private_upload_busy",third.last("error").get("code").getAsString());assertEquals(2,scene.workers.size());
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();
            assertEquals(1,((Number)field(scene.service,"uploads")).intValue());
            scene.workers.removeFirst().run();scene.callbacks.removeFirst().run();
            assertEquals(0,((Number)field(scene.service,"uploads")).intValue());assertEquals(0,((Number)field(scene.service,"reservedBytes")).longValue());
            assertFalse(scene.publications().containsKey(scene.owner.id));assertTrue(scene.publications().containsKey(scene.viewer.id));
        }
    }

    private static final class Scene implements AutoCloseable {
        final Field bukkitServer=Bukkit.class.getDeclaredField("server");final Object previousServer;
        final Map<UUID,Person> people=new LinkedHashMap<>();final ConnectionLimits limits=new ConnectionLimits();
        final Set<UUID> disguised=new HashSet<>();final List<Runnable> workers=new ArrayList<>(),callbacks=new ArrayList<>();
        final World world=proxy(World.class,PrivateModelPerformanceTest::objectMethod);
        final Person owner=person(0),viewer=person(1);final PrivateModelSyncService service;
        final byte[] bundle;final String hash;final UUID generation=new UUID(10,10);
        int tick,snapshotReads;
        Scene(boolean enabled,int maxViewers) throws Exception {
            bundle=bundle();hash=PrivateModelBundle.hash(bundle);
            BukkitTask task=proxy(BukkitTask.class,(instance,method,args)->method.getName().equals("cancel")?null:objectMethod(instance,method,args));
            BukkitScheduler scheduler=proxy(BukkitScheduler.class,(instance,method,args)->switch(method.getName()) {
                case "runTaskAsynchronously" -> {workers.add((Runnable)args[1]);yield task;}
                case "runTask" -> {callbacks.add((Runnable)args[1]);yield task;}
                default -> objectMethod(instance,method,args);
            });
            Server server=proxy(Server.class,(instance,method,args)->switch(method.getName()) {
                case "getCurrentTick" -> tick;case "isPrimaryThread" -> true;case "getScheduler" -> scheduler;
                case "getPlayer" -> {Person person=people.get(args[0]);yield person==null?null:person.player;}
                default -> objectMethod(instance,method,args);
            });
            Plugin plugin=proxy(Plugin.class,(instance,method,args)->switch(method.getName()) {
                case "getServer" -> server;case "isEnabled" -> true;case "getName" -> "PrivatePerformanceFixture";
                default -> objectMethod(instance,method,args);
            });
            bukkitServer.setAccessible(true);previousServer=bukkitServer.get(null);bukkitServer.set(null,server);
            try {
                service=new PrivateModelSyncService(plugin,()->{snapshotReads++;return Set.copyOf(disguised);},limits);
                service.configure(new PrivateModelSyncService.Policy(enabled,16000,8*1024*1024,32L*1024*1024,64,maxViewers,"mact.private.upload","mact.private.view"));
                service.configurePerformance(PerformanceSettings.defaults());setField(service,"running",true);owner.tracked.add(viewer.player);
            }catch(Exception|Error failure){bukkitServer.set(null,previousServer);throw failure;}
        }
        Person person(double x){Person person=new Person(new UUID(0,people.size()),world,x);people.put(person.id,person);return person;}
        void start() throws Exception {
            hello(owner);hello(viewer);
            Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Publication");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,UUID.class,String.class,String.class,byte[].class,JsonObject.class,long.class);constructor.setAccessible(true);
            publications().put(owner.id,constructor.newInstance(owner.id,generation,hash,"ysm",bundle,PrivateModelSyncService.appearance(new JsonObject()),(long)tick));
            setField(service,"storedBytes",(long)bundle.length);maintain();assertNotNull(offer());
        }
        void hello(Person person){JsonObject hello=packet("hello");JsonArray caps=new JsonArray();caps.add(PrivateModelSyncService.CAPABILITY);hello.add("capabilities",caps);send(person,hello);}
        void send(Person person,JsonObject value){service.onPluginMessageReceived(PrivateModelSyncService.CHANNEL,person.player,value.toString().getBytes(StandardCharsets.UTF_8));}
        void maintain() throws Exception {invoke(service,"maintain");}
        void feedback(String status) throws Exception {JsonObject value=packet("private_status");value.addProperty("offerId",field(offer(),"id").toString());value.addProperty("hash",hash);value.addProperty("status",status);send(viewer,value);}
        void ready() throws Exception {feedback("cached");JsonObject ready=identity("private_ready");ready.addProperty("owner",owner.id.toString());send(viewer,ready);}
        void heartbeat(Person person){JsonObject heartbeat=packet("private_heartbeat");JsonArray identities=new JsonArray();JsonObject entry=identity(null);entry.addProperty("owner",owner.id.toString());identities.add(entry);heartbeat.add("bindings",identities);send(person,heartbeat);}
        JsonObject state(){JsonObject state=identity("private_state");state.add("appearance",new JsonObject());JsonObject extra=new JsonObject();extra.addProperty("id","wave");extra.addProperty("loop","ONCE");extra.addProperty("locked",false);extra.addProperty("sequence",1);state.add("extra",extra);return state;}
        JsonObject event(){JsonObject event=identity("private_event");JsonArray args=new JsonArray();args.add(1);event.add("args",args);return event;}
        JsonObject identity(String type){JsonObject result=type==null?new JsonObject():packet(type);result.addProperty("generation",generation.toString());result.addProperty("hash",hash);return result;}
        void uploadOffer(Person person,String advertisedHash){JsonObject value=packet("upload_offer");value.addProperty("generation",UUID.randomUUID().toString());value.addProperty("hash",advertisedHash);value.addProperty("bytes",bundle.length);value.addProperty("kind","ysm");value.add("appearance",new JsonObject());send(person,value);}
        void upload(Person person,String advertisedHash) {
            uploadOffer(person,advertisedHash);String id=person.last("upload_accept").get("uploadId").getAsString();int chunk=person.last("upload_accept").get("chunkBytes").getAsInt();
            for(int start=0,index=0;start<bundle.length;start+=chunk,index++){JsonObject value=packet("upload_chunk");value.addProperty("uploadId",id);value.addProperty("index",index);value.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bundle,start,Math.min(start+chunk,bundle.length))));send(person,value);}
            JsonObject end=packet("upload_end");end.addProperty("uploadId",id);send(person,end);
        }
        @SuppressWarnings("unchecked") Map<UUID,Object> publications() throws Exception{return (Map<UUID,Object>)field(service,"publications");}
        @SuppressWarnings("unchecked") Map<UUID,Object> offers(Person person) throws Exception {Object session=((Map<?,?>)field(service,"sessions")).get(person.id);return (Map<UUID,Object>)field(session,"offers");}
        Object offer() throws Exception{return offers(viewer).get(owner.id);}
        @Override public void close() throws Exception{bukkitServer.set(null,previousServer);}
    }
    private static final class Person {
        final UUID id;final Player player;final Set<Player> tracked=new HashSet<>();final List<JsonObject> messages=new ArrayList<>();
        boolean allowed=true;int discoveryIterations;Runnable onChunk;
        Person(UUID id,World world,double x) {
            this.id=id;
            Set<Player> trackingView=new AbstractSet<>() {
                @Override public Iterator<Player> iterator(){discoveryIterations++;return tracked.iterator();}
                @Override public int size(){return tracked.size();}
                @Override public boolean contains(Object value){return tracked.contains(value);}
            };
            player=proxy(Player.class,(instance,method,args)->switch(method.getName()) {
                case "getUniqueId" -> id;case "isOnline" -> true;case "getWorld" -> world;case "getLocation" -> new Location(world,x,64,0);
                case "canSee" -> true;case "getTrackedBy" -> trackingView;
                case "hasPermission" -> allowed && Set.of("mact.private.upload","mact.private.view").contains(args[0]);
                case "sendPluginMessage" -> {
                    JsonObject value=JsonParser.parseString(new String((byte[])args[2],StandardCharsets.UTF_8)).getAsJsonObject();messages.add(value);
                    if(value.get("type").getAsString().equals("asset_chunk")&&onChunk!=null)onChunk.run();yield null;
                }
                default -> objectMethod(instance,method,args);
            });
        }
        int count(String type){return (int)messages.stream().filter(value->value.get("type").getAsString().equals(type)).count();}
        JsonObject last(String type){return messages.stream().filter(value->value.get("type").getAsString().equals(type)).reduce((left,right)->right).orElseThrow();}
    }
    private static byte[] bundle() throws Exception {
        byte[] sound=new byte[50*1024];new Random(17).nextBytes(sound);System.arraycopy(new byte[]{'O','g','g','S'},0,sound,0,4);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) {
            Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json","{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}".getBytes(StandardCharsets.UTF_8));files.put("ysm.json","{}".getBytes(StandardCharsets.UTF_8));files.put("sounds/tone.ogg",sound);
            for(var file:files.entrySet()){ZipEntry entry=new ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}
        }
        return out.toByteArray();
    }
    private static JsonObject packet(String type){JsonObject value=new JsonObject();value.addProperty("protocol",1);value.addProperty("type",type);return value;}
    private static Object field(Object target,String name) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void setField(Object target,String name,Object value) throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Object target,String name) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);
        try{method.invoke(target);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
    private static Object objectMethod(Object instance,Method method,Object[] args){return switch(method.getName()){case "equals"->instance==args[0];case "hashCode"->System.identityHashCode(instance);case "toString"->"PrivatePerformanceFixture";default->throw new AssertionError("Unexpected fixture call: "+method);};}
    private static <T> T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
