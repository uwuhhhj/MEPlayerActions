package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.config.ModelComplexityLimits;
import com.simmc.meplayeractions.protection.ResourceRejectedException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OptionalServerTemplateTest {
    @Test void absentOptionalTemplateReturnsMeFallbackImmediatelyWithoutIoOrWork() {
        AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        try (var cache = new ServerTemplatePreparation(path -> { reads.incrementAndGet(); return null; },
                path -> { lookups.incrementAndGet(); assertEquals("models/registered_me_model.bbmodel", path); return false; },
                work::add, ModelComplexityLimits.defaults(), 4, 1_000_000, System::nanoTime)) {
            for (int i = 0; i < 20; i++) assertTrue(cache.ensureIfPresent("registered_me_model").isEmpty());
            assertEquals(1, lookups.get());
            assertEquals(0, reads.get());
            assertTrue(work.isEmpty());
            assertEquals(0, cache.metrics().entries());
            assertEquals(0, cache.metrics().bytes());
        }
    }
    @Test void presentTemplateKeepsAsyncPreparationSingleFlightAndAuthorData() {
        AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        byte[] authored = ("{\"mpa_runtime\":true,\"animations\":[{\"name\":\"idle\",\"animators\":{"
                + "\"00000000-0000-0000-0000-000000000001\":{\"name\":\"body\",\"type\":\"bone\",\"keyframes\":["
                + "{\"channel\":\"scale\",\"time\":0,\"interpolation\":\"linear\",\"data_points\":[{\"x\":\"variable.scale\"}]}]}}}]}")
                .getBytes(StandardCharsets.UTF_8);
        try (var cache = new ServerTemplatePreparation(path -> { reads.incrementAndGet(); return new ByteArrayInputStream(authored); },
                path -> { lookups.incrementAndGet(); return true; },
                work::add, ModelComplexityLimits.defaults(), 4, 1_000_000, System::nanoTime)) {
            for (int i = 0; i < 20; i++) assertEquals("asset_preparing",
                    assertThrows(ResourceRejectedException.class, () -> cache.ensureIfPresent("authored_model")).error().code());
            assertEquals(1, lookups.get()); assertEquals(0, reads.get()); assertEquals(1, work.size());
            work.remove().run();
            var model = cache.ensureIfPresent("authored_model").orElseThrow();
            assertEquals("variable.scale", model.clips().getFirst().bones().getFirst().frames().getFirst().pre().x());
            assertSame(model, cache.ensureIfPresent("authored_model").orElseThrow());
            assertEquals(1, reads.get()); assertEquals(1, lookups.get());
        }
    }
    @Test void missingTemplatesDoNotFillPreloadQueueAndInvalidPresentTemplateStaysRejected() {
        AtomicInteger reads = new AtomicInteger(); ArrayDeque<Runnable> work = new ArrayDeque<>();
        try (var cache = new ServerTemplatePreparation(path -> { reads.incrementAndGet(); return new ByteArrayInputStream("invalid".getBytes(StandardCharsets.UTF_8)); },
                path -> path.equals("models/authored_model.bbmodel"),
                work::add, ModelComplexityLimits.defaults(), 2, 1_000_000, System::nanoTime)) {
            cache.preload(List.of("missing_one", "missing_two", "authored_model"));
            assertEquals(1, work.size()); assertEquals(0, reads.get());
            work.remove().run();
            assertEquals("invalid_model", assertThrows(ResourceRejectedException.class, () -> cache.ensureIfPresent("authored_model")).error().code());
            assertTrue(cache.ensureIfPresent("missing_one").isEmpty()); assertTrue(work.isEmpty());
            assertEquals(1, reads.get());
            cache.close();
            assertEquals("request_cancelled", assertThrows(ResourceRejectedException.class, () -> cache.ensureIfPresent("missing_one")).error().code());
        }
    }
}
