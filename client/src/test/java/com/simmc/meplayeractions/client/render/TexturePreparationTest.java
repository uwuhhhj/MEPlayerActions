package com.simmc.meplayeractions.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TexturePreparationTest {
    @Test
    void boundsRequestsCoalescesAndKeepsCancelledWorkerReservationUntilExit() throws Exception {
        TexturePreparation queue = new TexturePreparation(2, 128);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        try {
            assertTrue(queue.submit("a", () -> true, memory -> {
                memory.reserve(80);
                running.countDown();
                assertTrue(exit.await(5, TimeUnit.SECONDS));
                return prepared("a", 1, new ArrayList<>(), closed);
            }, (success, reason) -> assertFalse(success)));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertFalse(queue.submit("a", () -> true, memory -> null, (success, reason) -> fail()));
            assertTrue(queue.submit("b", () -> true, memory -> null, (success, reason) -> assertFalse(success)));
            assertFalse(queue.submit("c", () -> true, memory -> null, (success, reason) -> fail()));
            queue.cancel("a");
            queue.cancel("b");
            queue.pump();
            assertEquals(80L, queue.diagnostics().get("reservedBytes"));
            assertEquals(1, queue.diagnostics().get("requests"));
            assertEquals(0, closed.get());
            exit.countDown();
            awaitReady(queue, 1);
            queue.pump();
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
            assertEquals(0, queue.diagnostics().get("requests"));
            assertEquals(1, closed.get());
        } finally {
            exit.countDown();
            queue.close();
        }
    }

    @Test
    void uploadsOnlyOneTexturePerPumpAndRotatesMultiTextureJobs() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 128);
        List<String> uploads = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        try {
            for (String key : List.of("a", "b")) {
                assertTrue(queue.submit(key, () -> true, memory -> {
                    memory.reserve(32);
                    return prepared(key, key.equals("a") ? 2 : 3, uploads, closed);
                }, (success, reason) -> {
                    assertTrue(success, reason);
                    completed.incrementAndGet();
                }));
            }
            awaitReady(queue, 2);
            for (int tick = 0; tick < 5; tick++) {
                int before = uploads.size();
                queue.pump();
                assertEquals(before + 1, uploads.size());
            }
            assertEquals(List.of("a", "b", "a", "b", "b"), uploads);
            assertEquals(2, completed.get());
            assertEquals(2, closed.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    @Test
    void waitsForWorkingMemoryAndDoesNotRedecodeWhenUploadWaits() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        AtomicInteger decodes = new AtomicInteger();
        AtomicBoolean room = new AtomicBoolean(false);
        AtomicInteger closed = new AtomicInteger();
        try {
            assertTrue(queue.submit("a", () -> true, memory -> {
                memory.reserve(80);
                decodes.incrementAndGet();
                return new TexturePreparation.Prepared() {
                    @Override
                    public TexturePreparation.Step upload() {
                        return room.get() ? new TexturePreparation.Step(true, true) : TexturePreparation.Step.WAIT;
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }, (success, reason) -> assertTrue(success, reason)));
            assertTrue(queue.submit("b", () -> true, memory -> {
                memory.reserve(80);
                decodes.incrementAndGet();
                return prepared("b", 1, new ArrayList<>(), closed);
            }, (success, reason) -> assertTrue(success, reason)));
            awaitReady(queue, 1);
            queue.pump();
            queue.pump();
            assertEquals(1, decodes.get());
            assertEquals(80L, queue.diagnostics().get("reservedBytes"));
            room.set(true);
            queue.pump();
            awaitReady(queue, 1);
            queue.pump();
            assertEquals(2, decodes.get());
            assertEquals(2, closed.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    @Test
    void revocationAndUploadFailureCloseStagedResourcesWithoutCommit() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        try {
            assertTrue(queue.submit("a", current::get, memory -> {
                memory.reserve(50);
                return new TexturePreparation.Prepared() {
                    @Override
                    public TexturePreparation.Step upload() {
                        uploads.incrementAndGet();
                        return new TexturePreparation.Step(true, false);
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }, (success, reason) -> assertFalse(success)));
            awaitReady(queue, 1);
            queue.pump();
            current.set(false);
            queue.pump();
            assertEquals(1, uploads.get());
            assertEquals(1, closed.get());
            assertTrue(queue.submit("b", () -> true, memory -> {
                memory.reserve(50);
                return new TexturePreparation.Prepared() {
                    @Override
                    public TexturePreparation.Step upload() {
                        throw new IllegalStateException("GPU failed");
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }, (success, reason) -> {
                assertFalse(success);
                assertTrue(reason.contains("GPU failed"));
            }));
            awaitReady(queue, 1);
            queue.pump();
            assertEquals(2, closed.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    @Test
    void rejectsOversizedReservationBeforeNativeWork() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        AtomicBoolean allocated = new AtomicBoolean();
        try {
            assertTrue(queue.submit("a", () -> true, memory -> {
                memory.reserve(101);
                allocated.set(true);
                return null;
            }, (success, reason) -> {
                assertFalse(success);
                assertTrue(reason.contains("budget"));
            }));
            awaitReady(queue, 1);
            queue.pump();
            assertFalse(allocated.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    private static TexturePreparation.Prepared prepared(String key, int count, List<String> uploads,
            AtomicInteger closed) {
        return new TexturePreparation.Prepared() {
            private int remaining = count;

            @Override
            public TexturePreparation.Step upload() {
                uploads.add(key);
                return new TexturePreparation.Step(true, --this.remaining == 0);
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
    }

    @Test
    void throwingCleanupAndCallbackStillReleaseReservationsAndAllowOtherJobs() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        AtomicInteger completed = new AtomicInteger();
        try {
            assertTrue(queue.submit("a", () -> true, memory -> {
                memory.reserve(50);
                return new TexturePreparation.Prepared() {
                    @Override
                    public TexturePreparation.Step upload() {
                        return new TexturePreparation.Step(false, true);
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("cleanup failed");
                    }
                };
            }, (success, reason) -> {
                assertFalse(success);
                throw new IllegalStateException("callback failed");
            }));
            assertTrue(queue.submit("b", () -> true, memory -> {
                memory.reserve(50);
                return prepared("b", 1, new ArrayList<>(), new AtomicInteger());
            }, (success, reason) -> {
                assertTrue(success);
                completed.incrementAndGet();
            }));
            awaitReady(queue, 2);
            queue.pump();
            queue.pump();
            assertEquals(1, completed.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
            assertEquals(0, queue.diagnostics().get("requests"));
        } finally {
            queue.close();
        }
    }

    @Test
    void cancelledCurrentWaiterCanRetryAfterWorkerExitWithoutStaleCommit() throws Exception {
        TexturePreparation queue = new TexturePreparation(1, 100);
        AtomicInteger closed = new AtomicInteger();
        List<String> uploads = new ArrayList<>();
        AtomicBoolean retry = new AtomicBoolean();
        try {
            assertTrue(queue.submit("old", () -> true, memory -> {
                memory.reserve(80);
                return prepared("old", 1, uploads, closed);
            }, (success, reason) -> {
                assertFalse(success);
                assertEquals("cancelled", reason);
                retry.set(true);
            }));
            awaitReady(queue, 1);
            queue.cancel("old");
            assertFalse(queue.submit("new", () -> true, memory -> null, (success, reason) -> fail()));
            queue.pump();
            assertTrue(retry.get());
            assertTrue(uploads.isEmpty());
            assertTrue(queue.submit("new", () -> true, memory -> {
                memory.reserve(80);
                return prepared("new", 1, uploads, closed);
            }, (success, reason) -> assertTrue(success)));
            awaitReady(queue, 1);
            queue.pump();
            assertEquals(List.of("new"), uploads);
            assertEquals(2, closed.get());
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    @Test
    void stoppingClosesQueuedResultsAndLateWorkerPixelsWhileKeepingItsQuotaUntilExit() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        assertTrue(queue.submit("a", () -> true, memory -> {
            memory.reserve(80);
            running.countDown();
            assertTrue(exit.await(5, TimeUnit.SECONDS));
            return prepared("a", 1, new ArrayList<>(), closed);
        }, (success, reason) -> fail("Stopping worker must not invoke UI callbacks")));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        queue.close();
        assertEquals(80L, queue.diagnostics().get("reservedBytes"));
        assertEquals(1, queue.diagnostics().get("requests"));
        exit.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Number) queue.diagnostics().get("requests")).intValue() > 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, queue.diagnostics().get("requests"));
        assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        assertEquals(1, closed.get());
    }

    @Test
    void failingUploadConsumesTheTickAllowanceAndAllocationErrorsRemainRetryable() throws Exception {
        TexturePreparation queue = new TexturePreparation(8, 100);
        AtomicInteger uploads = new AtomicInteger();
        try {
            assertTrue(queue.submit("a", () -> true, memory -> new TexturePreparation.Prepared() {
                @Override
                public TexturePreparation.Step upload() {
                    uploads.incrementAndGet();
                    throw new IllegalStateException("GPU partially registered before failing");
                }

                @Override
                public void close() {
                }
            }, (success, reason) -> assertFalse(success)));
            assertTrue(queue.submit("b", () -> true, memory -> new TexturePreparation.Prepared() {
                @Override
                public TexturePreparation.Step upload() {
                    uploads.incrementAndGet();
                    return new TexturePreparation.Step(true, true);
                }

                @Override
                public void close() {
                }
            }, (success, reason) -> assertTrue(success)));
            awaitReady(queue, 2);
            queue.pump();
            assertEquals(1, uploads.get());
            queue.pump();
            assertEquals(2, uploads.get());
            assertTrue(queue.submit("oom", () -> true, memory -> {
                memory.reserve(80);
                throw new OutOfMemoryError("Native pixel allocation failed");
            }, (success, reason) -> {
                assertFalse(success);
                assertTrue(reason.contains("resource:"));
                assertFalse(reason.contains("decode:"));
            }));
            awaitReady(queue, 1);
            queue.pump();
            assertEquals(0L, queue.diagnostics().get("reservedBytes"));
        } finally {
            queue.close();
        }
    }

    private static void awaitReady(TexturePreparation queue, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Number) queue.diagnostics().get("ready")).intValue() < count && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(count, queue.diagnostics().get("ready"));
    }
}
