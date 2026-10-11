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
    @ParameterizedTest @ValueSource(strings={"OFFERED","CACHED","QUEUED","SENDING","DELIVERED","READY"})
    void diagnosticsSeparateOwnPublicationFromReceivingOtherPlayersAndDescribeEachTransferPhase(String phase) throws Exception {
        try(var scene=new Scene()) {
            scene.transition(phase);Object original=scene.offer();int sent=scene.viewer.messages.size();
            String diagnostic=String.join("\n",scene.service.statusLines(scene.viewer.player));
            assertTrue(diagnostic.contains("本人发布：§f未发布"));assertTrue(diagnostic.contains("接收他人私人模型：§f1"));
            assertTrue(diagnostic.contains(scene.owner.name));assertTrue(diagnostic.contains("ysm:winefox"));
            assertTrue(diagnostic.contains(scene.hash));assertTrue(diagnostic.contains(scene.generation.toString()));
            String expected=switch(phase) {
                case "OFFERED" -> "等待客户端报告缓存情况";case "CACHED" -> "客户端报告已有缓存";
                case "QUEUED" -> "资源传送排队中";case "SENDING" -> "正在发送资源";
                case "DELIVERED" -> "资源已全部发送";case "READY" -> "已发送授权 ACK";default -> throw new AssertionError(phase);
            };
            assertTrue(diagnostic.contains(expected),diagnostic);assertTrue(diagnostic.contains("服务器无法直接确认"));
            assertSame(original,scene.offer());assertEquals(sent,scene.viewer.messages.size(),"diagnostic reads never send or grant resources");
            String ownerDiagnostic=String.join("\n",scene.service.statusLines(scene.owner.player));
            assertTrue(ownerDiagnostic.contains("本人发布：§a已验证并发布"));assertTrue(ownerDiagnostic.contains(scene.viewer.name));
            assertTrue(ownerDiagnostic.contains("接收他人私人模型：§f0"));
        }
    }
    @Test void diagnosticsRetainRejectionIdentityWithoutCallingItAnActiveCachedOrRenderedModel() throws Exception {
        try(var scene=new Scene()) {
            scene.transition("READY");scene.feedback("rejected");
            String diagnostic=String.join("\n",scene.service.statusLines(scene.viewer.player));
            assertTrue(diagnostic.contains("接收他人私人模型：§f0"));assertTrue(diagnostic.contains("客户端拒绝资源解析或渲染"));
            assertTrue(diagnostic.contains(scene.owner.name));assertTrue(diagnostic.contains(scene.hash));assertTrue(diagnostic.contains("原阶段 READY"));
            scene.newGeneration();scene.advance(100);
            assertFalse(String.join("\n",scene.service.statusLines(scene.viewer.player)).contains("客户端拒绝资源解析或渲染"));
        }
    }
    @Test void recentFailureDiagnosticsAreBoundedAndExpireWithoutRetainingModelBundlesOrScanningPlayers() throws Exception {
        try(var scene=new Scene()) {
            for(int index=0;index<12;index++) {
                Person person=scene.person(index+2);scene.publication(person,UUID.randomUUID());
                Object publication=scene.publications().get(person.id);
                Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Offer");
                Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,publication.getClass(),long.class);constructor.setAccessible(true);
                Object offer=constructor.newInstance(scene.viewer.id,publication,(long)scene.tick);scene.offers().put(person.id,offer);
                invoke(scene.service,"removeOffer",new Class<?>[]{Player.class,scene.session().getClass(),type,String.class},scene.viewer.player,scene.session(),offer,"asset_rejected");
            }
            Map<?,?> history=(Map<?,?>)field(scene.session(),"observationFailures");assertEquals(8,history.size());
            assertEquals(8,scene.service.statusLines(scene.viewer.player).stream().filter(line->line.contains("最近停止接收")).count());
            for(Object failure:history.values())for(var component:failure.getClass().getRecordComponents())
                assertTrue(Set.of(UUID.class,String.class,long.class).contains(component.getType()),"diagnostics retain only bounded scalar identities");
            scene.tick+=1200;
            assertTrue(scene.service.statusLines(scene.viewer.player).stream().noneMatch(line->line.contains("最近停止接收")));
            assertEquals(8,history.size(),"expiry does not start a background cleanup or unbounded history");
        }
    }
    @Test void negotiatedViewerPauseRevokesOnlyIncomingAuthorizationAndResumeCanReceiveTheSameGeneration() throws Exception {
        try(var scene=new Scene()) {
            scene.hello(scene.viewer,true);scene.advance(100);scene.transition("READY");
            Object original=scene.offer();UUID offerId=(UUID)field(original,"id"),generation=scene.generation;
            UUID ownGeneration=UUID.randomUUID();scene.publication(scene.viewer,ownGeneration);Object own=scene.publications().get(scene.viewer.id);
            scene.viewHeartbeat(false,ownGeneration);
            assertNull(scene.offer());assertSame(own,scene.publications().get(scene.viewer.id));assertTrue(scene.rejected().isEmpty());
            assertEquals((long)scene.tick,field(own,"lease"));
            assertFalse(scene.viewer.last("heartbeat").get("viewEnabled").getAsBoolean());
            assertEquals("viewer_paused",scene.viewer.last("private_remove").get("reason").getAsString());
            assertTrue(String.join("\n",scene.service.statusLines(scene.viewer.player)).contains("客户端已暂停"));
            int grants=scene.viewer.count("private_ack");scene.sendReady(scene.viewer,generation);scene.feedback("cached",offerId,scene.hash);
            assertEquals(grants,scene.viewer.count("private_ack"));assertNull(scene.offer());
            scene.advance(100);assertNull(scene.offer());
            scene.viewHeartbeat(true,ownGeneration);scene.advance(100);
            assertNotNull(scene.offer());assertEquals(generation,field(field(scene.offer(),"publication"),"generation"));
            assertSame(own,scene.publications().get(scene.viewer.id));scene.transition("READY");assertEquals(grants+1,scene.viewer.count("private_ack"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"OFFERED","CACHED","QUEUED","SENDING","DELIVERED"})
    void repeatedlyPausingBeforeReadyDoesNotConsumeTheThreeActualFailureAttempts(String phase) throws Exception {
        try(var scene=new Scene()) {
            scene.hello(scene.viewer,true);scene.advance(100);
            for(int cycle=0;cycle<4;cycle++) {
                assertNotNull(scene.offer(),"same publication can resume at cycle "+cycle);scene.transition(phase);
                assertEquals(phase,field(scene.offer(),"status").toString());
                scene.viewHeartbeat(false,null);assertNull(scene.offer());assertTrue(scene.rejected().isEmpty());
                scene.advance(100);scene.viewHeartbeat(true,null);scene.advance(100);
                assertNotNull(scene.offer(),"intentional pause must not exhaust retry budget at cycle "+cycle);
            }
            scene.transition("READY");assertEquals(1,scene.viewer.count("private_ack"));
        }
    }
    @Test void pausingAfterAnActualTimeoutDoesNotRefundFailuresOrResetTheHelloFailureBudget() throws Exception {
        try(var scene=new Scene()) {
            // Replacing the original legacy handshake already consumed one non-pause attempt.
            scene.hello(scene.viewer,true);scene.advance(100);
            for(int failure=0;failure<2;failure++) {
                assertNotNull(scene.offer());scene.advance(101);assertNull(scene.offer());
                assertEquals("offer_expired",scene.viewer.last("private_remove").get("reason").getAsString());
                scene.viewHeartbeat(false,null);scene.viewHeartbeat(true,null);scene.advance(100);
            }
            assertNull(scene.offer(),"one replaced handshake plus two real timeouts exhaust the three-attempt budget");
            for(int retry=0;retry<4;retry++) {scene.viewHeartbeat(false,null);scene.viewHeartbeat(true,null);scene.advance(100);assertNull(scene.offer());}
        }
    }
    @Test void pausingAfterAClientRejectionNeverClearsItsGenerationLock() throws Exception {
        try(var scene=new Scene()) {
            scene.hello(scene.viewer,true);scene.advance(100);scene.feedback("rejected");
            for(int retry=0;retry<4;retry++) {scene.viewHeartbeat(false,null);scene.viewHeartbeat(true,null);scene.advance(100);assertNull(scene.offer());}
            assertEquals(scene.generation,scene.rejected().get(scene.owner.id));
        }
    }
    @Test void enablingPrivateViewCannotBypassPermissionsAndLegacyClientsCannotControlItWithoutNegotiation() throws Exception {
        try(var scene=new Scene()) {
            JsonObject forbidden=packet("private_heartbeat");forbidden.add("bindings",new JsonArray());forbidden.addProperty("viewEnabled",false);scene.send(scene.viewer,forbidden);
            assertNotNull(scene.offer());assertEquals("invalid_private_payload",scene.viewer.last("error").get("code").getAsString());
            scene.hello(scene.viewer,false);scene.advance(100);assertNull(scene.offer());
            scene.viewer.allowed=false;scene.viewHeartbeat(true,null);scene.advance(100);assertNull(scene.offer());
            scene.viewer.allowed=true;scene.advance(100);assertNotNull(scene.offer());
        }
    }
    @Test void negotiatedInitialPauseAndOfferMetadataDoNotChangeLegacyDownloadOrPublishingRules() throws Exception {
        try(var scene=new Scene()) {
            assertFalse(scene.viewer.last("private_offer").has("modelId"));
            scene.hello(scene.viewer,false);JsonObject ack=scene.viewer.last("hello_ack");
            assertFalse(ack.get("viewEnabled").getAsBoolean());assertTrue(ack.getAsJsonArray("capabilities").asList().stream().anyMatch(value->value.getAsString().equals(PrivateModelSyncService.VIEW_STATE)));
            scene.advance(100);assertNull(scene.offer());assertNotNull(scene.publications().get(scene.owner.id));
            scene.viewHeartbeat(true,null);scene.advance(100);
            assertEquals("ysm:winefox",scene.viewer.last("private_offer").get("modelId").getAsString());
        }
    }
    @Test void aViewCapableClientWithoutAnInitialIntentCannotAccidentallyReceivePrivateModels() throws Exception {
        try(var scene=new Scene()) {
            scene.tick+=20;JsonObject hello=packet("hello");JsonArray capabilities=new JsonArray();
            capabilities.add(PrivateModelSyncService.CAPABILITY);capabilities.add(PrivateModelSyncService.VIEW_STATE);hello.add("capabilities",capabilities);scene.send(scene.viewer,hello);
            assertFalse(scene.viewer.last("hello_ack").get("viewEnabled").getAsBoolean());scene.advance(100);assertNull(scene.offer());
            scene.viewHeartbeat(true,null);scene.advance(100);assertNotNull(scene.offer());
        }
    }
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
        void hello(Person person,boolean enabled) {
            tick+=20;
            JsonObject packet=packet("hello");JsonArray capabilities=new JsonArray();capabilities.add(PrivateModelSyncService.CAPABILITY);capabilities.add(PrivateModelSyncService.VIEW_STATE);
            packet.add("capabilities",capabilities);packet.addProperty("viewEnabled",enabled);send(person,packet);
        }
        void viewHeartbeat(boolean enabled,UUID ownGeneration) {
            JsonObject heartbeat=packet("private_heartbeat");heartbeat.addProperty("viewEnabled",enabled);JsonArray bindings=new JsonArray();
            if(ownGeneration!=null) {JsonObject own=new JsonObject();own.addProperty("owner",viewer.id.toString());own.addProperty("generation",ownGeneration.toString());own.addProperty("hash",hash);bindings.add(own);}
            heartbeat.add("bindings",bindings);send(viewer,heartbeat);
        }
        void sendReady(Person person,UUID generation) {
            JsonObject ready=packet("private_ready");ready.addProperty("owner",owner.id.toString());ready.addProperty("generation",generation.toString());ready.addProperty("hash",hash);send(person,ready);
        }
        void send(Person person,JsonObject packet) {service.onPluginMessageReceived(PrivateModelSyncService.CHANNEL,person.player,packet.toString().getBytes(StandardCharsets.UTF_8));}
        void publication(Person person,UUID generation) throws Exception {
            Class<?> type=Class.forName(PrivateModelSyncService.class.getName()+"$Publication");
            Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,UUID.class,String.class,String.class,byte[].class,JsonObject.class,long.class);constructor.setAccessible(true);
            Object published=constructor.newInstance(person.id,generation,hash,"ysm",bundle,PrivateModelSyncService.appearance(new JsonObject()),(long)tick);
            setField(published,"sourceId","ysm:winefox");
            publications().put(person.id,published);setField(service,"storedBytes",(long)publications().size()*bundle.length);
        }
        void newGeneration() throws Exception {
            invoke(service,"removePublication",new Class<?>[]{UUID.class,String.class},owner.id,"model_changed");
            generation=UUID.randomUUID();publication(owner,generation);
        }
        void advance(int ticks) throws Exception {
            tick+=ticks;JsonObject heartbeat=packet("private_heartbeat"),identity=new JsonObject();JsonArray bindings=new JsonArray();
            identity.addProperty("owner",owner.id.toString());identity.addProperty("generation",generation.toString());identity.addProperty("hash",hash);bindings.add(identity);heartbeat.add("bindings",bindings);
            send(owner,heartbeat);
            Object own=publications().get(viewer.id);
            if(own!=null) {
                JsonObject viewerHeartbeat=packet("private_heartbeat"),ownIdentity=new JsonObject();JsonArray ownBindings=new JsonArray();
                ownIdentity.addProperty("owner",viewer.id.toString());ownIdentity.addProperty("generation",field(own,"generation").toString());ownIdentity.addProperty("hash",hash);
                ownBindings.add(ownIdentity);viewerHeartbeat.add("bindings",ownBindings);send(viewer,viewerHeartbeat);
            }
            maintenance();
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
                    sendReady(viewer,generation);
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
        final UUID id=UUID.randomUUID();final String name;final Player player;final Set<Player> tracking=new HashSet<>();
        final List<JsonObject> messages=new ArrayList<>();boolean allowed=true,dropAssetChunks,flying;
        Person(World world,double x) {
            name=x==0?"Loliiiico":x==1?"zztyx_":"Viewer"+(int)x;
            player=proxy(Player.class,(instance,method,args)->switch(method.getName()) {
                case "getUniqueId" -> id;case "isOnline" -> true;case "getWorld" -> world;case "getLocation" -> new Location(world,x,64,0);
                case "getName" -> name;
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
