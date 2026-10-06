package com.simmc.meplayeractions.protection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ResourceProtectionTest {
    private static final long MIB=1048576;
    @TempDir Path temporary;
    @Test void workerAndCompletionQueuesAreBoundedAndCallbacksRequireMainDrain() throws Exception {
        AtomicLong clock=new AtomicLong();var settings=withTasks(1,1,2,4*MIB,4*MIB,2);
        try(var runtime=new ResourceProtection(settings,clock::get)) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger callback=new AtomicInteger();List<ResourceError> failures=new ArrayList<>();
            assertTrue(runtime.submit(null,MIB,"asset_prepare",()->{entered.countDown();release.await();return 1;},callback::addAndGet,failures::add));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(runtime.submit(null,MIB,"asset_prepare",()->2,callback::addAndGet,failures::add));
            assertFalse(runtime.submit(null,MIB,"asset_prepare",()->3,callback::addAndGet,failures::add));
            assertEquals("asset_queue_full",failures.getFirst().code());assertEquals(0,callback.get());
            release.countDown();await(()->runtime.snapshot().get("tasks.pendingCompletions")==2);assertEquals(0,callback.get());
            runtime.drainCompletions();assertEquals(3,callback.get());assertEquals(0,runtime.snapshot().get("tasks.reservedBytes"));
        }
    }
    @Test void completedButUndrainedResultsContinueToReserveCallbackSpace() throws Exception {
        try(var runtime=new ResourceProtection(withTasks(1,2,2,4*MIB,4*MIB,1),System::nanoTime)) {
            List<ResourceError> failures=new ArrayList<>();assertTrue(runtime.submit(null,MIB,"asset_prepare",()->1,v->{},failures::add));
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);
            assertEquals(0,runtime.snapshot().get("tasks.reservedBytes"));assertEquals(MIB,runtime.snapshot().get("tasks.completionReservedBytes"));
            assertFalse(runtime.submit(null,MIB,"asset_prepare",()->2,v->{},failures::add));assertEquals("asset_queue_full",failures.getFirst().code());
            runtime.drainCompletions();assertTrue(runtime.submit(null,MIB,"asset_prepare",()->2,v->{},failures::add));
        }
    }
    @Test void perOwnerAdmissionCannotUseOtherPlayersReservedCapacity() throws Exception {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var runtime=new ResourceProtection(withTasks(1,3,1,4*MIB,MIB,4),System::nanoTime)) {
            List<ResourceError> failures=new ArrayList<>();assertTrue(runtime.submit(owner,MIB,"upload_validate",()->{entered.countDown();release.await();return 1;},v->{},failures::add));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(runtime.submit(owner,1,"upload_validate",()->1,v->{},failures::add));
            assertEquals("player_task_limit",failures.getFirst().code());assertTrue(runtime.submit(other,MIB,"upload_validate",()->1,v->{},failures::add));release.countDown();
        }
    }
    @Test void timeoutDoesNotReleaseRunningMemoryUntilAnInterruptIgnoringWorkerActuallyExits() throws Exception {
        AtomicLong clock=new AtomicLong();AtomicBoolean finish=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1);
        try(var runtime=new ResourceProtection(withTasks(1,2,2,MIB,MIB,3),clock::get)) {
            List<ResourceError> failures=new ArrayList<>();AtomicInteger successful=new AtomicInteger();
            assertTrue(runtime.submit(null,MIB,"upload_validate",()->{entered.countDown();while(!finish.get()) { try { Thread.sleep(5); }catch(InterruptedException ignored){} }return 1;},successful::addAndGet,failures::add));
            assertTrue(entered.await(5,TimeUnit.SECONDS));clock.set(2_000_000_000L);runtime.tick();
            assertEquals("validation_timeout",failures.getFirst().code());assertEquals(MIB,runtime.snapshot().get("tasks.reservedBytes"));
            assertFalse(runtime.submit(null,MIB,"upload_validate",()->1,v->{},failures::add));assertEquals("memory_limit",failures.getLast().code());
            finish.set(true);await(()->runtime.snapshot().get("tasks.reservedBytes")==0);runtime.drainCompletions();assertEquals(0,successful.get());
        } finally { finish.set(true); }
    }
    @Test void queuedOwnerCancellationReleasesItsBytesAndAllowsAFutureSession() throws Exception {
        UUID running=UUID.randomUUID(),queued=UUID.randomUUID();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger executed=new AtomicInteger();
        try(var runtime=new ResourceProtection(withTasks(1,3,2,4*MIB,2*MIB,4),System::nanoTime)) {
            assertTrue(runtime.submit(running,MIB,"asset_prepare",()->{entered.countDown();release.await();return 1;},v->{},v->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(runtime.submit(queued,MIB,"asset_prepare",()->executed.incrementAndGet(),v->{},v->{}));runtime.cancelOwner(queued);
            assertEquals(MIB,runtime.snapshot().get("tasks.reservedBytes"));runtime.drainCompletions();
            assertTrue(runtime.submit(queued,MIB,"asset_prepare",()->executed.incrementAndGet(),v->{},v->{}));release.countDown();
            await(()->runtime.snapshot().get("tasks.reservedBytes")==0);assertEquals(1,executed.get());
        }
    }
    @Test void validationFailureIsStructuredAndDoesNotExposePaths() throws Exception {
        List<ResourceError> errors=new ArrayList<>();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            assertTrue(runtime.submit(null,1,"upload_validate",()->{throw new IllegalArgumentException("C:/private/password");},v->fail(),errors::add));
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);runtime.drainCompletions();assertEquals("validation_failed",errors.getFirst().code());
            assertFalse(errors.getFirst().json().toString().contains("password"));
        }
    }
    @Test void ownerInvalidationReplacesStaleResultsWithCancellationBookkeepingAndAllowsReplacementWork() throws Exception {
        UUID owner=UUID.randomUUID();AtomicInteger successes=new AtomicInteger();List<ResourceError> failures=new ArrayList<>();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            assertTrue(runtime.submit(owner,1,"upload_validate",()->1,v->successes.incrementAndGet(),failures::add));
            assertTrue(runtime.submit(owner,1,"upload_validate",()->{throw new IllegalArgumentException();},v->successes.incrementAndGet(),failures::add));
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==2);runtime.cancelOwner(owner);runtime.cancelOwner(owner);runtime.drainCompletions();assertEquals(0,successes.get());
            assertEquals(2,failures.size());assertTrue(failures.stream().allMatch(error->error.code().equals("request_cancelled")));
            assertTrue(runtime.submit(owner,1,"upload_validate",()->1,v->successes.incrementAndGet(),failures::add));
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);runtime.drainCompletions();assertEquals(1,successes.get());assertEquals(2,failures.size());
        }
    }
    @Test void finishedDeleteLikeWorkReleasesServiceBookkeepingAfterOwnerDisconnect() throws Exception {
        UUID owner=UUID.randomUUID();Set<UUID> deleting=new HashSet<>(Set.of(owner));AtomicInteger committed=new AtomicInteger();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            assertTrue(runtime.submit(owner,1,"cleanup",()->true,value->{committed.incrementAndGet();deleting.remove(owner);},error->deleting.remove(owner)));
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);runtime.cancelOwner(owner);runtime.drainCompletions();
            assertEquals(0,committed.get());assertTrue(deleting.isEmpty());assertEquals(0,runtime.snapshot().get("tasks.completionReservedBytes"));
        }
    }
    @Test void overloadEscalatesImmediatelyAndRecoversOneStatePerHealthyInterval() {
        AtomicLong clock=new AtomicLong();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),clock::get)) {
            runtime.sample(9,170,0);assertEquals(ResourceProtection.State.EMERGENCY,runtime.state());assertFalse(runtime.acceptingNewWork());
            runtime.sample(20,10,0);runtime.sample(20,10,29_000_000_000L);assertEquals(ResourceProtection.State.EMERGENCY,runtime.state());
            runtime.sample(20,10,30_000_000_000L);assertEquals(ResourceProtection.State.PROTECTED,runtime.state());
            runtime.sample(20,10,60_000_000_000L);assertEquals(ResourceProtection.State.THROTTLED,runtime.state());
            assertEquals(.5,runtime.throttleFactor());runtime.sample(20,10,90_000_000_000L);assertEquals(ResourceProtection.State.NORMAL,runtime.state());
        }
    }
    @Test void unhealthySamplesResetTheRecoveryWindowAndCleanupCanStillRun() throws Exception {
        AtomicLong clock=new AtomicLong();List<ResourceError> failures=new ArrayList<>();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),clock::get)) {
            runtime.sample(12,100,0);runtime.sample(20,10,0);runtime.sample(17,60,29_000_000_000L);runtime.sample(20,10,30_000_000_000L);
            runtime.sample(20,10,59_000_000_000L);assertEquals(ResourceProtection.State.PROTECTED,runtime.state());
            assertFalse(runtime.submit(null,1,"asset_prepare",()->1,v->{},failures::add));assertEquals("tps_protection",failures.getFirst().code());
            assertTrue(runtime.submit(null,1,"catalog_delete",()->1,v->{},failures::add));
        }
    }
    @Test void emergencyDropsQueuedCosmeticWorkButRetainsQueuedCleanup() throws Exception {
        AtomicLong clock=new AtomicLong();AtomicInteger costly=new AtomicInteger(),cleanup=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var runtime=new ResourceProtection(withTasks(1,4,4,4*MIB,4*MIB,5),clock::get)) {
            runtime.submit(null,1,"asset_prepare",()->{entered.countDown();release.await();return 1;},v->{},v->{});assertTrue(entered.await(5,TimeUnit.SECONDS));
            runtime.submit(null,1,"asset_prepare",costly::incrementAndGet,v->{},v->{});
            runtime.submit(null,1,"cleanup",cleanup::incrementAndGet,v->{},v->{});runtime.sample(9,170,0);
            release.countDown();await(()->runtime.snapshot().get("tasks.reservedBytes")==0);runtime.drainCompletions();
            assertEquals(0,costly.get());assertEquals(1,cleanup.get());
        }
    }
    @Test void workerStructuredRefusalRetainsItsCodeAndRetryPolicy() throws Exception {
        List<ResourceError> errors=new ArrayList<>();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            runtime.submit(null,1,"validation",()->{throw new ResourceRejectedException(new ResourceError("model_complexity","validation",false,0));},v->fail(),errors::add);
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);runtime.drainCompletions();
            assertEquals("model_complexity",errors.getFirst().code());assertFalse(errors.getFirst().retryable());
        }
    }
    @Test void mainCommitFailureReportsOnceAndReleasesPendingMemory() throws Exception {
        List<ResourceError> errors=new ArrayList<>();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            runtime.submit(null,MIB,"validation",()->1,v->{throw new IllegalStateException("private filepath");},errors::add);
            await(()->runtime.snapshot().get("tasks.pendingCompletions")==1);runtime.drainCompletions();
            assertEquals(1,errors.size());assertEquals("commit_failed",errors.getFirst().code());
            assertEquals(0,runtime.snapshot().get("tasks.completionReservedBytes"));
        }
    }
    @Test void mainThreadTimeAndOperationBudgetsDeferNonessentialWork() {
        AtomicLong clock=new AtomicLong();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),clock::get)) {
            assertTrue(runtime.tryMainWork(64));assertFalse(runtime.tryWork());runtime.tick();assertTrue(runtime.tryWork());
            runtime.recordWork(3_000_000);assertFalse(runtime.tryWork());runtime.tick();assertTrue(runtime.tryWork());
        }
    }
    @Test void measuredPluginPressureProtectsServerEvenWhenTpsRemainsHealthy() {
        try(var protectedRuntime=new ResourceProtection(ResourceSettings.defaults(),()->0)) {
            protectedRuntime.recordWork(45_000_000);protectedRuntime.tick();protectedRuntime.sample(20,30,0);
            assertEquals(ResourceProtection.State.PROTECTED,protectedRuntime.state());assertFalse(protectedRuntime.acceptingNewWork());
        }
        try(var emergencyRuntime=new ResourceProtection(ResourceSettings.defaults(),()->0)) {
            emergencyRuntime.recordWork(90_000_000);emergencyRuntime.tick();emergencyRuntime.sample(20,30,0);
            assertEquals(ResourceProtection.State.EMERGENCY,emergencyRuntime.state());
        }
    }
    @Test void relationshipLimitsAreSharedAndRetainExistingAdmissionsFirst() {
        ResourceSettings d=ResourceSettings.defaults();var models=new ResourceSettings.Models(10,10,2,1,20,20,0,MIB,10);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var runtime=new ResourceProtection(new ResourceSettings(d.tasks(),d.network(),d.protection(),models,d.disk(),d.complexity()),System::nanoTime)) {
            assertEquals(Set.of(first,second),runtime.reserveRelationships("server",a,List.of(first,second)));
            assertTrue(runtime.reserveRelationships("private",b,List.of(first,third)).isEmpty());
            assertEquals(Set.of(second,third),runtime.reserveRelationships("server",a,List.of(third,second)));
            runtime.releaseRelationships("server",a);assertEquals(Set.of(first),runtime.reserveRelationships("private",b,List.of(first)));
            assertEquals(1,runtime.snapshot().get("models.relations"));
        }
    }
    @Test void protectedRelationshipRefreshReleasesRevokedViewersWithoutAddingNewOnes() {
        UUID owner=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            runtime.reserveRelationships("server",owner,List.of(a,b));runtime.sample(12,100,System.nanoTime());
            assertEquals(Set.of(b),runtime.reserveRelationships("server",owner,List.of(b,c)));assertEquals(1,runtime.snapshot().get("models.relations"));
        }
    }
    @Test void newDisguiseRateAndPerPlayerCooldownAreSharedWithoutPermanentOwnerBlocking() {
        AtomicLong clock=new AtomicLong();UUID owner=UUID.randomUUID();
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),clock::get)) {
            assertNull(runtime.tryDisguise(owner));assertEquals("disguise_cooldown",runtime.tryDisguise(owner).code());
            runtime.cancelOwner(owner);assertNull(runtime.tryDisguise(owner));runtime.sample(12,100,0);
            assertEquals("tps_protection",runtime.tryDisguise(UUID.randomUUID()).code());
        }
    }
    @Test void diskReservationAndCachedStatusUseCountersInsteadOfScanningFiles() {
        ResourceSettings d=ResourceSettings.defaults();var disk=new ResourceSettings.Disk(MIB,0);
        try(var runtime=new ResourceProtection(new ResourceSettings(d.tasks(),d.network(),d.protection(),d.models(),disk,d.complexity()),System::nanoTime)) {
            UUID owner=UUID.randomUUID();assertNull(runtime.reserveTemporary(owner,MIB,temporary));
            assertEquals("disk_budget",runtime.reserveTemporary(owner,1,temporary).code());runtime.releaseTemporary(owner,MIB);
            assertEquals(0,runtime.snapshot().get("disk.temporaryBytes"));runtime.gauge("network.activeTransfers",()->4);
            runtime.gauge("network.failedGauge",()->{throw new IllegalStateException();});
            assertEquals(4,runtime.snapshot().get("network.activeTransfers"));assertEquals(-1,runtime.snapshot().get("network.failedGauge"));
            assertTrue(runtime.statusLines("network").stream().anyMatch(line->line.contains("network.activeTransfers")));
        }
    }
    @Test void immutableWireAndParsedCachesShareOneGlobalMemoryBudget() {
        ResourceSettings d=ResourceSettings.defaults();var models=new ResourceSettings.Models(10,10,10,10,20,20,0,MIB,10);
        try(var runtime=new ResourceProtection(new ResourceSettings(d.tasks(),d.network(),d.protection(),models,d.disk(),d.complexity()),System::nanoTime)) {
            Object wire=new Object(),parsed=new Object();assertTrue(runtime.reserveCache("wire",wire,3*MIB/4));
            assertFalse(runtime.reserveCache("parsed",parsed,MIB/2));assertEquals(3*MIB/4,runtime.snapshot().get("models.cachedBytes"));
            runtime.releaseCache("wire",wire);assertTrue(runtime.reserveCache("parsed",parsed,MIB/2));
            assertEquals(MIB/2,runtime.snapshot().get("models.cachedBytes"));assertEquals(3*MIB/4,runtime.snapshot().get("models.peakCachedBytes"));
        }
    }
    @Test void duplicateImmutableRetainsNeverDoubleChargeAndDifferentNamespacesRemainSeparate() {
        try(var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime)) {
            String identity="same-hash";assertTrue(runtime.reserveCache("wire",identity,100));assertTrue(runtime.reserveCache("wire",identity,100));
            assertTrue(runtime.reserveCache("parsed",identity,200));assertEquals(300,runtime.snapshot().get("models.cachedBytes"));
            assertThrows(IllegalStateException.class,()->runtime.reserveCache("wire",identity,101));
            runtime.releaseCache("wire",identity);runtime.releaseCache("wire",identity);assertEquals(200,runtime.snapshot().get("models.cachedBytes"));
        }
    }
    @Test void immutableCacheEntriesStayChargedAcrossRuntimeCloseUntilTheirOwnersReleaseThem() {
        var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime);Object identity=new Object();
        assertTrue(runtime.reserveCache("parsed",identity,100));runtime.close();assertEquals(100,runtime.snapshot().get("models.cachedBytes"));
        assertFalse(runtime.reserveCache("wire",new Object(),100));runtime.releaseCache("parsed",identity);assertEquals(0,runtime.snapshot().get("models.cachedBytes"));
    }
    @Test void immutableCacheEntryCapIsSharedEvenWhenThereIsFreeByteBudget() {
        ResourceSettings d=ResourceSettings.defaults();var models=new ResourceSettings.Models(10,10,10,10,20,20,0,MIB,1);
        try(var runtime=new ResourceProtection(new ResourceSettings(d.tasks(),d.network(),d.protection(),models,d.disk(),d.complexity()),System::nanoTime)) {
            assertTrue(runtime.reserveCache("wire","first",100));assertFalse(runtime.reserveCache("parsed","second",100));
            runtime.releaseCache("wire","first");assertTrue(runtime.reserveCache("parsed","second",100));assertEquals(1,runtime.snapshot().get("models.cachedEntries"));
        }
    }
    @Test void closeDiscardsLateCompletionAndRejectsSubsequentSubmission() throws Exception {
        AtomicBoolean finish=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();
        var runtime=new ResourceProtection(ResourceSettings.defaults(),System::nanoTime);
        try {
            runtime.submit(null,MIB,"asset_prepare",()->{entered.countDown();while(!finish.get()) {try {Thread.sleep(5);}catch(InterruptedException ignored){}}return 1;},callbacks::addAndGet,v->callbacks.incrementAndGet());
            assertTrue(entered.await(5,TimeUnit.SECONDS));runtime.close();assertEquals(MIB,runtime.snapshot().get("tasks.reservedBytes"));
            finish.set(true);await(()->runtime.snapshot().get("tasks.reservedBytes")==0);runtime.drainCompletions();assertEquals(0,callbacks.get());
            List<ResourceError> errors=new ArrayList<>();assertFalse(runtime.submit(null,1,"cleanup",()->1,v->{},errors::add));assertEquals("server_stopped",errors.getFirst().code());
        } finally { finish.set(true);runtime.close(); }
    }
    private static ResourceSettings withTasks(int threads,int queue,int ownerJobs,long bytes,long ownerBytes,int completions) {
        ResourceSettings d=ResourceSettings.defaults();return new ResourceSettings(new ResourceSettings.Tasks(threads,queue,ownerJobs,bytes,ownerBytes,completions,4*MIB,8,20,20,40),d.network(),d.protection(),d.models(),d.disk(),d.complexity());
    }
    private static void await(BooleanSupplier ready) throws InterruptedException {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(1);assertTrue(ready.getAsBoolean(),"Worker did not reach expected bounded state");
    }
}
