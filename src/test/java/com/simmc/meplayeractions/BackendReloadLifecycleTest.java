package com.simmc.meplayeractions;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same replacement guard used by the reload command without starting Bukkit. */
class BackendReloadLifecycleTest {
    @Test void invalidConfigurationPreservesTheActiveBackendAndItsRuntime() {
        var active = new AtomicBoolean(true);
        var invalid = new IllegalArgumentException("invalid resource settings");
        assertSame(invalid, assertThrows(IllegalArgumentException.class, () ->
                MEPlayerActionsPlugin.replaceAfterValidation(() -> { throw invalid; },
                        ignored -> fail("Replacement must not run"), () -> active.set(false))));
        assertTrue(active.get());
    }

    @Test void partiallyStartedReplacementClosesNewResourcesAfterStartupFailure() {
        var order = new ArrayList<String>();
        var newRuntime = new AtomicBoolean(false);
        var startup = new IllegalStateException("private relay startup failed");
        assertSame(startup, assertThrows(IllegalStateException.class, () ->
                MEPlayerActionsPlugin.replaceAfterValidation(() -> { order.add("validate"); return "next"; },
                        next -> {
                            assertEquals("next", next);
                            order.add("release-old"); newRuntime.set(true); order.add("start-new"); throw startup;
                        }, () -> { order.add("cleanup-new"); newRuntime.set(false); })));
        assertEquals(List.of("validate", "release-old", "start-new", "cleanup-new"), order);
        assertFalse(newRuntime.get());
    }

    @Test void incompatibleNewBackendAlsoClosesThePartialReplacement() {
        var cleaned = new AtomicBoolean(false);
        var missingApi = new NoClassDefFoundError("optional backend API");
        assertSame(missingApi, assertThrows(NoClassDefFoundError.class, () ->
                MEPlayerActionsPlugin.replaceAfterValidation(() -> "next", ignored -> { throw missingApi; },
                        () -> cleaned.set(true))));
        assertTrue(cleaned.get());
    }

    @Test void successfulReplacementKeepsTheNewBackendRunning() {
        var started = new AtomicBoolean(false);
        MEPlayerActionsPlugin.replaceAfterValidation(() -> "next", ignored -> started.set(true),
                () -> fail("Successful replacement must not be closed"));
        assertTrue(started.get());
    }

    @Test void cleanupFailureDoesNotReplaceTheOriginalStartupError() {
        var startup = new IllegalStateException("startup failed");
        var cleanup = new LinkageError("cleanup failed");
        assertSame(startup, assertThrows(IllegalStateException.class, () ->
                MEPlayerActionsPlugin.replaceAfterValidation(() -> "next", ignored -> { throw startup; },
                        () -> { throw cleanup; })));
        assertArrayEquals(new Throwable[]{cleanup}, startup.getSuppressed());
    }
}
