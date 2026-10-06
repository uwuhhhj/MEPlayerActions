package com.simmc.meplayeractions.me;

import com.simmc.meplayeractions.config.ModelComplexityLimits;
import com.simmc.meplayeractions.protection.ResourceRejectedException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ServerTemplatePreparationTest {
    private static byte[] model(String expression) {
        return ("{\"mpa_runtime\":true,\"animations\":[{\"name\":\"idle\",\"animators\":{"
                +"\"00000000-0000-0000-0000-000000000001\":{\"name\":\"body\",\"type\":\"bone\",\"keyframes\":["
                +"{\"channel\":\"scale\",\"time\":0,\"interpolation\":\"linear\",\"data_points\":[{\"x\":\""+expression+"\"}]}]}}}]}")
                .getBytes(StandardCharsets.UTF_8);
    }
    @Test void lookupDoesNoSourceIoAndRepeatedPendingRequestsQueueOneImmutablePreparation() {
        AtomicInteger reads=new AtomicInteger();ArrayDeque<Runnable> work=new ArrayDeque<>();
        try(var cache=new ServerTemplatePreparation(name->{reads.incrementAndGet();return new ByteArrayInputStream(model("variable.scale"));},
                work::add,ModelComplexityLimits.defaults(),4,1_000_000,System::nanoTime)) {
            for(int i=0;i<20;i++) {
                var blocked=assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));
                assertEquals("asset_preparing",blocked.error().code());assertTrue(blocked.error().retryable());
            }
            assertEquals(0,reads.get());assertEquals(1,work.size());assertEquals(0,cache.metrics().bytes());
            work.remove().run();var model=cache.ensure("player").orElseThrow();
            assertSame(model,cache.ensure("player").orElseThrow());assertEquals(1,reads.get());assertTrue(work.isEmpty());
            assertEquals("variable.scale",model.clips().getFirst().bones().getFirst().frames().getFirst().pre().x());
            assertThrows(UnsupportedOperationException.class,()->model.clips().clear());
        }
    }
    @Test void bothShippedNativeSourcesProduceCompleteImmutableClipsAndPrecompiledAxes() {
        ArrayDeque<Runnable> work=new ArrayDeque<>();AtomicInteger reads=new AtomicInteger();
        try(var cache=new ServerTemplatePreparation(name->{reads.incrementAndGet();return Files.newInputStream(Path.of("examples").resolve(name));},
                work::add,ModelComplexityLimits.defaults(),2,20L*1024*1024,System::nanoTime)) {
            for(String id:List.of("ysm_01_jk","ysm_02_jk")) {
                assertThrows(ResourceRejectedException.class,()->cache.ensure(id));work.remove().run();
                var prepared=cache.ensure(id).orElseThrow();assertFalse(prepared.clips().isEmpty());int formulas=0,frames=0;
                for(var clip:prepared.clips())for(var bone:clip.bones())for(var frame:bone.frames()) {
                    frames++;
                    for(var point:List.of(frame.pre(),frame.post()))for(String axis:List.of("x","y","z")) {
                        String text=point.axis(axis);
                        try{Double.parseDouble(text);}catch(NumberFormatException expression){assertNotNull(prepared.runtime().expression(text));formulas++;}
                    }
                }
                assertTrue(frames>1000);assertTrue(formulas>100);assertSame(prepared,cache.ensure(id).orElseThrow());
            }
            assertEquals(2,reads.get());assertTrue(cache.metrics().bytes()<20L*1024*1024);assertTrue(work.isEmpty());
        }
    }
    @Test void activeNativeDataStaysChargedWhileUnneededEmptyEntriesCanBeEvicted() {
        ArrayDeque<Runnable> work=new ArrayDeque<>();
        try(var cache=new ServerTemplatePreparation(name->name.contains("player")?new ByteArrayInputStream(model("1")):null,
                work::add,ModelComplexityLimits.defaults(),2,1_000_000,System::nanoTime)) {
            assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));work.remove().run();
            var nativeModel=cache.ensure("player").orElseThrow();long bytes=cache.metrics().bytes();
            assertThrows(ResourceRejectedException.class,()->cache.ensure("ordinary"));work.remove().run();assertTrue(cache.ensure("ordinary").isEmpty());
            assertThrows(ResourceRejectedException.class,()->cache.ensure("another"));work.remove().run();
            assertEquals(2,cache.metrics().entries());assertEquals(bytes+64,cache.metrics().bytes());
            assertSame(nativeModel,cache.ensure("player").orElseThrow());cache.close();assertEquals(0,cache.metrics().bytes());
        }
    }
    @Test void permanentInvalidOrOversizedNativeSourcesDoNotRetryCompilation() {
        for(String expression:List.of("unbalanced(","variable.scale")) {
            AtomicInteger reads=new AtomicInteger();ArrayDeque<Runnable> work=new ArrayDeque<>();long budget=expression.equals("unbalanced(")?1_000_000:1;
            try(var cache=new ServerTemplatePreparation(name->{reads.incrementAndGet();return new ByteArrayInputStream(model(expression));},
                    work::add,ModelComplexityLimits.defaults(),2,budget,System::nanoTime)) {
                assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));work.remove().run();
                for(int i=0;i<5;i++) {
                    var failure=assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));
                    assertFalse(failure.error().retryable());assertEquals(budget==1?"asset_too_large":"invalid_model",failure.error().code());
                }
                assertEquals(1,reads.get());assertTrue(work.isEmpty());assertEquals(0,cache.metrics().bytes());
            }
        }
    }
    @Test void busyBackoffAndShutdownCannotPublishLateWork() {
        AtomicLong clock=new AtomicLong();AtomicInteger submits=new AtomicInteger();ArrayDeque<Runnable> work=new ArrayDeque<>();
        var cache=new ServerTemplatePreparation(name->new ByteArrayInputStream(model("1")),task->{
            if(submits.getAndIncrement()==0)throw new RejectedExecutionException();work.add(task);
        },ModelComplexityLimits.defaults(),2,1_000_000,clock::get);
        var busy=assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));assertEquals("asset_queue_full",busy.error().code());
        assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));assertEquals(1,submits.get());
        clock.set(6_000_000_000L);assertThrows(ResourceRejectedException.class,()->cache.ensure("player"));assertEquals(2,submits.get());
        Runnable delayed=work.remove();cache.close();delayed.run();assertEquals(0,cache.metrics().entries());assertEquals(0,cache.metrics().bytes());
        assertEquals("request_cancelled",assertThrows(ResourceRejectedException.class,()->cache.ensure("player")).error().code());
    }
}
