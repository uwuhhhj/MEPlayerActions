package com.simmc.meplayeractions.client;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerModelCatalogTest {
    @TempDir Path temporary;

    @Test void registryReadsAreSharedForFiveSecondsAndUnchangedContentsKeepTheSamePlan() {
        AtomicInteger reads = new AtomicInteger();
        var catalog = new ServerModelCatalog(() -> { reads.incrementAndGet(); return List.of("ysm_02_jk", "ysm_01_jk"); });
        catalog.refresh(100); var first = catalog.plan(true, 1024);
        for (int viewer = 0; viewer < 1000; viewer++) catalog.refresh(199);
        assertEquals(1, reads.get()); catalog.refresh(200); assertEquals(2, reads.get());
        assertSame(first, catalog.plan(true, 1024));
        assertEquals(List.of("ysm_01_jk", "ysm_02_jk"), ids(first));
    }

    @Test void choicesCannotInjectCommandsOrCarryAssetLocationsAndDeniedCatalogIsEmpty() {
        var catalog = new ServerModelCatalog(() -> Arrays.asList("valid", "valid", null, "../hidden", "other/model",
                "valid;op", "space id", "valid\nop", "UPPER", "a".repeat(65), "valid-two_2"));
        catalog.refresh(100); var plan = catalog.plan(true, 1024);
        assertEquals(List.of("valid", "valid-two_2"), ids(plan));
        JsonObject message = decode(plan.packet(0, 1));
        assertEquals(Set.of("id", "label"), message.getAsJsonArray("models").get(0).getAsJsonObject().keySet());
        JsonObject denied = decode(catalog.plan(false, 1024).packet(0, 2));
        assertFalse(denied.get("canDisguise").getAsBoolean()); assertFalse(denied.get("truncated").getAsBoolean());
        assertTrue(denied.getAsJsonArray("models").isEmpty());
    }

    @Test void longIdsFragmentWithinTheSmallestPayloadAndOverLimitCatalogReportsTruncation() {
        var names = IntStream.range(0, 4100).mapToObj(id -> "m" + "x".repeat(59) + String.format("%04d", id)).toList();
        var catalog = new ServerModelCatalog(() -> names); catalog.refresh(100);
        for (int budget : List.of(1024, 16000)) {
            var plan = catalog.plan(true, budget); assertTrue(plan.count() > 1);
            var seen = new LinkedHashSet<String>();
            for (int index = 0; index < plan.count(); index++) {
                byte[] bytes = plan.packet(index, Long.MAX_VALUE); assertTrue(bytes.length <= budget);
                JsonObject packet = decode(bytes); assertEquals(index, packet.get("index").getAsInt());
                assertEquals(plan.count(), packet.get("count").getAsInt()); assertEquals(Long.MAX_VALUE, packet.get("revision").getAsLong());
                assertTrue(packet.get("truncated").getAsBoolean());
                for (JsonElement entry : packet.getAsJsonArray("models")) assertTrue(seen.add(entry.getAsJsonObject().get("id").getAsString()));
            }
            assertEquals(4096, seen.size()); assertFalse(seen.contains(names.getLast()));
        }
        assertTrue(catalog.status().contains("4096 上限"));
    }

    @Test void shortIdsAtTheMaximumPayloadRespectThe512EntryDecoderLimitAndReassembleCompletely() {
        List<String> names = IntStream.range(0, 1700).mapToObj(id -> "m" + id).toList();
        var catalog = new ServerModelCatalog(() -> names); catalog.refresh(100);
        for (int budget : List.of(1024, 16000, 32766)) {
            var plan = catalog.plan(true, budget); var reassembled = new ArrayList<String>();
            int largestChunk = 0;
            for (int index = 0; index < plan.count(); index++) {
                byte[] bytes = plan.packet(index, Long.MAX_VALUE); assertTrue(bytes.length <= budget);
                JsonObject packet = decode(bytes); JsonArray chunk = packet.getAsJsonArray("models");
                assertEquals(index, packet.get("index").getAsInt()); assertEquals(plan.count(), packet.get("count").getAsInt());
                assertTrue(chunk.size() <= 512); assertFalse(chunk.isEmpty());
                largestChunk = Math.max(largestChunk, chunk.size());
                for (JsonElement entry : chunk) reassembled.add(entry.getAsJsonObject().get("id").getAsString());
            }
            assertEquals(new ArrayList<>(new TreeSet<>(names)), reassembled);
            if (budget == 32766) assertEquals(512, largestChunk);
        }
    }

    @Test void onlyNegotiatedSessionsReceiveChoicesAndBrowsingCannotAuthorizeAnAssetDownload() throws Exception {
        try (Scene scene = new Scene(temporary)) {
            Person legacy = scene.person(true); scene.hello(legacy, false);
            assertEquals(0, scene.reads); assertEquals(0, legacy.count(ServerModelCatalog.CAPABILITY));
            Person viewer = scene.person(true); scene.hello(viewer, true);
            assertEquals(1, scene.reads); assertEquals(1, viewer.count(ServerModelCatalog.CAPABILITY));
            assertEquals(1, viewer.last(ServerModelCatalog.CAPABILITY).get("revision").getAsLong());
            assertTrue(viewer.last("hello_ack").getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(ServerModelCatalog.CAPABILITY)));
            JsonObject request = new JsonObject(); request.addProperty("modelId", "ysm_01_jk"); request.addProperty("hash", "a".repeat(64));
            scene.send(viewer, "asset_request", request);
            assertEquals("asset_not_authorized", viewer.last("error").get("code").getAsString());
            assertEquals(0, scene.assetPreparations.get()); assertEquals(0, viewer.count("asset_begin"));
        }
    }

    @Test void stableCatalogDoesNotRepeatAndPermissionChangesSendANewerExplicitClear() throws Exception {
        try (Scene scene = new Scene(temporary)) {
            Person viewer = scene.person(true); scene.hello(viewer, true); viewer.messages.clear();
            for (int i = 0; i < 99; i++) scene.advance();
            assertEquals(0, viewer.count(ServerModelCatalog.CAPABILITY)); assertEquals(1, scene.reads);
            for (int i = 0; i < 40; i++) scene.advance();
            assertEquals(2, scene.reads); assertEquals(0, viewer.count(ServerModelCatalog.CAPABILITY));
            viewer.permitted = false;
            for (int i = 0; i < 40; i++) scene.advance();
            JsonObject denied = viewer.last(ServerModelCatalog.CAPABILITY);
            assertEquals(2, denied.get("revision").getAsLong()); assertFalse(denied.get("canDisguise").getAsBoolean());
            assertTrue(denied.getAsJsonArray("models").isEmpty());
            viewer.permitted = true;
            for (int i = 0; i < 40; i++) scene.advance();
            assertEquals(3, viewer.last(ServerModelCatalog.CAPABILITY).get("revision").getAsLong());
            assertTrue(viewer.last(ServerModelCatalog.CAPABILITY).get("canDisguise").getAsBoolean());
        }
    }

    @Test void discoveryPublishesChangedRegistryAndCancelsPartiallySentChoicesOnPermissionRevocation() throws Exception {
        try (Scene scene = new Scene(temporary)) {
            scene.service.configure(true, 1024, 4, 64);
            scene.choices = IntStream.range(0, 100).mapToObj(id -> "model_" + id).toList();
            Person viewer = scene.person(true); scene.hello(viewer, true);
            assertTrue(viewer.last(ServerModelCatalog.CAPABILITY).get("count").getAsInt() > 1);
            viewer.messages.clear(); viewer.permitted = false; scene.advance();
            assertEquals(1, viewer.count(ServerModelCatalog.CAPABILITY));
            JsonObject clear = viewer.last(ServerModelCatalog.CAPABILITY);
            assertEquals(0, clear.get("index").getAsInt()); assertEquals(1, clear.get("count").getAsInt());
            assertEquals(2, clear.get("revision").getAsLong()); assertTrue(clear.getAsJsonArray("models").isEmpty());
            viewer.permitted = true; scene.choices = List.of("replacement");
            for (int i = 0; i < 140; i++) scene.advance();
            JsonArray choices = viewer.last(ServerModelCatalog.CAPABILITY).getAsJsonArray("models");
            assertEquals(1, choices.size()); assertEquals("replacement", choices.get(0).getAsJsonObject().get("id").getAsString());
        }
    }

    @Test void budgetDeferralRetainsTheSameCatalogBytesWithoutFillingTheCriticalQueue() throws Exception {
        try (Scene scene = new Scene(temporary)) {
            scene.service.configure(true, 1024, 4, 64);
            scene.choices = IntStream.range(0, 100).mapToObj(id -> "model_" + id).toList();
            Person viewer = scene.person(true); scene.hello(viewer, true); viewer.messages.clear();
            scene.tick++;
            // Roll the real global budget to this tick before measuring its remaining bytes.
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            assertTrue(limits.allowOutbound(new UUID(3, 98), 1, false, System.nanoTime(), scene.tick));
            scene.fillBudget(); scene.maintain(); Object session = scene.session(viewer);
            byte[] deferred = (byte[]) field(session, "pendingCatalogBytes"); assertNotNull(deferred);
            scene.maintain(); assertSame(deferred, field(session, "pendingCatalogBytes"));
            assertEquals(0, ((DeferredClientPackets) field(session, "controls")).size());
            assertEquals(0, viewer.count(ServerModelCatalog.CAPABILITY)); scene.advance();
            assertEquals(decode(deferred), viewer.last(ServerModelCatalog.CAPABILITY));
        }
    }

    @Test void deferredHelloMustPrecedeTheCatalogAndItsCallbackCannotSendTwoChunksInOneTick() throws Exception {
        try (Scene scene = new Scene(temporary)) {
            scene.service.configure(true, 1024, 4, 64);
            scene.choices = IntStream.range(0, 100).mapToObj(id -> "model_" + id).toList();
            Person viewer = scene.person(true); scene.fillBudget(); scene.hello(viewer, true);
            assertEquals(0, viewer.count("hello_ack")); assertEquals(0, viewer.count(ServerModelCatalog.CAPABILITY));
            scene.maintain(); assertEquals(0, viewer.count(ServerModelCatalog.CAPABILITY));
            scene.advance();
            assertEquals("hello_ack", viewer.messages.getFirst().get("type").getAsString());
            assertEquals(1, viewer.count(ServerModelCatalog.CAPABILITY));
            assertEquals(0, viewer.last(ServerModelCatalog.CAPABILITY).get("index").getAsInt());
            scene.maintain(); assertEquals(1, viewer.count(ServerModelCatalog.CAPABILITY));
            scene.advance(); assertEquals(2, viewer.count(ServerModelCatalog.CAPABILITY));
            assertEquals(1, viewer.last(ServerModelCatalog.CAPABILITY).get("index").getAsInt());
        }
    }

    private static List<String> ids(ServerModelCatalog.Plan plan) {
        var ids = new ArrayList<String>();
        for (int index = 0; index < plan.count(); index++)
            for (JsonElement entry : decode(plan.packet(index, 1)).getAsJsonArray("models")) ids.add(entry.getAsJsonObject().get("id").getAsString());
        return ids;
    }
    private static JsonObject decode(byte[] bytes) { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }

    private static final class Scene implements AutoCloseable {
        final Field serverField; final Object originalServer;
        final Map<UUID, Person> people = new HashMap<>(); final ClientSyncService service;
        final AtomicInteger assetPreparations = new AtomicInteger();
        List<String> choices = List.of("ysm_01_jk", "ysm_02_jk"); int tick = 100, reads, nextId = 1;
        Scene(Path temporary) throws Exception {
            Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
                case "getCurrentTick" -> tick; case "isPrimaryThread" -> true;
                case "getPlayer" -> { Person person = people.get(args[0]); yield person == null ? null : person.player; }
                default -> objectMethod(instance, method, args);
            });
            Plugin plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
                case "getServer" -> server; case "isEnabled" -> true; case "getName" -> "CatalogFixture";
                case "getLogger" -> Logger.getLogger("CatalogFixture"); default -> objectMethod(instance, method, args);
            });
            serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
            originalServer = serverField.get(null); serverField.set(null, server);
            try {
                var assets = new ModelAssets(temporary, null, name -> { throw new AssertionError("Catalog must not read model resources"); },
                        task -> { assetPreparations.incrementAndGet(); throw new AssertionError("Catalog must not prepare assets"); }, ignored -> {});
                service = new ClientSyncService(plugin, List::of, ignored -> {}, assets);
                service.modelCatalog(() -> { reads++; return choices; }); setField(service, "running", true);
            } catch (Exception | Error failure) { serverField.set(null, originalServer); throw failure; }
        }
        Person person(boolean permitted) { Person person = new Person(new UUID(0, nextId++), permitted); people.put(person.id, person); return person; }
        void hello(Person viewer, boolean catalog) {
            JsonObject fields = new JsonObject(); JsonArray capabilities = new JsonArray(); capabilities.add("local_render"); capabilities.add("incremental_state");
            if (catalog) capabilities.add(ServerModelCatalog.CAPABILITY); fields.add("capabilities", capabilities); send(viewer, "hello", fields);
        }
        void send(Person viewer, String type, JsonObject fields) {
            JsonObject packet = new JsonObject(); packet.addProperty("protocol", 3); packet.addProperty("type", type);
            fields.entrySet().forEach(entry -> packet.add(entry.getKey(), entry.getValue()));
            service.onPluginMessageReceived(ClientSyncService.CHANNEL, viewer.player, packet.toString().getBytes(StandardCharsets.UTF_8));
        }
        void maintain() throws Exception { Method method = ClientSyncService.class.getDeclaredMethod("maintainSessions"); method.setAccessible(true); method.invoke(service); }
        void advance() throws Exception { tick++; maintain(); }
        Object session(Person viewer) throws Exception { return ((Map<?, ?>) field(service, "sessions")).get(viewer.id); }
        void fillBudget() throws Exception {
            ConnectionLimits limits = (ConnectionLimits) field(service, "limits");
            assertTrue(limits.allowOutbound(new UUID(3, 99), ConnectionLimits.GLOBAL_BYTES_PER_TICK - (int) field(limits, "globalBytes"), false, System.nanoTime(), tick));
        }
        @Override public void close() throws Exception { serverField.set(null, originalServer); }
    }
    private static final class Person {
        final UUID id; final Player player; final List<JsonObject> messages = new ArrayList<>(); boolean permitted;
        Person(UUID id, boolean permitted) {
            this.id = id; this.permitted = permitted;
            player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id; case "isOnline" -> true; case "hasPermission" -> this.permitted;
                case "sendPluginMessage" -> { messages.add(decode((byte[]) args[2])); yield null; }
                default -> objectMethod(instance, method, args);
            });
        }
        int count(String type) { return (int) messages.stream().filter(value -> value.get("type").getAsString().equals(type)).count(); }
        JsonObject last(String type) { return messages.stream().filter(value -> value.get("type").getAsString().equals(type)).reduce((first, second) -> second).orElseThrow(); }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static Object objectMethod(Object instance, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> instance == args[0]; case "hashCode" -> System.identityHashCode(instance); case "toString" -> "fixture";
            default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : method.getReturnType() == long.class ? 0L : null;
        };
    }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void setField(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
}
