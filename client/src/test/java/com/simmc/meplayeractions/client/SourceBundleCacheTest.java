package com.simmc.meplayeractions.client;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SourceBundleCacheTest {
    @Test
    void refreshDoesNotWaitForLoadingAndLateSuccessCannotRestoreOldSource() throws Exception {
        SourceBundleCache cache = new SourceBundleCache();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<byte[]> loading = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.bundle("model", generation -> {
                    entered.countDown();
                    await(release);
                    cache.remember("model", new byte[] { 1 }, generation);
                });
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture.runAsync(cache::clear).get(2, TimeUnit.SECONDS);
            cache.remember("model", new byte[] { 2 }, cache.generation());
        } finally {
            release.countDown();
        }
        assertInstanceOf(IOException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> loading.get(2, TimeUnit.SECONDS)).getCause());
        assertArrayEquals(new byte[] { 2 }, cache.bundle("model", generation -> fail("Must use refreshed cache")));
    }

    @Test
    void lateErrorsAreDiscardedAndReturnedBytesCannotMutateTheCache() throws Exception {
        SourceBundleCache cache = new SourceBundleCache();
        long old = cache.generation();
        cache.clear();
        cache.error("model", "old error", old);
        byte[] bytes = cache.bundle("model", generation -> cache.remember("model", new byte[] { 7 }, generation));
        bytes[0] = 99;
        assertArrayEquals(new byte[] { 7 }, cache.bundle("model", generation -> fail("Must retain owned bytes")));
        cache.error("model", "duplicate failed load", cache.generation());
        assertArrayEquals(new byte[] { 7 }, cache.bundle("model", generation -> fail("Success wins")));
    }

    @Test
    void sourceAndErrorCachesRetainTheirExistingSixteenEntryLimits() throws Exception {
        SourceBundleCache cache = new SourceBundleCache();
        for (int number = 0; number < 17; number++) {
            cache.remember("model" + number, new byte[] { (byte) number }, cache.generation());
            cache.error("error" + number, "invalid", cache.generation());
        }
        assertArrayEquals(new byte[] { 42 }, cache.bundle("model0",
                generation -> cache.remember("model0", new byte[] { 42 }, generation)));
        assertArrayEquals(new byte[] { 43 }, cache.bundle("error0",
                generation -> cache.remember("error0", new byte[] { 43 }, generation)));
        assertThrows(IOException.class, () -> cache.bundle("error16", generation -> fail("Error remains cached")));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for source fixture");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }
}
