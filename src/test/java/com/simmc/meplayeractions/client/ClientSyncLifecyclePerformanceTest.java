package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.action.DisguiseOptions;
import com.simmc.meplayeractions.protection.ResourceError;
import com.simmc.meplayeractions.protection.ResourceSettings;
import com.simmc.meplayeractions.protection.ResourceProtection;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

class ClientSyncLifecyclePerformanceTest {
    @TempDir
    Path temporary;

    @Test
    void actualInvisibilityChangesReachOwnerAndViewerWithoutRestartingTheDisguise() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            owner.localRenderable = true;
            Files.writeString(this.temporary.resolve("fixture.bbmodel"),
                    "{\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}");
            scene.assets.get("fixture");
            assertTrue(scene.assets.get("fixture").isPresent());
            scene.hello(owner, true, false);
            scene.hello(viewer, true, false);
            for (Person recipient : List.of(owner, viewer)) {
                assertFalse(recipient.last("state").get("sourceInvisible").getAsBoolean());
                recipient.messages.clear();
            }
            owner.invisible = true;
            scene.tick += 20;
            scene.maintain();
            for (Person recipient : List.of(owner, viewer)) {
                JsonObject state = recipient.last("state");
                assertTrue(state.get("sourceInvisible").getAsBoolean());
                assertEquals(owner.instance.toString(), state.get("instance").getAsString());
                assertEquals(owner.sequence, state.get("sequence").getAsLong());
                assertEquals(0, recipient.count("unbind"));
                recipient.messages.clear();
            }
            scene.tick += 20;
            scene.maintain();
            assertEquals(0, owner.count("state"));
            assertEquals(0, viewer.count("state"));
            owner.invisible = false;
            scene.tick += 20;
            scene.maintain();
            for (Person recipient : List.of(owner, viewer)) {
                assertFalse(recipient.last("state").get("sourceInvisible").getAsBoolean());
                assertEquals(0, recipient.count("unbind"));
            }
        }
    }

    @Test
    void preparationCompletionPublishesResourceSourceWithoutCreatingATakeoverLease() throws Exception {
        ArrayDeque<Runnable> work = new ArrayDeque<Runnable>();
        try (Scene scene = new Scene(this.temporary, work::add)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            owner.localRenderable = true;
            Files.writeString(this.temporary.resolve("fixture.bbmodel"),
                    "{\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}");
            AtomicInteger renderCalls = new AtomicInteger();
            scene.service.rendering((watcher, id, instance, enabled) -> {
                renderCalls.incrementAndGet();
                return true;
            });
            scene.hello(viewer, true, false);
            JsonObject preparing = viewer.last("state");
            assertEquals("pending", preparing.get("assetStatus").getAsString());
            assertEquals("own", preparing.get("assetSource").getAsString());
            assertEquals("", preparing.get("assetHash").getAsString());
            assertEquals(1, preparing.getAsJsonArray("animations").size());
            assertEquals(1, work.size());
            work.remove().run();
            viewer.messages.clear();
            scene.tick += 20;
            scene.maintain();
            JsonObject available = viewer.last("state");
            assertEquals(owner.instance.toString(), available.get("instance").getAsString());
            assertEquals(owner.sequence, available.get("sequence").getAsLong());
            assertEquals("ready", available.get("assetStatus").getAsString());
            assertEquals("own", available.get("assetSource").getAsString());
            assertEquals(scene.assets.get("fixture").orElseThrow().hash(), available.get("assetHash").getAsString());
            assertEquals(0, renderCalls.get());
            assertEquals(0, viewer.count("render_ack"));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
            assertEquals(1, ((Map<?, ?>) field(session, "bindings")).size());
            assertTrue(work.isEmpty());
        }
    }

    @Test
    void missingResourcesKeepServerOwnershipAndActionsWithoutClaimingLocalTakeover() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            owner.localRenderable = true;
            AtomicInteger renderCalls = new AtomicInteger();
            scene.service.rendering((watcher, id, instance, enabled) -> {
                renderCalls.incrementAndGet();
                return true;
            });
            scene.hello(viewer, true, false);
            JsonObject state = viewer.last("state");
            assertEquals(owner.instance.toString(), state.get("instance").getAsString());
            assertEquals("fixture", state.get("modelId").getAsString());
            assertEquals("missing", state.get("assetStatus").getAsString());
            assertEquals("own", state.get("assetSource").getAsString());
            assertEquals("", state.get("assetHash").getAsString());
            assertTrue(state.get("assetReason").getAsString().contains("未提供此模型"));
            assertEquals("idle", state.getAsJsonArray("animations").get(0).getAsJsonObject().get("id").getAsString());
            assertEquals(0, viewer.count("render_ack"));
            assertEquals(0, viewer.count("asset_offer"));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
            assertEquals(1, ((Map<?, ?>) field(session, "bindings")).size());
            JsonObject staleCache = new JsonObject();
            staleCache.addProperty("owner", owner.id.toString());
            staleCache.addProperty("instance", owner.instance.toString());
            staleCache.addProperty("hash", "a".repeat(64));
            scene.send(viewer, "render_ready", staleCache);
            assertEquals("render_not_authorized", viewer.last("error").get("code").getAsString());
            assertEquals(0, renderCalls.get());
            assertEquals(0, viewer.count("render_ack"));
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
        }
    }

    @Test
    void invalidOwnResourceReportsItsActualSourceWithoutClaimingAMissingFileOrTakeover() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            owner.localRenderable = true;
            Files.writeString(this.temporary.resolve("fixture.bbmodel"), "{}");
            scene.hello(viewer, true, false);
            JsonObject state = viewer.last("state");
            assertEquals("invalid", state.get("assetStatus").getAsString());
            assertEquals("own", state.get("assetSource").getAsString());
            assertEquals("", state.get("assetHash").getAsString());
            assertTrue(state.get("assetReason").getAsString().contains("无效或无法读取"));
            assertEquals(1, state.getAsJsonArray("animations").size());
            assertEquals(0, viewer.count("render_ack"));
            assertEquals(0, viewer.count("asset_offer"));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
        }
    }

    @Test
    void hiddenSelfStillReceivesOwnershipStateAndResultsWithoutObtainingARenderLease() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), other = scene.person(false);
            owner.showSelf = false;
            scene.local(owner);
            scene.service.audience((viewer, id) -> false);
            AtomicInteger renderCalls = new AtomicInteger();
            scene.service.rendering((viewer, id, instance, enabled) -> {
                renderCalls.incrementAndGet();
                return true;
            });
            scene.hello(owner, true, false, true);
            scene.hello(other, true, false, true);
            assertEquals(1, owner.count("state"));
            assertEquals(0, other.count("state"));
            assertEquals(owner.instance.toString(), owner.last("state").get("instance").getAsString());
            assertFalse(owner.last("state").get("showSelf").getAsBoolean());
            ClientSyncService.DisguiseRequest request = scene.service.beginDisguiseRequest(owner.player,
                    UUID.randomUUID(), "fixture", "show-self=false", 100);
            scene.service.disguiseSucceeded(owner.player, request, owner.instance,
                    new DisguiseOptions("fixture", 1, true, 0, false, 8, 10, List.of()));
            assertEquals(owner.last("state").get("instance"), owner.last("disguise_result").get("instance"));
            scene.ready(owner, owner);
            assertEquals(0, renderCalls.get());
            assertEquals(0, owner.count("render_ack"));
            assertEquals("render_not_authorized", owner.last("error").get("code").getAsString());
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(owner.id);
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
            scene.tick += 100;
            scene.maintain();
            assertEquals(0, owner.count("unbind"));
            assertEquals(0, other.count("state"));
        }
    }

    @Test
    void disguiseResultsConfirmActualSevenOptionsAndTheUnchangedIdempotentInstance() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), other = scene.person(false);
            scene.hello(owner, true, false, true);
            scene.hello(other, true, false, true);
            owner.messages.clear();
            other.messages.clear();
            DisguiseOptions options = new DisguiseOptions("fixture", 2, false, 3, false, 12, 0,
                    List.of(new DisguiseOptions.Effect("slowness", 1, 0)));
            UUID first = UUID.randomUUID();
            ClientSyncService.DisguiseRequest request = scene.service.beginDisguiseRequest(owner.player, first,
                    "fixture", "same-options", 180);
            assertTrue(request.execute());
            scene.service.disguiseSucceeded(owner.player, request, owner.instance, options);
            JsonObject result = owner.last("disguise_result");
            assertTrue(result.get("success").getAsBoolean());
            assertEquals(first.toString(), result.get("requestId").getAsString());
            assertEquals(owner.instance.toString(), result.get("instance").getAsString());
            JsonObject actual = result.getAsJsonObject("options");
            assertEquals(Set.of("scale", "hideSelf", "showSelf", "viewDistance", "maxViewers", "delay", "effect"),
                    actual.keySet());
            assertEquals(2, actual.get("scale").getAsDouble());
            assertFalse(actual.get("hideSelf").getAsBoolean());
            assertFalse(actual.get("showSelf").getAsBoolean());
            assertEquals(12, actual.get("viewDistance").getAsDouble());
            assertEquals(0, actual.get("maxViewers").getAsInt());
            assertEquals(3, actual.get("delay").getAsInt());
            assertEquals("slowness:1", actual.get("effect").getAsString());
            assertEquals(0, other.count("disguise_result"));
            scene.tick += 4;
            UUID second = UUID.randomUUID();
            ClientSyncService.DisguiseRequest idempotent = scene.service.beginDisguiseRequest(owner.player, second,
                    "fixture", "same-options", 180);
            assertTrue(idempotent.execute());
            scene.service.disguiseSucceeded(owner.player, idempotent, owner.instance, options);
            assertEquals(owner.instance.toString(), owner.last("disguise_result").get("instance").getAsString());
            assertEquals(second.toString(), owner.last("disguise_result").get("requestId").getAsString());
        }
    }

    @Test
    void disguiseFailureKeepsItsNonceAndDoesNotInventSuccessOrAppliedOptions() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            UUID nonce = UUID.randomUUID();
            ClientSyncService.DisguiseRequest request = scene.service.beginDisguiseRequest(owner.player, nonce,
                    "fixture", "request", 100);
            scene.service.disguiseFailure(owner.player, request, "permission_denied",
                    "缺少权限：mact.disguise\n" + "🦊".repeat(80));
            JsonObject result = owner.last("disguise_result");
            assertFalse(result.get("success").getAsBoolean());
            assertEquals("permission_denied", result.get("code").getAsString());
            assertEquals(nonce.toString(), result.get("requestId").getAsString());
            assertFalse(result.has("instance"));
            assertFalse(result.has("options"));
            assertEquals("disguise", result.get("stage").getAsString());
            assertFalse(result.get("retryable").getAsBoolean());
            String text = result.get("message").getAsString();
            assertTrue(text.codePointCount(0, text.length()) <= 64);
            assertFalse(text.contains("\n"));
        }
    }

    @Test
    void protectionFailureIncludesStableStageRetryDelayAndOriginalRequestIdentity() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            UUID nonce = UUID.randomUUID();
            ClientSyncService.DisguiseRequest request = scene.service.beginDisguiseRequest(owner.player, nonce,
                    "fixture", "options", 100);
            scene.service.disguiseFailure(owner.player, request,
                    new ResourceError("tps_protection", "disguise", true, 600), "服务器负载保护，请稍后再试");
            JsonObject result = owner.last("disguise_result");
            assertFalse(result.get("success").getAsBoolean());
            assertEquals(nonce.toString(), result.get("requestId").getAsString());
            assertEquals("tps_protection", result.get("code").getAsString());
            assertEquals("disguise", result.get("stage").getAsString());
            assertTrue(result.get("retryable").getAsBoolean());
            assertEquals(30, result.get("retryAfter").getAsInt());
            assertFalse(result.has("instance"));
        }
    }

    @Test
    void snapshotEndWaitsForDeferredStateAndReleasesEveryQueuedByte() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false);
            owner.messages.clear();
            scene.tick++;
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            UUID filler = new UUID(5, 6);
            assertTrue(limits.canOutbound(filler, 1, false, System.nanoTime(), scene.tick));
            assertTrue(limits.allowOutbound(filler, ConnectionLimits.GLOBAL_BYTES_PER_TICK - 128, false,
                    System.nanoTime(), scene.tick));
            scene.service.sendSnapshot(owner.player);
            assertEquals(1, owner.count("snapshot_begin"));
            assertEquals(0, owner.count("state"));
            assertEquals(0, owner.count("snapshot_end"));
            assertTrue(limits.metric("queuedBytes") > 0);
            scene.tick++;
            scene.maintain();
            assertEquals(1, owner.count("state"));
            assertEquals(1, owner.count("snapshot_end"));
            assertEquals(0, limits.metric("queuedBytes"));
            int stateIndex = -1, endIndex = -1;
            for (int i = 0; i < owner.messages.size(); i++) {
                String type = owner.messages.get(i).get("type").getAsString();
                if (type.equals("state")) {
                    stateIndex = i;
                }
                if (type.equals("snapshot_end")) {
                    endIndex = i;
                }

            }
            assertTrue(stateIndex >= 0 && endIndex > stateIndex);
        }
    }

    @Test
    void repeatedNonceReplaysOriginalResultAndCannotReapplyEffectsOrDifferentArguments() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            UUID nonce = UUID.randomUUID();
            DisguiseOptions options = new DisguiseOptions("fixture", 1, true, 0, true, 8, 10,
                    List.of(new DisguiseOptions.Effect("slowness", 2, 60)));
            ClientSyncService.DisguiseRequest original = scene.service.beginDisguiseRequest(owner.player, nonce,
                    "fixture", "effect=slowness:2:60", 100);
            assertTrue(original.execute());
            scene.service.disguiseSucceeded(owner.player, original, owner.instance, options);
            owner.messages.clear();
            ClientSyncService.DisguiseRequest replay = scene.service.beginDisguiseRequest(owner.player, nonce,
                    "fixture", "effect=slowness:2:60", 100);
            assertFalse(replay.execute());
            assertTrue(owner.last("disguise_result").get("success").getAsBoolean());
            assertEquals("slowness:2:60",
                    owner.last("disguise_result").getAsJsonObject("options").get("effect").getAsString());
            ClientSyncService.DisguiseRequest conflict = scene.service.beginDisguiseRequest(owner.player, nonce,
                    "fixture", "scale=8", 100);
            assertFalse(conflict.execute());
            assertEquals("request_id_reused", owner.last("disguise_result").get("code").getAsString());
            assertFalse(scene.service.beginDisguiseRequest(owner.player, nonce, "fixture", "effect=slowness:2:60", 100)
                    .execute());
            assertTrue(owner.last("disguise_result").get("success").getAsBoolean());
        }
    }

    @Test
    void busyDisguiseResultUsesDeferredBudgetAndAnEndedConnectionCannotPublishIt() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            owner.messages.clear();
            ClientSyncService.DisguiseRequest first = scene.service.beginDisguiseRequest(owner.player,
                    UUID.randomUUID(), "fixture", "first", 100);
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            scene.fillBudget(limits, new UUID(2, 95));
            scene.service.disguiseSucceeded(owner.player, first, owner.instance,
                    DisguiseOptions.defaults("fixture", 2, true, 0));
            assertEquals(0, owner.count("disguise_result"));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(owner.id);
            assertEquals(1, ((DeferredClientPackets) field(session, "controls")).size());
            scene.tick++;
            scene.maintain();
            assertEquals(1, owner.count("disguise_result"));
            scene.tick += 4;
            ClientSyncService.DisguiseRequest stale = scene.service.beginDisguiseRequest(owner.player,
                    UUID.randomUUID(), "fixture", "old-session", 100);
            scene.fillBudget(limits, new UUID(2, 96));
            scene.service.disguiseFailure(owner.player, stale, "action_failed", "old connection");
            scene.service.forget(owner.player);
            scene.tick++;
            owner.messages.clear();
            scene.hello(owner, true, false, true);
            scene.service.disguiseSucceeded(owner.player, stale, owner.instance,
                    DisguiseOptions.defaults("fixture", 1, true, 0));
            scene.maintain();
            assertEquals(0, owner.count("disguise_result"));
        }
    }

    @Test
    void optionalDisguiseResultsRequireTheAcknowledgedCapabilityAndRemainBounded() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person plain = scene.person(true);
            scene.hello(plain, true, false);
            assertNull(scene.service.beginDisguiseRequest(plain.player, UUID.randomUUID(), "fixture", "plain", 100));
            assertFalse(plain.last("hello_ack").getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(ClientSyncService.DISGUISE_RESULTS)));
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            assertTrue(owner.last("hello_ack").getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals(ClientSyncService.DISGUISE_RESULTS)));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(owner.id);
            setField(session, "helloAcknowledged", false);
            assertNull(scene.service.beginDisguiseRequest(owner.player, UUID.randomUUID(), "fixture", "unacked", 100));
            setField(session, "helloAcknowledged", true);
            for (int i = 0; i < 40; i++) {
                scene.tick += 4;
                ClientSyncService.DisguiseRequest request = scene.service.beginDisguiseRequest(owner.player,
                        UUID.randomUUID(), "fixture", "request" + i, 100);
                assertTrue(request.execute());
                scene.service.disguiseFailure(owner.player, request, "invalid_options", "fixture");
            }
            assertEquals(32, ((Map<?, ?>) field(session, "disguiseReceipts")).size());
        }
    }

    @Test
    void nonceCommandBudgetDenialNeverExecutesAndStillReportsCorrelatedFailure() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true);
            scene.hello(owner, true, false, true);
            ClientSyncService.DisguiseRequest first = scene.service.beginDisguiseRequest(owner.player,
                    UUID.randomUUID(), "fixture", "first", 100);
            assertTrue(first.execute());
            UUID second = UUID.randomUUID();
            ClientSyncService.DisguiseRequest denied = scene.service.beginDisguiseRequest(owner.player, second,
                    "fixture", "second", 100);
            assertFalse(denied.execute());
            assertEquals(second.toString(), owner.last("disguise_result").get("requestId").getAsString());
            assertEquals("request_cooldown", owner.last("disguise_result").get("code").getAsString());
            assertFalse(owner.last("disguise_result").get("success").getAsBoolean());
        }
    }

    @Test
    void incrementalHelloIsImmediateAndUnchangedModelsOnlyReceiveIdentityHeartbeats() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), first = scene.person(false), second = scene.person(false);
            scene.hello(first, true, false);
            scene.hello(second, true, false);
            assertEquals(1, first.count("state"));
            assertEquals(1, second.count("state"));
            assertEquals(1, scene.singleReads);
            assertTrue(first.last("hello_ack").getAsJsonArray("capabilities").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("incremental_state")));
            first.messages.clear();
            second.messages.clear();
            for (int i = 0; i < 81; i++) {
                owner.x += .1;
                scene.tick++;
                scene.maintain();
            }
            assertEquals(0, first.count("state"));
            assertEquals(0, second.count("state"));
            assertTrue(first.count("heartbeat") >= 4);
            for (JsonObject packet : first.messages) {
                if (packet.get("type").getAsString().equals("heartbeat")) {
                    JsonObject binding = packet.getAsJsonArray("bindings").get(0).getAsJsonObject();
                    assertEquals(owner.id.toString(), binding.get("owner").getAsString());
                    assertEquals(owner.instance.toString(), binding.get("instance").getAsString());
                    assertEquals("", binding.get("hash").getAsString());
                }
            }

        }
    }

    @Test
    void legacyClientReceivesFullStateEveryTwentyTicksAndNoIdentityExtension() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.person(true);
            Person viewer = scene.person(false);
            scene.hello(viewer, false, false);
            viewer.messages.clear();
            for (int i = 0; i < 19; i++) {
                scene.tick++;
                scene.maintain();
            }
            assertEquals(0, viewer.count("state"));
            scene.tick++;
            scene.maintain();
            assertEquals(1, viewer.count("state"));
            assertTrue(viewer.last("state").has("animations"));
            for (JsonObject packet : viewer.messages) {
                if (packet.get("type").getAsString().equals("heartbeat")) {
                    assertFalse(packet.has("bindings"));
                }

            }

        }
    }

    @Test
    void timelineNegotiationKeepsTwoTickMotionAndChangedMenusOnlyAppearOnce() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, true);
            viewer.messages.clear();
            for (int i = 0; i < 10; i++) {
                scene.tick++;
                owner.x++;
                scene.maintain();
            }
            assertEquals(5, viewer.count("state"));
            assertEquals(owner.x, viewer.last("state").get("x").getAsDouble());
            assertFalse(viewer.last("state").has("animations"));
            owner.animations = List.of(new ClientSyncService.AnimationInfo("extra", "Changed"));
            owner.sequence++;
            scene.service.broadcast(scene.snapshot(owner.id));
            assertEquals("extra",
                    viewer.last("state").getAsJsonArray("animations").get(0).getAsJsonObject().get("id").getAsString());
            owner.sequence++;
            scene.service.broadcast(scene.snapshot(owner.id));
            assertFalse(viewer.last("state").has("animations"));
        }
    }

    @Test
    void broadcastUsesExistingSubscribersAndNewObserversWaitForDiscovery() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            Person added = scene.person(true);
            viewer.messages.clear();
            scene.service.broadcast(scene.snapshot(added.id));
            assertEquals(0, viewer.count("state"));
            scene.tick += 40;
            scene.maintain();
            assertEquals(added.id.toString(), viewer.last("state").get("owner").getAsString());
            scene.service.unbind(owner.id, owner.instance, "fixture");
            viewer.messages.clear();
            scene.service.broadcast(scene.snapshot(owner.id));
            assertEquals(0, viewer.count("state"));
            assertFalse(((Map<?, ?>) field(scene.service, "viewersByOwner")).containsKey(owner.id));
        }
    }

    @Test
    void budgetDeferralKeepsBindingAndQueuesUnbindUntilNextTick() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            viewer.messages.clear();
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            assertTrue(limits.allowOutbound(new UUID(2, 9),
                    ConnectionLimits.GLOBAL_BYTES_PER_TICK - (int) field(limits, "globalBytes"), false,
                    System.nanoTime(), scene.tick));
            owner.sequence++;
            scene.service.broadcast(scene.snapshot(owner.id));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(1, ((Map<?, ?>) field(session, "bindings")).size());
            assertEquals(1, ((Map<?, ?>) field(session, "pendingStates")).size());
            assertEquals(0, viewer.count("unbind"));
            scene.tick++;
            scene.maintain();
            assertEquals(1, viewer.count("state"));
            assertTrue(limits.allowOutbound(new UUID(2, 10),
                    ConnectionLimits.GLOBAL_BYTES_PER_TICK - (int) field(limits, "globalBytes"), false,
                    System.nanoTime(), scene.tick));
            scene.service.unbind(owner.id, owner.instance, "fixture");
            assertTrue(((DeferredClientPackets) field(session, "controls")).size() > 0);
            assertEquals(0, viewer.count("unbind"));
            scene.tick++;
            scene.maintain();
            assertEquals(1, viewer.count("unbind"));
        }
    }

    @Test
    void heartbeatNeverRenewsInvisibleOrReplacedBindingsBetweenValidationTicks() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.local(owner);
            scene.hello(viewer, true, false);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            scene.tick++;
            setField(session, "lastValidationTick", (long) scene.tick);
            setField(session, "lastDiscoveryTick", (long) scene.tick);
            setField(session, "lastHeartbeatTick", (long) scene.tick - 20);
            owner.instance = UUID.randomUUID();
            scene.maintain();
            assertEquals(0, viewer.last("heartbeat").getAsJsonArray("bindings").size());
            owner.instance = UUID.fromString(viewer.last("state").get("instance").getAsString());
            scene.tick++;
            setField(session, "lastHeartbeatTick", (long) scene.tick - 20);
            scene.service.audience((watcher, id) -> false);
            scene.maintain();
            assertEquals(0, viewer.last("heartbeat").getAsJsonArray("bindings").size());
            scene.service.audience((watcher, id) -> true);
            Files.writeString(scene.modelFolder.resolve("fixture.bbmodel"),
                    "{\"model_identifier\":\"fixture\",\"marker\":\"replacement\",\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}");
            scene.assets.invalidate();
            scene.assets.get("fixture");
            assertTrue(scene.assets.get("fixture").isPresent());
            scene.tick++;
            setField(session, "lastHeartbeatTick", (long) scene.tick - 20);
            scene.maintain();
            assertEquals(0, viewer.last("heartbeat").getAsJsonArray("bindings").size());
        }
    }

    @Test
    void queuedUnbindCannotDeleteSuccessfulSameInstanceRebindBeforeQueueDrain() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            viewer.messages.clear();
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            scene.fillBudget(limits, new UUID(2, 90));
            scene.service.unbind(owner.id, owner.instance, "temporarily_out_of_range");
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(1, ((DeferredClientPackets) field(session, "controls")).size());
            scene.tick++;
            scene.send(viewer, "snapshot_request", null);
            assertEquals(1, viewer.count("state"));
            assertEquals(owner.instance.toString(), viewer.last("state").get("instance").getAsString());
            scene.maintain();
            assertEquals(0, viewer.count("unbind"));
            assertEquals(0, ((DeferredClientPackets) field(session, "controls")).size());
            assertEquals(1, ((Map<?, ?>) field(session, "bindings")).size());
        }
    }

    @Test
    void newReadyReservesAckBudgetBeforeHidingMeAndExistingLeaseSurvivesBusyRetry() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.local(owner);
            scene.hello(viewer, true, false);
            AtomicBoolean hidden = new AtomicBoolean();
            AtomicInteger enables = new AtomicInteger();
            scene.service.rendering((watcher, id, instance, enabled) -> {
                hidden.set(enabled);
                if (enabled) {
                    enables.incrementAndGet();
                }
                return true;
            });
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            scene.fillBudget(limits, new UUID(2, 91));
            scene.ready(viewer, owner);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertFalse(hidden.get());
            assertEquals(0, enables.get());
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
            assertEquals(1, ((Map<?, ?>) field(session, "pendingReady")).size());
            assertEquals(0, viewer.count("render_ack"));
            scene.tick++;
            scene.maintain();
            assertTrue(hidden.get());
            assertEquals(1, enables.get());
            assertEquals(1, viewer.count("render_ack"));
            assertEquals(1, ((RenderLeases) field(session, "leases")).size());
            scene.fillBudget(limits, new UUID(2, 92));
            scene.ready(viewer, owner);
            assertTrue(hidden.get());
            assertEquals(1, ((RenderLeases) field(session, "leases")).size());
            assertEquals(1, viewer.count("render_ack"));
            scene.tick += 100;
            JsonObject renewal = new JsonObject(), identity = new JsonObject();
            JsonArray identities = new JsonArray();
            identity.addProperty("owner", owner.id.toString());
            identity.addProperty("instance", owner.instance.toString());
            identity.addProperty("hash", scene.assets.get("fixture").orElseThrow().hash());
            identities.add(identity);
            renewal.add("bindings", identities);
            scene.send(viewer, "render_heartbeat", renewal);
            scene.maintain();
            assertTrue(hidden.get());
            assertEquals(1, ((RenderLeases) field(session, "leases")).size());
            assertEquals(1, enables.get());
        }
    }

    @Test
    void requestedSnapshotRecreatesUnchangedClientBindingWithItsCompleteActionMenu() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            viewer.messages.clear();
            scene.tick++;
            scene.send(viewer, "snapshot_request", null);
            assertEquals(1, viewer.count("snapshot_begin"));
            assertEquals(1, viewer.count("snapshot_end"));
            assertEquals(1, viewer.count("state"));
            JsonObject recovered = viewer.last("state");
            assertEquals(owner.instance.toString(), recovered.get("instance").getAsString());
            assertEquals(owner.sequence, recovered.get("sequence").getAsLong());
            assertEquals(1, recovered.getAsJsonArray("animations").size());
            assertEquals("idle",
                    recovered.getAsJsonArray("animations").get(0).getAsJsonObject().get("id").getAsString());
        }
    }

    @Test
    void deferredCompleteSnapshotKeepsItsMenuWhenLaterDeltaReplacesTheBody() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            viewer.messages.clear();
            ConnectionLimits limits = (ConnectionLimits) field(scene.service, "limits");
            assertTrue(limits.allowOutbound(new UUID(2, 93),
                    ConnectionLimits.GLOBAL_BYTES_PER_TICK - (int) field(limits, "globalBytes") - 256, false,
                    System.nanoTime(), scene.tick));
            scene.send(viewer, "snapshot_request", null);
            assertEquals(1, viewer.count("snapshot_begin"));
            assertEquals(0, viewer.count("state"));
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(1, ((Map<?, ?>) field(session, "pendingStates")).size());
            owner.sequence++;
            scene.service.broadcast(scene.snapshot(owner.id));
            assertEquals(0, viewer.count("state"));
            scene.tick++;
            scene.maintain();
            JsonObject recovered = viewer.last("state");
            assertEquals(owner.sequence, recovered.get("sequence").getAsLong());
            assertEquals(1, recovered.getAsJsonArray("animations").size());
            assertEquals("idle",
                    recovered.getAsJsonArray("animations").get(0).getAsJsonObject().get("id").getAsString());
        }
    }

    @Test
    void indexedDiscoverySharesOneQuotaAcrossFiveHundredHandshakes() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            List<Person> people = new ArrayList<>();
            Map<UUID, Set<UUID>> candidates = new HashMap<>();
            for (int number = 0; number < 500; number++) {
                scene.nextId = (number + 1) * 40;
                people.add(scene.person(number < 200));
            }
            for (int number = 0; number < 200; number++) {
                for (int slot = 1; slot <= 10; slot++) {
                    UUID viewer = people.get((number + slot) % people.size()).id;
                    candidates.computeIfAbsent(viewer, ignored -> new HashSet<>()).add(people.get(number).id);
                }
            }
            scene.service.audienceCandidates(viewer -> candidates.getOrDefault(viewer, Set.of()));
            scene.service.audience((viewer, owner) -> candidates.getOrDefault(viewer.getUniqueId(), Set.of())
                    .contains(owner));
            for (Person person : people) {
                scene.hello(person, true, false);
                assertTrue((int) field(scene.service, "discoveryCandidatesThisTick") <= 128);
            }
            int started = scene.tick;
            while (!((Deque<?>) field(scene.service, "discoveryQueue")).isEmpty()) {
                assertTrue(scene.tick - started < 100);
                scene.tick++;
                scene.maintain();
                assertTrue((int) field(scene.service, "discoveryCandidatesThisTick") <= 128);
            }
            assertEquals(2200L, field(scene.service, "discoveryCandidatesTotal"));
            for (Person person : people) {
                Set<UUID> expected = new HashSet<>(candidates.getOrDefault(person.id, Set.of()));
                if (scene.owners.containsKey(person.id)) {
                    expected.add(person.id);
                }
                Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(person.id);
                assertEquals(expected, ((Map<?, ?>) field(session, "bindings")).keySet());
            }
            System.out.println("Indexed discovery: 200 owners, 500 observers, 2200 candidates, max 128/tick, "
                    + (scene.tick - started + 1) + " ticks including handshake tick");
        }
    }

    @Test
    void partialFallbackSweepsPreserveBindingsAndEndSnapshotsOnlyAfterCompletion() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                scene.person(true);
            }
            Person first = scene.person(false), second = scene.person(false), third = scene.person(false);
            for (Person viewer : List.of(first, second, third)) {
                scene.hello(viewer, true, false);
                assertEquals(1, viewer.count("snapshot_begin"));
                assertEquals(0, viewer.count("snapshot_end"));
            }
            assertEquals(128, field(scene.service, "discoveryCandidatesThisTick"));
            scene.maintain();
            assertEquals(0, first.count("unbind"));
            int started = scene.tick;
            while (!((Deque<?>) field(scene.service, "discoveryQueue")).isEmpty()) {
                assertTrue(scene.tick - started < 20);
                scene.tick++;
                scene.maintain();
                assertTrue((int) field(scene.service, "discoveryCandidatesThisTick") <= 128);
            }
            for (Person viewer : List.of(first, second, third)) {
                assertEquals(200, viewer.count("state"));
                assertEquals(1, viewer.count("snapshot_end"));
                assertEquals(0, viewer.count("unbind"));
                assertTrue(viewer.messages.indexOf(viewer.last("snapshot_end"))
                        > viewer.messages.indexOf(viewer.last("state")));
            }
        }
    }

    @Test
    void staleCandidateIndexCannotGrantVisibilityOrRevokeAnOtherwiseValidBinding() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.service.audienceCandidates(id -> Set.of(owner.id));
            scene.service.audience((watcher, id) -> false);
            scene.hello(viewer, true, false);
            assertEquals(0, viewer.count("state"));
            scene.service.audience((watcher, id) -> true);
            scene.service.sendSnapshot(viewer.player);
            assertEquals(1, viewer.count("state"));
            scene.service.audienceCandidates(id -> Set.of());
            viewer.messages.clear();
            scene.service.sendSnapshot(viewer.player);
            assertEquals(1, viewer.count("state"));
            assertEquals(0, viewer.count("unbind"));
            viewer.visible = false;
            scene.service.observationChanged(viewer.player);
            assertEquals(1, viewer.count("unbind"));
        }
    }

    @Test
    void existingStateUpdatesAndLeaseRevocationContinueWhenDiscoveryQuotaIsExhausted() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.local(owner);
            AtomicInteger restored = new AtomicInteger();
            scene.service.rendering((watcher, id, instance, enabled) -> {
                if (!enabled) {
                    restored.incrementAndGet();
                }
                return true;
            });
            scene.hello(viewer, true, false);
            scene.ready(viewer, owner);
            for (int number = 0; number < 200; number++) {
                scene.person(true);
            }
            scene.tick++;
            Person joining = scene.person(false);
            scene.hello(joining, true, false);
            assertEquals(128, field(scene.service, "discoveryCandidatesThisTick"));
            viewer.messages.clear();
            owner.sequence++;
            scene.service.broadcast(scene.snapshot(owner.id));
            assertEquals(1, viewer.count("state"));
            viewer.visible = false;
            scene.service.observationChanged(viewer.player);
            assertEquals(1, viewer.count("unbind"));
            assertEquals(1, restored.get());
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(0, ((RenderLeases) field(session, "leases")).size());
            assertEquals(128, field(scene.service, "discoveryCandidatesThisTick"));
        }
    }

    @Test
    void quitCancelsAPartialScanAndANewHandshakeStartsAFreshSnapshot() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                scene.person(true);
            }
            Person viewer = scene.person(false);
            scene.hello(viewer, true, false);
            assertEquals(0, viewer.count("snapshot_end"));
            scene.service.forget(viewer.player);
            assertTrue(((Deque<?>) field(scene.service, "discoveryQueue")).isEmpty());
            viewer.messages.clear();
            scene.hello(viewer, true, false);
            assertEquals(1, viewer.count("snapshot_begin"));
            assertEquals(0, viewer.count("state"));
            assertEquals(1, ((Deque<?>) field(scene.service, "discoveryQueue")).size());
            for (int number = 0; number < 2; number++) {
                scene.tick++;
                scene.maintain();
            }
            assertEquals(200, viewer.count("state"));
            assertEquals(1, viewer.count("snapshot_end"));
        }
    }

    @Test
    void remainingCandidatesUseCurrentInstancesAndVisibilityAfterAYield() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                scene.person(true);
            }
            Person viewer = scene.person(false);
            scene.hello(viewer, true, false);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            Map<?, ?> bindings = (Map<?, ?>) field(session, "bindings");
            List<Person> remaining = scene.owners.values().stream().filter(person -> !bindings.containsKey(person.id))
                    .toList();
            assertEquals(72, remaining.size());
            Person replaced = remaining.get(0), distant = remaining.get(1), revoked = remaining.get(2);
            UUID previousInstance = replaced.instance;
            replaced.instance = UUID.randomUUID();
            distant.x = 65;
            scene.service.audience((watcher, owner) -> !owner.equals(revoked.id));
            scene.tick++;
            scene.maintain();
            assertEquals(0, viewer.messages.stream().filter(packet -> packet.get("type").getAsString().equals("state")
                    && packet.get("instance").getAsString().equals(previousInstance.toString())).count());
            assertTrue(viewer.messages.stream().anyMatch(packet -> packet.get("type").getAsString().equals("state")
                    && packet.get("instance").getAsString().equals(replaced.instance.toString())));
            assertFalse(bindings.containsKey(distant.id));
            assertFalse(bindings.containsKey(revoked.id));
            assertEquals(1, viewer.count("snapshot_end"));
            assertEquals(0, viewer.count("unbind"));
        }
    }

    @Test
    void mainWorkBudgetPausesDiscoveryWithoutDelayingRevocationAndThenResumes() throws Exception {
        Constructor<ResourceProtection> constructor = ResourceProtection.class.getDeclaredConstructor(
                ResourceSettings.class, LongSupplier.class);
        constructor.setAccessible(true);
        try (Scene scene = new Scene(this.temporary);
                ResourceProtection runtime = constructor.newInstance(ResourceSettings.defaults(), (LongSupplier) () -> 0)) {
            scene.allowLargeSnapshot();
            Person owner = scene.person(true), viewer = scene.person(false);
            scene.hello(viewer, true, false);
            for (int number = 0; number < 200; number++) {
                scene.person(true);
            }
            scene.tick++;
            Person joining = scene.person(false);
            scene.hello(joining, true, false);
            assertEquals(128, field(scene.service, "discoveryCandidatesThisTick"));
            setField(scene.service, "protection", runtime);
            assertTrue(runtime.tryMainWork(runtime.settings().protection().mainWorkPerTick()));
            int delivered = joining.count("state");
            viewer.visible = false;
            scene.service.observationChanged(viewer.player);
            assertEquals(1, viewer.count("unbind"));
            scene.tick++;
            scene.maintain();
            assertEquals(delivered, joining.count("state"));
            assertEquals(0, joining.count("snapshot_end"));
            assertEquals(0, field(scene.service, "discoveryCandidatesThisTick"));
            for (int number = 0; number < 20 && joining.count("snapshot_end") == 0; number++) {
                runtime.tick();
                scene.tick++;
                scene.maintain();
            }
            assertEquals(201, joining.count("state"));
            assertEquals(1, joining.count("snapshot_end"));
        }
    }

    @Test
    void pushDiscoveryRechecksAuthorizationOnceForAllBatchesInEachPump() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                Person owner = scene.person(true);
                if (number == 0) {
                    scene.local(owner);
                }
                owner.localRenderable = true;
            }
            Person viewer = scene.person(false);
            scene.helloPush(viewer);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(128, ((Map<?, ?>) field(session, "bindings")).size());
            assertEquals(1L, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(128L, field(scene.service, "authorizationBindingChecksTotal"));
            assertEquals(Set.of(scene.assets.get("fixture").orElseThrow().hash()), field(session, "authorized"));
            assertEquals(1, viewer.count("asset_offer"));
            assertEquals(0, viewer.count("asset_cancel"));

            scene.tick++;
            Method pump = ClientSyncService.class.getDeclaredMethod("pumpDiscovery");
            pump.setAccessible(true);
            pump.invoke(scene.service);
            assertEquals(200, ((Map<?, ?>) field(session, "bindings")).size());
            assertEquals(2L, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(328L, field(scene.service, "authorizationBindingChecksTotal"));
            assertEquals(0, field(session, "authorizationBatchDepth"));
            assertEquals(0, viewer.count("asset_cancel"));
            assertEquals(1, viewer.count("snapshot_end"));
        }
    }

    @Test
    void validationCancelsRevokedOffersAfterOneAuthorizationRefreshWithoutDiscoveryWork() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 20; number++) {
                Person owner = scene.person(true);
                if (number == 0) {
                    scene.local(owner);
                }
                owner.localRenderable = true;
            }
            Person viewer = scene.person(false);
            scene.helloPush(viewer);
            long before = (long) field(scene.service, "authorizationRefreshesTotal");
            int candidates = (int) field(scene.service, "discoveryCandidatesThisTick");
            viewer.visible = false;
            scene.service.observationChanged(viewer.player);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertTrue(((Map<?, ?>) field(session, "bindings")).isEmpty());
            assertEquals(Set.of(), field(session, "authorized"));
            assertEquals(before + 1, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(20, viewer.count("unbind"));
            assertEquals(1, viewer.count("asset_cancel"));
            assertEquals(candidates, field(scene.service, "discoveryCandidatesThisTick"));
            assertEquals(0, field(session, "authorizationBatchDepth"));
        }
    }

    @Test
    void revokingOneHashAliasPreservesTheOfferUntilTheLastValidOwnerIsRemoved() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            Person first = scene.person(true), second = scene.person(true), viewer = scene.person(false);
            scene.local(first);
            second.localRenderable = true;
            scene.helloPush(viewer);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            String offer = viewer.last("asset_offer").get("offerId").getAsString();
            scene.service.audience((watcher, owner) -> owner.equals(second.id));
            long before = (long) field(scene.service, "authorizationRefreshesTotal");
            scene.service.observationChanged(viewer.player);
            assertEquals(Set.of(second.id), ((Map<?, ?>) field(session, "bindings")).keySet());
            assertEquals(Set.of(scene.assets.get("fixture").orElseThrow().hash()), field(session, "authorized"));
            assertEquals(before + 1, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(0, viewer.count("asset_cancel"));
            assertEquals(offer, viewer.last("asset_offer").get("offerId").getAsString());

            scene.service.unbind(second.id, second.instance, "test_revoke");
            assertEquals(Set.of(), field(session, "authorized"));
            assertEquals(1, viewer.count("asset_cancel"));
            assertEquals(before + 2, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(0, field(session, "authorizationBatchDepth"));
        }
    }

    @Test
    void failedDiscoveryStillClosesAuthorizationBatchesForAllTouchedViewers() throws Exception {
        try (Scene scene = new Scene(this.temporary)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                Person owner = scene.person(true);
                if (number == 0) {
                    scene.local(owner);
                }
                owner.localRenderable = true;
            }
            Person first = scene.person(false), second = scene.person(false);
            scene.helloPush(first);
            scene.helloPush(second);
            AtomicBoolean fail = new AtomicBoolean(true);
            scene.service.audience((watcher, owner) -> {
                if (watcher.getUniqueId().equals(first.id) && fail.getAndSet(false)) {
                    throw new IllegalStateException("fixture audience failure");
                }
                return true;
            });
            long before = (long) field(scene.service, "authorizationRefreshesTotal");
            scene.tick++;
            Method pump = ClientSyncService.class.getDeclaredMethod("pumpDiscovery");
            pump.setAccessible(true);
            pump.invoke(scene.service);
            assertEquals(before + 2, field(scene.service, "authorizationRefreshesTotal"));
            for (Person viewer : List.of(first, second)) {
                Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
                assertEquals(0, field(session, "authorizationBatchDepth"));
                assertFalse((boolean) field(session, "authorizationDirty"));
                assertEquals(Set.of(scene.assets.get("fixture").orElseThrow().hash()), field(session, "authorized"));
                assertEquals(0, viewer.count("asset_cancel"));
            }
        }
    }

    @Test
    void partialDiscoveryStillFlushesAuthorizationWhenMainBudgetStopsTheNextBatch() throws Exception {
        Constructor<ResourceProtection> constructor = ResourceProtection.class.getDeclaredConstructor(
                ResourceSettings.class, LongSupplier.class);
        constructor.setAccessible(true);
        try (Scene scene = new Scene(this.temporary);
                ResourceProtection runtime = constructor.newInstance(ResourceSettings.defaults(), (LongSupplier) () -> 0)) {
            scene.allowLargeSnapshot();
            for (int number = 0; number < 200; number++) {
                Person owner = scene.person(true);
                if (number == 0) {
                    scene.local(owner);
                }
                owner.localRenderable = true;
            }
            Person viewer = scene.person(false);
            scene.helloPush(viewer);
            assertTrue(runtime.tryMainWork(runtime.settings().protection().mainWorkPerTick() - 1));
            setField(scene.service, "protection", runtime);
            scene.tick++;
            Method pump = ClientSyncService.class.getDeclaredMethod("pumpDiscovery");
            pump.setAccessible(true);
            pump.invoke(scene.service);
            Object session = ((Map<?, ?>) field(scene.service, "sessions")).get(viewer.id);
            assertEquals(136, ((Map<?, ?>) field(session, "bindings")).size());
            assertEquals(2L, field(scene.service, "authorizationRefreshesTotal"));
            assertEquals(264L, field(scene.service, "authorizationBindingChecksTotal"));
            assertEquals(Set.of(scene.assets.get("fixture").orElseThrow().hash()), field(session, "authorized"));
            assertEquals(0, field(session, "authorizationBatchDepth"));
            assertFalse((boolean) field(session, "authorizationDirty"));
            assertEquals(0, viewer.count("asset_cancel"));
            assertEquals(0, viewer.count("snapshot_end"));
        }
    }

    private static final class Scene implements AutoCloseable {
        final Field serverField;
        final Object originalServer;
        final Map<UUID, Person> people = new LinkedHashMap<>();
        final Map<UUID, Person> owners = new LinkedHashMap<>();
        final UUID worldId = new UUID(3, 1);
        final World world;
        final ClientSyncService service;
        final ModelAssets assets;
        final Path modelFolder;
        int tick = 100, nextId = 1, singleReads;

        Scene(Path temporary) throws Exception {
            this(temporary, Runnable::run);
        }

        Scene(Path temporary, Consumer<Runnable> preparation) throws Exception {
            this.modelFolder = temporary;
            this.world = proxy(World.class, (instance, method, args) -> method.getName().equals("getUID") ? this.worldId
                    : objectMethod(instance, method, args));
            Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getCurrentTick" -> this.tick;
            case "isPrimaryThread" -> true;
            case "getPlayer" -> {
                Person person = this.people.get(args[0]);
                yield person == null ? null : person.player;
            }
            default -> objectMethod(instance, method, args);
            });
            Plugin plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
            case "getServer" -> server;
            case "isEnabled" -> true;
            case "getName" -> "SyncPerformanceFixture";
            case "getLogger" -> Logger.getLogger("SyncPerformanceFixture");
            default -> objectMethod(instance, method, args);
            });
            this.serverField = Bukkit.class.getDeclaredField("server");
            this.serverField.setAccessible(true);
            this.originalServer = this.serverField.get(null);
            this.serverField.set(null, server);
            try {
                this.assets = new ModelAssets(temporary, null, name -> null, preparation, ignored -> {
                });
                this.service = new ClientSyncService(plugin, () -> {
                    throw new AssertionError("Full snapshot supplier invoked");
                }, ignored -> {
                }, this.assets);
                this.service.snapshotSources(() -> Set.copyOf(this.owners.keySet()), id -> {
                    this.singleReads++;
                    return snapshot(id);
                });
                setField(this.service, "running", true);
            } catch (Exception | Error failure) {
                this.serverField.set(null, this.originalServer);
                throw failure;
            }
        }

        Person person(boolean disguised) {
            Person person = new Person(new UUID(0, this.nextId++), this.world);
            this.people.put(person.id, person);
            if (disguised) {
                this.owners.put(person.id, person);
            }
            return person;
        }

        ClientSyncService.StateSnapshot snapshot(UUID id) {
            Person owner = this.owners.get(id);
            if (owner == null) {
                return null;
            }

            ClientSyncService.MotionState motion = new ClientSyncService.MotionState(List.of(), List.of(), 17, 6, .025,
                    true, true, false, "", "", "", 0, 0, 0, 0);
            return new ClientSyncService.StateSnapshot(id, owner.instance, "fixture", owner.sequence, this.tick,
                    List.of(), this.worldId, owner.x, 64, 0, 0, 0, 0, 1, true, owner.showSelf, owner.animations,
                    owner.localRenderable, motion);
        }

        void hello(Person person, boolean incremental, boolean timeline) {
            hello(person, incremental, timeline, false);
        }

        void helloPush(Person person) {
            JsonObject packet = new JsonObject();
            JsonArray capabilities = new JsonArray();
            for (String capability : List.of("local_render", "server_push_models", "incremental_state")) {
                capabilities.add(capability);
            }
            packet.add("capabilities", capabilities);
            send(person, "hello", packet);
        }

        void hello(Person person, boolean incremental, boolean timeline, boolean disguiseResults) {
            JsonObject packet = new JsonObject();
            packet.addProperty("protocol", 3);
            packet.addProperty("type", "hello");
            JsonArray capabilities = new JsonArray();
            capabilities.add("local_render");
            capabilities.add("resource_pack_models");
            if (incremental) {
                capabilities.add("incremental_state");
            }
            if (timeline) {
                capabilities.add("server_timeline");
            }

            if (disguiseResults) {
                capabilities.add(ClientSyncService.DISGUISE_RESULTS);
            }
            packet.add("capabilities", capabilities);
            this.service.onPluginMessageReceived(ClientSyncService.CHANNEL, person.player,
                    packet.toString().getBytes(StandardCharsets.UTF_8));
        }

        void maintain() throws Exception {
            Method method = ClientSyncService.class.getDeclaredMethod("maintainSessions");
            method.setAccessible(true);
            method.invoke(this.service);
        }

        void allowLargeSnapshot() throws Exception {
            ResourceSettings.Network defaults = ResourceSettings.defaults().network();
            ConnectionLimits limits = (ConnectionLimits) field(this.service, "limits");
            limits.configure(new ResourceSettings.Network(defaults.globalUploadBytesPerSecond(),
                    defaults.globalDownloadBytesPerSecond(), defaults.uploadBytesPerSecond(),
                    defaults.downloadBytesPerSecond(), defaults.assetDownloadBytesPerSecond(),
                    defaults.globalPacketsPerSecond(), 512, defaults.burstBytes(), defaults.bytesPerTick(),
                    defaults.assetBytesPerTick(), defaults.globalTransfers(), defaults.transfersPerPlayer(),
                    defaults.waitingTransfers(), defaults.transferQueueTicks(), defaults.transferIdleTicks(),
                    defaults.transferTotalTicks(), defaults.readyTicks(), defaults.maxQueuedOutgoingBytes()));
        }

        void local(Person owner) throws Exception {
            Files.writeString(this.modelFolder.resolve("fixture.bbmodel"),
                    "{\"model_identifier\":\"fixture\",\"elements\":[],\"outliner\":[],\"textures\":[],\"animations\":[]}");
            this.assets.get("fixture");
            assertTrue(this.assets.get("fixture").isPresent());
            owner.localRenderable = true;
        }

        void fillBudget(ConnectionLimits limits, UUID filler) throws Exception {
            assertTrue(limits.allowOutbound(filler,
                    ConnectionLimits.GLOBAL_BYTES_PER_TICK - (int) field(limits, "globalBytes"), false,
                    System.nanoTime(), this.tick));
        }

        void ready(Person viewer, Person owner) {
            JsonObject binding = new JsonObject();
            binding.addProperty("owner", owner.id.toString());
            binding.addProperty("instance", owner.instance.toString());
            binding.addProperty("hash", this.assets.get("fixture").orElseThrow().hash());
            send(viewer, "render_ready", binding);
        }

        void send(Person viewer, String type, JsonObject fields) {
            JsonObject packet = new JsonObject();
            packet.addProperty("protocol", 3);
            packet.addProperty("type", type);
            if (fields != null) {
                fields.entrySet().forEach(entry -> packet.add(entry.getKey(), entry.getValue()));
            }

            this.service.onPluginMessageReceived(ClientSyncService.CHANNEL, viewer.player,
                    packet.toString().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws Exception {
            this.serverField.set(null, this.originalServer);
        }
    }

    private static final class Person {
        final UUID id;
        UUID instance = UUID.randomUUID();
        final Player player;
        final List<JsonObject> messages = new ArrayList<>();
        double x;
        long sequence = 1;
        int lastBytes;
        boolean localRenderable, showSelf = true, invisible, visible = true;
        List<ClientSyncService.AnimationInfo> animations = List.of(new ClientSyncService.AnimationInfo("idle", "Idle"));

        Person(UUID id, World world) {
            this.id = id;
            this.player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline" -> true;
            case "canSee" -> this.visible;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world, this.x, 64, 0);
            case "getFoodLevel" -> 20;
            case "isInvisible" -> this.invisible;
            case "sendPluginMessage" -> {
                this.lastBytes = ((byte[]) args[2]).length;
                this.messages.add(
                        JsonParser.parseString(new String((byte[]) args[2], StandardCharsets.UTF_8)).getAsJsonObject());
                yield null;
            }
            default -> objectMethod(instance, method, args);
            });
        }

        int count(String type) {
            return (int) this.messages.stream().filter(value -> value.get("type").getAsString().equals(type)).count();
        }

        JsonObject last(String type) {
            return this.messages.stream().filter(value -> value.get("type").getAsString().equals(type))
                    .reduce((first, second) -> second).orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object objectMethod(Object instance, Method method, Object[] args) {
        return switch (method.getName()) {
        case "equals" -> instance == args[0];
        case "hashCode" -> System.identityHashCode(instance);
        case "toString" -> "fixture";
        default -> method.getReturnType() == boolean.class ? false
                : (method.getReturnType() == int.class ? 0
                        : (method.getReturnType() == long.class ? 0L
                                : (method.getReturnType() == double.class ? 0d
                                        : (method.getReturnType() == float.class ? 0f : null))));
        };
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
