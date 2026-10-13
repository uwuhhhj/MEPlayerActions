package com.simmc.meplayeractions.preferences;

import com.simmc.meplayeractions.protection.ResourceProtection;
import com.simmc.meplayeractions.protection.ResourceSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPreferencesTest {
    private static final Logger LOGGER = Logger.getLogger("MPA.Preferences.Tests");
    private static final Constructor<ResourceProtection> RUNTIME_CONSTRUCTOR = runtimeConstructor();
    @TempDir
    Path temporary;

    @Test
    void firstDirtyDeadlineCoalescesLatestValuesWithoutDebouncingIndefinitely() throws Exception {
        AtomicLong clock = new AtomicLong();
        List<List<PlayerPreferences.Change>> writes = new ArrayList<>();
        UUID player = UUID.randomUUID();
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0,
                    (changes, ignored) -> writes.add(changes), clock::get, LOGGER);
            preferences.runtime(resources);
            assertTrue(preferences.enabled(player, "walk"));
            assertTrue(preferences.set(player, "walk", false));
            assertFalse(preferences.enabled(player, "walk"));
            assertFalse(preferences.set(player, "walk", false));
            clock.set(500_000_000);
            assertTrue(preferences.set(player, "walk", true));
            clock.set(999_000_000);
            preferences.tick();
            assertTrue(writes.isEmpty());
            clock.set(1_000_000_000);
            preferences.tick();
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.writes") == 1;
            });
            assertEquals(1, writes.size());
            assertEquals(1, writes.getFirst().size());
            assertEquals(Boolean.TRUE, writes.getFirst().getFirst().value());
            assertEquals(0, resources.snapshot().get("preferences.pending"));
            assertFalse(preferences.set(player, "walk", true));
            assertTrue(preferences.set(player, "walk", null));
            assertTrue(preferences.enabled(player, "walk"));
            assertFalse(preferences.set(player, "walk", null));
            preferences.shutdown();
            assertNull(writes.getLast().getFirst().value());
        }
    }

    @Test
    void failedWritesKeepDirtyValuesAndRetryLatestSnapshotWithBackoff() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        UUID player = UUID.randomUUID();
        List<List<PlayerPreferences.Change>> saved = new ArrayList<>();
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0, (changes, ignored) -> {
                if (attempts.incrementAndGet() <= 2) {
                    throw new IOException("disk failure");
                }
                saved.add(changes);
            }, clock::get, LOGGER);
            preferences.runtime(resources);
            preferences.set(player, "walk", false);
            clock.set(1_000_000_000);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.failures") == 1;
            });
            preferences.set(player, "walk", true);
            clock.set(5_999_000_000L);
            preferences.tick();
            assertEquals(1, attempts.get());
            assertEquals(1, resources.snapshot().get("preferences.pending"));
            clock.set(6_000_000_000L);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.failures") == 2;
            });
            clock.set(16_000_000_000L);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.writes") == 1;
            });
            assertEquals(3, attempts.get());
            assertEquals(Boolean.TRUE, saved.getFirst().getFirst().value());
            assertEquals(0, resources.snapshot().get("preferences.pending"));
            preferences.shutdown();
        }
    }

    @Test
    void negativeClockOriginFlushesAfterElapsedSecondAndHonorsRetryAtZero() throws Exception {
        AtomicLong clock = new AtomicLong(-6_000_000_000L);
        AtomicInteger attempts = new AtomicInteger();
        List<List<PlayerPreferences.Change>> snapshots = new ArrayList<>();
        UUID player = UUID.randomUUID();
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0, (changes, ignored) -> {
                snapshots.add(changes);
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("first save fails");
                }
            }, clock::get, LOGGER);
            preferences.runtime(resources);
            assertEquals(0, resources.snapshot().get("preferences.retryMillis"));
            preferences.set(player, "walk", false);
            clock.set(-5_500_000_000L);
            preferences.set(player, "walk", true);
            clock.set(-5_001_000_000L);
            preferences.tick();
            assertEquals(0, attempts.get());
            clock.set(-5_000_000_000L);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.failures") == 1;
            });
            assertEquals(Boolean.TRUE, snapshots.getFirst().getFirst().value());
            assertEquals(5_000, resources.snapshot().get("preferences.retryMillis"));
            clock.set(-4_000_000_000L);
            preferences.set(player, "walk", false);
            preferences.tick();
            assertEquals(1, attempts.get());
            assertEquals(4_000, resources.snapshot().get("preferences.retryMillis"));
            clock.set(-1_000_000L);
            preferences.tick();
            assertEquals(1, attempts.get());
            clock.set(0);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.writes") == 1;
            });
            assertEquals(2, attempts.get());
            assertEquals(Boolean.FALSE, snapshots.getLast().getFirst().value());
            assertEquals(0, resources.snapshot().get("preferences.retryMillis"));
            preferences.shutdown();
        }
    }

    @Test
    void runtimeReplacementWaitsForActualOldWriterExitAndPreservesNewerEdits() throws Exception {
        AtomicLong clock = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), writes = new AtomicInteger();
        UUID player = UUID.randomUUID();
        ResourceProtection old = runtime();
        try (ResourceProtection next = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0, (changes, ignored) -> {
                int concurrent = active.incrementAndGet();
                peak.accumulateAndGet(concurrent, Math::max);
                try {
                    if (writes.incrementAndGet() == 1) {
                        entered.countDown();
                        while (!release.get()) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException interrupted) {
                            }
                        }
                    }
                } finally {
                    active.decrementAndGet();
                }
            }, clock::get, LOGGER);
            preferences.runtime(old);
            preferences.set(player, "walk", false);
            clock.set(1_000_000_000);
            preferences.tick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            preferences.set(player, "walk", true);
            old.close();
            preferences.runtime(next);
            clock.set(3_000_000_000L);
            preferences.tick();
            assertEquals(1, writes.get());
            assertTrue(old.snapshot().get("tasks.reservedBytes") > 0);
            assertTrue(preferences.enabled(player, "walk"));
            release.set(true);
            await(() -> {
                preferences.tick();
                return next.snapshot().get("preferences.writes") == 2;
            });
            assertEquals(1, peak.get());
            assertEquals(0, old.snapshot().get("tasks.reservedBytes"));
            assertEquals(0, next.snapshot().get("preferences.pending"));
            preferences.shutdown();
        } finally {
            release.set(true);
            old.close();
        }
    }

    @Test
    void changesDuringWriteStartTheirOwnFixedDeadline() throws Exception {
        AtomicLong clock = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        UUID player = UUID.randomUUID();
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0, (changes, ignored) -> {
                if (writes.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        throw new IOException(interrupted);
                    }
                }
            }, clock::get, LOGGER);
            preferences.runtime(resources);
            preferences.set(player, "walk", false);
            clock.set(1_000_000_000);
            preferences.tick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.set(1_200_000_000);
            preferences.set(player, "walk", true);
            clock.set(1_800_000_000);
            preferences.set(player, "walk", false);
            release.countDown();
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.writes") == 1;
            });
            clock.set(2_199_000_000L);
            preferences.tick();
            assertEquals(1, writes.get());
            clock.set(2_200_000_000L);
            await(() -> {
                preferences.tick();
                return resources.snapshot().get("preferences.writes") == 2;
            });
            preferences.shutdown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownForcesSaveAndRetainsExistingYamlFieldsAndDefaults() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = this.temporary.resolve("players.yml");
        Files.writeString(file, "custom:\n  version: 7\n  list: [one, two]\n" + player
                + ":\n  walk: false\n  fly: invalid\n  unrelated: preserved\n");
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(file, LOGGER);
            preferences.runtime(resources);
            assertFalse(preferences.enabled(player, "walk"));
            assertTrue(preferences.enabled(player, "jump"));
            assertTrue(preferences.enabled(player, "fly"));
            assertTrue(preferences.set(player, "fly", null));
            preferences.set(player, "walk", null);
            preferences.set(player, "jump", false);
            preferences.shutdown();
            YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
            assertEquals(7, saved.getInt("custom.version"));
            assertEquals(List.of("one", "two"), saved.getStringList("custom.list"));
            assertEquals("preserved", saved.getString(player + ".unrelated"));
            assertFalse(saved.contains(player + ".walk"));
            assertFalse(saved.contains(player + ".fly"));
            assertFalse(saved.getBoolean(player + ".jump", true));
            try (java.util.stream.Stream<Path> files = Files.list(this.temporary)) {
                assertEquals(List.of(file), files.toList());
            }
        }
    }

    @Test
    void shutdownHasOneBoundedDeadlineEvenWhenWriterIgnoresInterruption() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicLong clock = new AtomicLong();
        try (ResourceProtection resources = runtime()) {
            PlayerPreferences preferences = new PlayerPreferences(Map.of(), 0, (changes, ignored) -> {
                entered.countDown();
                while (!release.get()) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException interrupted) {
                    }
                }
            }, clock::get, LOGGER);
            preferences.runtime(resources);
            preferences.set(UUID.randomUUID(), "walk", false);
            clock.set(1_000_000_000);
            preferences.tick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            preferences.shutdown();
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            assertTrue(resources.snapshot().get("tasks.reservedBytes") > 0);
            assertEquals(0, resources.snapshot().get("preferences.pending"));
            release.set(true);
            await(() -> resources.snapshot().get("tasks.reservedBytes") == 0);
            assertThrows(IllegalStateException.class, () -> preferences.set(UUID.randomUUID(), "walk", true));
        } finally {
            release.set(true);
        }
    }

    private static Constructor<ResourceProtection> runtimeConstructor() {
        try {
            Constructor<ResourceProtection> constructor = ResourceProtection.class.getDeclaredConstructor(
                    ResourceSettings.class, LongSupplier.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static ResourceProtection runtime() throws ReflectiveOperationException {
        ResourceSettings defaults = ResourceSettings.defaults();
        ResourceSettings settings = new ResourceSettings(defaults.tasks(), defaults.network(), defaults.protection(),
                defaults.models(), new ResourceSettings.Disk(defaults.disk().maxTemporaryBytes(), 0),
                defaults.complexity());
        return RUNTIME_CONSTRUCTOR.newInstance(settings, (LongSupplier) System::nanoTime);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Preference writer did not reach the expected state");
    }
}
