package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YsmAnimationClockTest {
    @Test void timeUpdateReturnsAbsoluteSecondsAndDoesNotAccumulateConstants() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("HOLD", 40, 1, 0, "v.calls+=1;return .75;", null, null);
        assertEquals(15, clock.advance(0, context).timeTicks(), 1e-9);
        assertEquals(15, clock.advance(10, context).timeTicks(), 1e-9);
        assertEquals(15, clock.advance(20, context).timeTicks(), 1e-9);
        assertEquals(3, context.get("v.calls"), 1e-9);
        assertFalse(clock.advance(30, context).finished());
    }

    @Test void updateReceivesSpeedAdjustedRealDeltaAndSameTickNeverEvaluatesAgain() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("HOLD", 100, 2, 0,
                "v.calls+=1;v.delta=q.delta_time;return q.anim_time+q.delta_time;", null, null);
        clock.advance(0, context); clock.advance(0, context);
        assertEquals(1, context.get("v.calls"), 1e-9);
        assertEquals(20, clock.advance(10, context).timeTicks(), 1e-9);
        assertEquals(1, context.get("v.delta"), 1e-9);
        clock.advance(10, context);
        assertEquals(2, context.get("v.calls"), 1e-9);
        assertEquals(22, clock.advance(11, context).timeTicks(), 1e-9);
    }

    @Test void startDelayEvaluatesAtActivationOnceAndStartsAfterBeginningBlend() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("HOLD", 100, 2, 30, null,
                "v.starts+=1;v.initial=q.anim_time;return .5;", null);
        clock.initialize(context); clock.initialize(context);
        assertEquals(1, context.get("v.starts"), 1e-9);
        assertEquals(0, context.get("v.initial"), 1e-9);
        assertTrue(clock.advance(39, context).waiting());
        assertEquals(0, clock.advance(40, context).timeTicks(), 1e-9);
        assertEquals(10, clock.advance(45, context).timeTicks(), 1e-9);
        assertEquals(1, context.get("v.starts"), 1e-9);
    }

    @Test void loopDelayEvaluatesOnlyAtEachBoundaryAndRestartsAtZeroAfterWaiting() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("LOOP", 20, 1, 0, null, null,
                "v.delays+=1;v.end=q.anim_time;return .5;");
        clock.advance(0, context);
        assertFalse(clock.advance(20, context).crossedBoundary());
        YsmAnimationClock.Step end = clock.advance(21, context);
        assertTrue(end.crossedBoundary()); assertTrue(end.waiting()); assertFalse(end.restarted());
        assertEquals(20, end.timeTicks(), 1e-9);
        assertEquals(1, context.get("v.delays"), 1e-9);
        assertEquals(1, context.get("v.end"), 1e-9);
        assertTrue(clock.advance(25, context).waiting());
        assertTrue(clock.advance(30, context).waiting());
        assertEquals(1, context.get("v.delays"), 1e-9);
        YsmAnimationClock.Step restart = clock.advance(100, context);
        assertFalse(restart.crossedBoundary()); assertTrue(restart.restarted()); assertFalse(restart.waiting());
        assertEquals(0, restart.timeTicks(), 1e-9);
        assertTrue(restart.beforeTimeTicks() < 0);
        assertFalse(clock.advance(100, context).restarted());
        assertEquals(10, clock.advance(110, context).timeTicks(), 1e-9);
        assertTrue(clock.advance(121, context).crossedBoundary());
        assertEquals(2, context.get("v.delays"), 1e-9);
    }

    @Test void onceAndHoldIgnoreLoopDelayAndUseTheirOriginalBoundaryOperators() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock once = clock("ONCE", 20, 1, 0, null, null, "v.delays+=1;return 1;");
        YsmAnimationClock hold = clock("HOLD", 20, 1, 0, null, null, "v.delays+=1;return 1;");
        once.advance(0, context); hold.advance(0, context);
        assertTrue(once.advance(20, context).finished());
        assertFalse(hold.advance(20, context).finished());
        YsmAnimationClock.Step held = hold.advance(21, context);
        assertTrue(held.finished()); assertFalse(held.waiting()); assertFalse(held.restarted());
        assertEquals(20, held.timeTicks(), 1e-9);
        assertEquals(0, context.get("v.delays"), 1e-9);
        assertFalse(once.advance(21, context).crossedBoundary());
        assertFalse(hold.advance(22, context).crossedBoundary());
    }

    @Test void fieldFreeLoopKeepsNativeModuloAndProcessesOneSkippedCycleBoundary() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("LOOP", 20, 1, 0, null, null, null);
        assertTrue(clock.advance(0, context).beforeTimeTicks() < 0);
        assertFalse(clock.advance(20, context).crossedBoundary());
        YsmAnimationClock.Step jump = clock.advance(201, context);
        assertTrue(jump.crossedBoundary()); assertTrue(jump.restarted()); assertTrue(jump.finished());
        assertEquals(1, jump.timeTicks(), 1e-9);
        assertFalse(clock.advance(201, context).crossedBoundary());
        assertFalse(clock.advance(201, context).restarted());
        assertEquals(2, clock.advance(202, context).timeTicks(), 1e-9);
        assertEquals(0, context.get("v.delays"), 1e-9);
    }

    @Test void authoredAbsoluteTimeCanRewindAndReportsThePreviousTimeForEventSeeking() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("HOLD", 100, 1, 0, "q.target", null, null);
        context.query("q.target", .75);
        assertEquals(15, clock.advance(0, context).timeTicks(), 1e-9);
        context.query("q.target", .25);
        YsmAnimationClock.Step rewind = clock.advance(1, context);
        assertEquals(15, rewind.beforeTimeTicks(), 1e-9);
        assertEquals(5, rewind.timeTicks(), 1e-9);
        assertFalse(rewind.restarted()); assertFalse(rewind.crossedBoundary());
        context.query("q.target", .5);
        assertEquals(5, clock.advance(1, context).timeTicks(), 1e-9);
        assertEquals(10, clock.advance(2, context).timeTicks(), 1e-9);
        context.query("q.target", -.5);
        assertEquals(0, clock.advance(3, context).timeTicks(), 1e-9);
        context.query("q.target", 1);
        assertEquals(20, clock.advance(4, context).timeTicks(), 1e-9);
    }

    @Test void authoredHoldKeepsUpdatingAfterItsEndAndMaySeekBackWhileFinishedStaysSticky() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("HOLD", 20, 1, 0, "v.calls+=1;return q.target;", null, null);
        context.query("q.target", 1);
        assertEquals(20, clock.advance(0, context).timeTicks(), 1e-9);
        assertFalse(clock.advance(0, context).finished());
        context.query("q.target", 2);
        YsmAnimationClock.Step firstEnd = clock.advance(1, context);
        assertEquals(20, firstEnd.timeTicks(), 1e-9);
        assertTrue(firstEnd.crossedBoundary()); assertTrue(firstEnd.finished());
        context.query("q.target", .25);
        assertEquals(20, clock.advance(1, context).timeTicks(), 1e-9);
        YsmAnimationClock.Step rewind = clock.advance(2, context);
        assertEquals(20, rewind.beforeTimeTicks(), 1e-9);
        assertEquals(5, rewind.timeTicks(), 1e-9);
        assertTrue(rewind.finished()); assertFalse(rewind.crossedBoundary());
        context.query("q.target", 2);
        YsmAnimationClock.Step secondEnd = clock.advance(3, context);
        assertEquals(20, secondEnd.timeTicks(), 1e-9);
        assertTrue(secondEnd.finished()); assertFalse(secondEnd.crossedBoundary());
        context.query("q.target", .5);
        assertEquals(10, clock.advance(4, context).timeTicks(), 1e-9);
        assertEquals(5, context.get("v.calls"), 1e-9);
    }

    @Test void timingEvaluationRestoresAllQueriesButKeepsCallerOwnedScope() {
        Molang.Context context = new Molang.Context();
        context.query("q.anim_time", 77); context.query("q.delta_time", 88); context.query("q.marker", 99);
        Map<String,Object> queries = context.queryValues();
        context.set("c.owned", 3);
        YsmAnimationClock clock = clock("LOOP", 20, 1, 0,
                "c.owned+=1;return q.anim_time+q.delta_time;", "return q.anim_time;", "return .5;");
        clock.initialize(context); assertEquals(queries, context.queryValues());
        clock.advance(0, context); clock.advance(21, context);
        assertEquals(queries, context.queryValues());
        assertEquals(5, context.get("c.owned"), 1e-9);
    }

    @Test void zeroLengthAndHugeTimeJumpsRemainBoundedAndFinite() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock zero = clock("LOOP", 0, 1, 0, "1000000000", null,
                "v.delays+=1;return .1;");
        assertTrue(zero.advance(0, context).crossedBoundary());
        assertTrue(zero.advance(1, context).waiting());
        assertTrue(zero.advance(2, context).restarted());
        assertTrue(zero.advance(3, context).crossedBoundary());
        assertEquals(2, context.get("v.delays"), 1e-9);
        YsmAnimationClock large = clock("LOOP", 20, 2, 0, null, null, null);
        assertTrue(Double.isFinite(large.advance(Double.MAX_VALUE, context).timeTicks()));
    }

    @Test void missingNativeAnimationLengthUsesAnInfiniteEndWithoutCompleting() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock nativeClock = clock("HOLD", Double.POSITIVE_INFINITY, 2, 0, null, null, null);
        assertEquals(20, nativeClock.advance(10, context).timeTicks(), 1e-9);
        assertFalse(nativeClock.advance(100, context).finished());
        assertTrue(Double.isFinite(nativeClock.advance(Double.MAX_VALUE, context).timeTicks()));
        YsmAnimationClock scriptedClock = clock("ONCE", Double.POSITIVE_INFINITY, 1, 0,
                "q.anim_time+q.delta_time", null, "v.delays+=1;return 1;");
        assertEquals(10, scriptedClock.advance(10, context).timeTicks(), 1e-9);
        assertFalse(scriptedClock.advance(100, context).crossedBoundary());
        assertEquals(0, context.get("v.delays"), 1e-9);
    }

    @Test void zeroSpeedRemainsStoppedEvenWhenTheRealTickDifferenceOverflows() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("LOOP", 20, 0, -Double.MAX_VALUE, null, null, null);
        YsmAnimationClock.Step sampled = clock.advance(Double.MAX_VALUE, context);
        assertEquals(0, sampled.timeTicks(), 1e-9);
        assertFalse(sampled.crossedBoundary()); assertFalse(sampled.finished());
    }

    @Test void cancelDiscardsPlaybackAndRestartGetsANewActivationDelay() {
        Molang.Context context = new Molang.Context();
        YsmAnimationClock clock = clock("LOOP", 20, 1, 0, null, "v.starts+=1;return .5;", null);
        clock.initialize(context); clock.advance(15, context); clock.cancel();
        YsmAnimationClock.Step stopped = clock.advance(100, context);
        assertEquals(0, stopped.timeTicks(), 1e-9);
        assertFalse(stopped.finished()); assertFalse(stopped.crossedBoundary());
        clock.initialize(context); assertEquals(1, context.get("v.starts"), 1e-9);
        YsmAnimationClock restart = clock("LOOP", 20, 1, 100, null, "v.starts+=1;return .5;", null);
        restart.initialize(context); assertEquals(2, context.get("v.starts"), 1e-9);
        assertTrue(restart.advance(109, context).waiting());
        assertEquals(0, restart.advance(110, context).timeTicks(), 1e-9);
    }

    @Test void expressionReentryFailsAndStillRestoresQueryBindings() {
        Molang.Context context = new Molang.Context(); context.query("q.anim_time", 77);
        Map<String,Object> queries = context.queryValues();
        YsmAnimationClock clock = clock("HOLD", 100, 1, 0, "fn.sample()", null, null);
        context.functions((name, args) -> clock.advance(0, context).timeTicks());
        assertThrows(IllegalStateException.class, () -> clock.advance(0, context));
        assertEquals(queries, context.queryValues());
        context.functions((name, args) -> .5);
        assertEquals(10, clock.advance(0, context).timeTicks(), 1e-9);
    }

    @Test void failedStartDelayCanBeRetriedWithoutLosingTheDelay() {
        Molang.Context context = new Molang.Context(); context.query("q.anim_time", 77);
        Map<String,Object> queries = context.queryValues();
        YsmAnimationClock clock = clock("HOLD", 100, 1, 0, null, "fn.delay()", null);
        context.functions((name, args) -> { throw new IllegalArgumentException("unavailable"); });
        assertThrows(IllegalArgumentException.class, () -> clock.initialize(context));
        assertEquals(queries, context.queryValues());
        context.functions((name, args) -> .5);
        clock.initialize(context);
        assertTrue(clock.advance(9, context).waiting());
        assertEquals(0, clock.advance(10, context).timeTicks(), 1e-9);
    }

    private static YsmAnimationClock clock(String loop, double lengthTicks, double speed, double startTick,
                                           String update, String startDelay, String loopDelay) {
        return new YsmAnimationClock(loop, lengthTicks, speed, startTick,
                program(update), program(startDelay), program(loopDelay));
    }
    private static Molang.Program program(String expression) {
        return expression == null ? null : Molang.compile(expression);
    }
}
