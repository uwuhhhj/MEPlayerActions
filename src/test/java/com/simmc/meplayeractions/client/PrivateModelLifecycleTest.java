package com.simmc.meplayeractions.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Actual relay messages/maintenance using public Bukkit interface proxies; no running server. */
class PrivateModelLifecycleTest {
    @Test void authoritativeFlightChangesReachOnlyAuthorizedReadyViewersWithoutReplayingAppearanceOrExtra() throws Exception {
        try(var scene=new Scene()) {
            scene.transition("READY");Object original=scene.offer();
            JsonObject initial=scene.viewer.last("private_state");assertFalse(initial.getAsJsonObject("state").get("flying").getAsBoolean());
            int sent=scene.viewer.count("private_state");long sequence=initial.get("sequence").getAsLong();
            scene.owner.flying=true;scene.advance(1);
            JsonObject flying=scene.viewer.last("private_state");assertTrue(flying.getAsJsonObject("state").get("flying").getAsBoolean());
            assertEquals(sent+1,scene.viewer.count("private_state"));assertEquals(sequence+1,flying.get("sequence").getAsLong());
            assertEquals(initial.get("appearance"),flying.get("appearance"));assertEquals(initial.get("extra"),flying.get("extra"));assertSame(original,scene.offer());
            scene.advance(1);assertEquals(sent+1,scene.viewer.count("private_state"));
            scene.owner.flying=false;scene.advance(1);
            JsonObject grounded=scene.viewer.last("private_state");assertFalse(grounded.getAsJsonObject("state").get("flying").getAsBoolean());
            assertEquals(sequence+2,grounded.get("sequence").getAsLong());assertEquals(sent+2,scene.viewer.count("private_state"));
            scene.viewer.allowed=false;scene.owner.flying=true;scene.advance(1);
            assertEquals(sent+2,scene.viewer.count("private_state"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"OFFERED","CACHED","QUEUED","SENDING","DELIVERED","READY"})
    void rendererRejectionRevokesEveryOfferPhaseAndWaitsForANewGeneration(String phase) throws Exception {
        try(var scene=new Scene()) {
            scene.transition(phase);assertEquals(phase,field(scene.offer(),"status").toString());
            int offers=scene.viewer.count("private_offer");UUID rejectedGeneration=scene.generation;
            scene.feedback("rejected");
            assertNull(scene.offer());assertEquals(rejectedGeneration,scene.rejected().get(scene.owner.id));
            JsonObject removed=scene.viewer.last("private_remove");
            assertEquals("asset_rejected",removed.get("reason").getAsString());
            assertEquals(scene.owner.id.toString(),removed.get("owner").getAsString());
            assertEquals(rejectedGeneration.toString(),removed.get("generation").getAsString());
            assertEquals(scene.hash,removed.get("hash").getAsString());
            for(int retry=0;retry<4;retry++)scene.advance(100);
            assertNull(scene.offer());assertEquals(offers,scene.viewer.count("private_offer"));
            // A SENDING rejection must release its transfer, just like any other revoked offer.
            assertTrue(scene.limits.reserveTransfer(scene.viewer.id));assertTrue(scene.limits.reserveTransfer(scene.viewer.id));
            assertFalse(scene.limits.reserveTransfer(scene.viewer.id));scene.limits.releaseTransfers(scene.viewer.id);
            scene.newGeneration();scene.advance(100);
            assertNotNull(scene.offer());assertFalse(scene.rejected().containsKey(scene.owner.id));
            assertEquals(offers+1,scene.viewer.count("private_offer"));
        }
    }
    @Test void feedbackRequiresAnExactStillAuthorizedTokenAndCannotRewindReadyToCachedOrMissing() throws Exception {
        try(var scene=new Scene()) {
            scene.transition("READY");Object ready=scene.offer();UUID token=(UUID)field(ready,"id");
            scene.feedback("cached");scene.feedback("missing");assertSame(ready,scene.offer());assertEquals("READY",field(ready,"status").toString());
            scene.feedback("rejected",UUID.randomUUID(),scene.hash);scene.feedback("rejected",token,"b".repeat(64));
            assertSame(ready,scene.offer());assertTrue(scene.rejected().isEmpty());
            scene.viewer.allowed=false;scene.feedback("rejected");assertSame(ready,scene.offer());assertTrue(scene.rejected().isEmpty());
            scene.viewer.allowed=true;scene.feedback("rejected");assertNull(scene.offer());assertEquals(scene.generation,scene.rejected().get(scene.owner.id));
        }
    }
    @Test void rejectionRecordsShareTheOfferBudgetWithoutEvictingAnUnchangedGeneration() throws Exception {
        try(var scene=new Scene()) {
            for(int i=0;i<63;i++)scene.rejected().put(new UUID(1,i+1),UUID.randomUUID());
            scene.feedback("rejected");assertEquals(64,scene.rejected().size());
            Person other=scene.person(4);other.tracking.add(scene.viewer.player);scene.publication(other,UUID.randomUUID());
            int offers=scene.viewer.count("private_offer");scene.advance(1);
            assertEquals(64,scene.rejected().size());assertNull(scene.offer());assertEquals(offers,scene.viewer.count("private_offer"));
            scene.newGeneration();scene.advance(100);
            assertEquals(63,scene.rejected().size());assertNotNull(scene.offer());assertEquals(64,scene.rejected().size()+scene.offers().size());
        }
    }
    private static final class Scene implements AutoCloseable {
        final Field bukkitServer=Bukkit.class.getDeclaredField("server");final Object previousServer;
        final Map<UUID,Person> people=new HashMap<>();final ConnectionLimits limits=new ConnectionLimits();
        final World world=proxy(World.class,(instance,method,args)->objectMethod(instance,method,args));
        final Person owner=person(0),viewer=person(1);
        final PrivateModelSyncService service;
        final byte[] bundle;final String hash;UUID generation=UUID.randomUUID();int tick=100;
        Scene() throws Exception {
            bundle=bundle();hash=PrivateModelBundle.hash(bundle);PrivateModelBundle.validate(bundle,"ysm");
            Server server=proxy(Server.class,(instance,method,args)->switch(method.getName()) {
                case "getCurrentTick" -> tick;case "isPrimaryThread" -> true;
                case "getPlayer" -> {Person person=people.get(args[0]);yield person==null?null:person.player;}
                default -> objectMethod(instance,method,args);
            });
            Plugin plugin=proxy(Plugin.class,(instance,method,args)->switch(method.getName()) {
                case "getServer" -> server;case "isEnabled" -> true;case "getName" -> "PrivateRelayFixture";
                default -> objectMethod(instance,method,args);
            });
            bukkitServer.setAccessible(true);previousServer=bukkitServer.get(null);bukkitServer.set(null,server);
            try {
                service=new PrivateModelSyncService(plugin,Set::of,limits,()->10_000_000_000L+tick*50_000_000L);
                service.configure(new PrivateModelSyncService.Policy(true,16000,8*1024*1024,32L*1024*1024,64,10,"mact.private.upload","mact.private.view"));
                setField(service,"running",true);owner.tracking.add(viewer.player);
                hello(owner);hello(viewer);publication(owner,generation);maintenance();assertNotNull(offer());
            } catch(Exception | Error failure) {bukkitServer.set(null,previousServer);throw failure;}
        }
        Person person(double x) {Person person=new Person(world,x);people.put(person.id,person);return person;}
        void hello(Person person) {
            JsonObject packet=packet("hello");JsonArray capabilities=new JsonArray();capabilities.add(PrivateModelSyncService.CAPABILITY);packet.add("capabilities",capabilities);send(person,packet);
        }
        void send(Person person,JsonObject packet) {service.onPluginMessageReceived(PrivateModelSyncService.CHANNEL,person.player,packet.toString().getBytes(StandardCharsets.UTF_8));}
        void publication(Person person,UUID generation) throws Exception {
            Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Publication");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,UUID.class,String.class,String.class,byte[].class,JsonObject.class,long.class);constructor.setAccessible(true);
            Object published=constructor.newInstance(person.id,generation,hash,"ysm",bundle,PrivateModelSyncService.appearance(new JsonObject()),(long)tick);
            publications().put(person.id,published);setField(service,"storedBytes",(long)publications().size()*bundle.length);
        }
        void newGeneration() throws Exception {
            invoke(service,"removePublication",new Class<?>[]{UUID.class,String.class},owner.id,"model_changed");
            generation=UUID.randomUUID();publication(owner,generation);
        }
        void advance(int ticks) throws Exception {
            tick+=ticks;JsonObject heartbeat=packet("private_heartbeat"),identity=new JsonObject();JsonArray bindings=new JsonArray();
            identity.addProperty("owner",owner.id.toString());identity.addProperty("generation",generation.toString());identity.addProperty("hash",hash);bindings.add(identity);heartbeat.add("bindings",bindings);
            send(owner,heartbeat);maintenance();
        }
        void maintenance() throws Exception {invoke(service,"maintain",new Class<?>[]{});}
        void feedback(String status) throws Exception {feedback(status,(UUID)field(offer(),"id"),hash);}
        void feedback(String status,UUID token,String hash) {
            JsonObject packet=packet("private_status");packet.addProperty("offerId",token.toString());packet.addProperty("hash",hash);packet.addProperty("status",status);send(viewer,packet);
        }
        void transition(String phase) throws Exception {
            if(phase.equals("OFFERED"))return;
            if(phase.equals("CACHED")||phase.equals("READY")) {
                feedback("cached");
                if(phase.equals("READY")) {
                    JsonObject ready=packet("private_ready");ready.addProperty("owner",owner.id.toString());ready.addProperty("generation",generation.toString());ready.addProperty("hash",hash);send(viewer,ready);
                }
            } else {
                feedback("missing");if(phase.equals("QUEUED"))return;
                viewer.dropAssetChunks=phase.equals("SENDING");advance(1);viewer.dropAssetChunks=false;
            }
        }
        Object session() throws Exception {return ((Map<?,?>)field(service,"sessions")).get(viewer.id);}
        @SuppressWarnings("unchecked") Map<UUID,Object> offers() throws Exception {return (Map<UUID,Object>)field(session(),"offers");}
        @SuppressWarnings("unchecked") Map<UUID,UUID> rejected() throws Exception {return (Map<UUID,UUID>)field(session(),"rejected");}
        @SuppressWarnings("unchecked") Map<UUID,Object> publications() throws Exception {return (Map<UUID,Object>)field(service,"publications");}
        Object offer() throws Exception {return offers().get(owner.id);}
        @Override public void close() throws Exception {bukkitServer.set(null,previousServer);}
    }
    private static final class Person {
        final UUID id=UUID.randomUUID();final Player player;final Set<Player> tracking=new HashSet<>();
        final List<JsonObject> messages=new ArrayList<>();boolean allowed=true,dropAssetChunks,flying;
        Person(World world,double x) {
            player=proxy(Player.class,(instance,method,args)->switch(method.getName()) {
                case "getUniqueId" -> id;case "isOnline" -> true;case "getWorld" -> world;case "getLocation" -> new Location(world,x,64,0);
                case "canSee" -> true;case "getTrackedBy" -> Set.copyOf(tracking);
                case "isFlying" -> flying;
                case "hasPermission" -> allowed && Set.of("mact.private.upload","mact.private.view").contains(args[0]);
                case "sendPluginMessage" -> {
                    JsonObject packet=JsonParser.parseString(new String((byte[])args[2],StandardCharsets.UTF_8)).getAsJsonObject();
                    if(dropAssetChunks&&packet.get("type").getAsString().equals("asset_chunk"))throw new IllegalStateException("fixture drops chunk");
                    messages.add(packet);yield null;
                }
                default -> objectMethod(instance,method,args);
            });
        }
        int count(String type) {return (int)messages.stream().filter(packet->packet.get("type").getAsString().equals(type)).count();}
        JsonObject last(String type) {return messages.stream().filter(packet->packet.get("type").getAsString().equals(type)).reduce((left,right)->right).orElseThrow();}
    }
    private static JsonObject packet(String type) {JsonObject packet=new JsonObject();packet.addProperty("protocol",1);packet.addProperty("type",type);return packet;}
    private static byte[] bundle() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            for(var file:Map.of("manifest.json","{\"format\":1,\"kind\":\"ysm\",\"entry\":\"ysm.json\"}","ysm.json","{}").entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static Object field(Object target,String name) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void setField(Object target,String name,Object value) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
    private static Object objectMethod(Object instance,Method method,Object[] args) {
        return switch(method.getName()) {
            case "equals" -> instance==args[0];case "hashCode" -> System.identityHashCode(instance);case "toString" -> "PrivateRelayFixture";
            default -> throw new AssertionError("Unexpected fixture call: "+method);
        };
    }
    private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
