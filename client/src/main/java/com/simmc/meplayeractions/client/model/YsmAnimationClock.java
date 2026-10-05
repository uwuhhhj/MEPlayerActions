package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.expression.Molang;
import java.util.Map;
import java.util.Objects;

/** Per-playback animation clock; the controller owns blending, events, pause and Molang scope.
 * Time update expressions return an absolute time in seconds and may move time backwards;
 * the controller repositions event cursors when that happens. This bounded implementation
 * processes at most one loop boundary per sampled tick, without replaying skipped cycles.
 */
final class YsmAnimationClock {
    record Step(double beforeTimeTicks, double timeTicks, boolean crossedBoundary,
                boolean waiting, boolean finished, boolean restarted) { }

    private static final double FIRST_EVENT_TICK = -1e-5;
    private final String loop;
    private final double lengthTicks, speed, startTick;
    private Molang.Program animTimeUpdate, startDelay, loopDelay;
    private final boolean authoredTiming;
    private boolean initialized, cancelled, evaluating, finished, terminal, loopWaiting;
    private double startWaitUntil, loopWaitUntil, lastRealTick, lastSampleTick = Double.NaN;
    private double timeTicks, previousTicks = FIRST_EVENT_TICK, tickOffset;

    YsmAnimationClock(String loop, double lengthTicks, double speed, double startTick,
                      Molang.Program animTimeUpdate, Molang.Program startDelay, Molang.Program loopDelay) {
        this.loop = Objects.requireNonNull(loop);
        if (!loop.equals("LOOP") && !loop.equals("ONCE") && !loop.equals("HOLD"))
            throw new IllegalArgumentException("Invalid animation loop");
        // Native YSM clips without animation_length use positive infinity.
        if (Double.isNaN(lengthTicks) || lengthTicks < 0 || !Double.isFinite(speed) || speed < 0
                || !Double.isFinite(startTick)) throw new IllegalArgumentException("Invalid animation clock");
        this.lengthTicks = lengthTicks; this.speed = speed; this.startTick = startTick;
        this.animTimeUpdate = animTimeUpdate; this.startDelay = startDelay; this.loopDelay = loopDelay;
        authoredTiming = animTimeUpdate != null || startDelay != null || loopDelay != null;
        lastRealTick = startTick; startWaitUntil = startTick;
    }

    /** Evaluate start_delay once in the activation scope, even when a beginning blend is pending. */
    void initialize(Molang.Context context) {
        if (cancelled || initialized) return;
        if (evaluating) throw new IllegalStateException("Animation clock evaluation is not reentrant");
        double delay = delayTicks(startDelay, 0, 0, context);
        if (cancelled) return;
        initialized = true;
        startWaitUntil = add(startTick, delay);
        lastRealTick = startWaitUntil;
    }

    Step advance(double realTick, Molang.Context context) {
        if (!Double.isFinite(realTick)) throw new IllegalArgumentException("Invalid animation tick");
        if (evaluating) throw new IllegalStateException("Animation clock evaluation is not reentrant");
        if (cancelled) return snapshot(false, false, false);
        initialize(context);
        if (cancelled) return snapshot(false, false, false);
        // A render can sample a controller repeatedly at the same world tick. Boundary flags
        // belong to the first sample only, just like expression side effects and event cursors.
        if (!Double.isNaN(lastSampleTick) && realTick <= lastSampleTick)
            return snapshot(false, isWaiting(realTick), false);
        if (realTick < startWaitUntil) { lastSampleTick = realTick; return snapshot(false, true, false); }
        // Authored HOLD clocks may seek away from their last frame after completion;
        // their finished query remains sticky while their absolute update keeps running.
        if (terminal && !(loop.equals("HOLD") && animTimeUpdate != null)) {
            lastSampleTick = realTick; return snapshot(false, false, false);
        }
        if (loopWaiting) {
            if (realTick < loopWaitUntil) { lastSampleTick = realTick; return snapshot(false, true, false); }
            // A delayed cycle starts at zero on the first available frame. Extra real time
            // across this wait is deliberately discarded; it cannot cause recursive loops.
            loopWaiting = false; timeTicks = 0; previousTicks = FIRST_EVENT_TICK;
            lastRealTick = lastSampleTick = realTick;
            return snapshot(false, false, true);
        }
        double before = previousTicks;
        double deltaTicks = multiply(Math.max(0, realTick - lastRealTick), speed);
        double candidate;
        if (!authoredTiming) {
            double rawElapsed = multiply(Math.max(0, realTick - startTick), speed);
            candidate = Math.max(0, rawElapsed - tickOffset);
        } else {
            candidate = animTimeUpdate == null ? add(timeTicks, deltaTicks)
                    : secondsToTicks(evaluate(animTimeUpdate, timeTicks / 20, deltaTicks / 20, context));
        }
        if (cancelled) return snapshot(false, false, false);
        boolean boundary = loop.equals("ONCE") ? candidate >= lengthTicks : candidate > lengthTicks;
        boolean crossed = boundary && (!loop.equals("HOLD") || !terminal);
        boolean restarted = false, waiting = false;
        if (boundary) {
            if (loop.equals("LOOP")) {
                double delay = delayTicks(loopDelay, lengthTicks / 20, deltaTicks / 20, context);
                if (cancelled) return snapshot(false, false, false);
                if (delay > 0) {
                    timeTicks = lengthTicks; loopWaiting = waiting = true;
                    loopWaitUntil = add(realTick, delay);
                } else {
                    timeTicks = lengthTicks > 0 ? candidate % lengthTicks : 0;
                    restarted = true;
                    if (!authoredTiming) {
                        double rawElapsed = multiply(Math.max(0, realTick - startTick), speed);
                        tickOffset = rawElapsed - timeTicks;
                    }
                }
            } else { timeTicks = lengthTicks; terminal = true; }
            finished = true;
        } else timeTicks = candidate;
        lastRealTick = lastSampleTick = realTick;
        previousTicks = timeTicks;
        return new Step(before, timeTicks, crossed, waiting, finished, restarted);
    }

    /** Freeze the animation time at an explicit native controller STOP. */
    double stoppingTimeTicks(double realTick) {
        // OpenYSM startEndingTransition saves the current adjusted clock, capped at clip length.
        // An authored absolute clock freezes its last evaluated value when explicitly stopped.
        double sampled = authoredTiming ? timeTicks
                : Math.max(0, multiply(Math.max(0, realTick - startTick), speed) - tickOffset);
        return Math.min(sampled, lengthTicks);
    }

    /** Discard the playback and expression references; restarting creates a new clock. */
    void cancel() {
        cancelled = true; animTimeUpdate = startDelay = loopDelay = null;
        timeTicks = tickOffset = 0; previousTicks = FIRST_EVENT_TICK;
        initialized = finished = terminal = loopWaiting = false;
        lastSampleTick = Double.NaN; lastRealTick = startWaitUntil = loopWaitUntil = 0;
    }

    private Step snapshot(boolean boundary, boolean waiting, boolean restarted) {
        double before = restarted ? FIRST_EVENT_TICK : previousTicks;
        if (restarted) previousTicks = timeTicks;
        return new Step(before, timeTicks, boundary, waiting, finished, restarted);
    }
    private boolean isWaiting(double realTick) {
        return realTick < startWaitUntil || loopWaiting;
    }
    private double delayTicks(Molang.Program program, double animSeconds, double deltaSeconds, Molang.Context context) {
        return program == null ? 0 : secondsToTicks(evaluate(program, animSeconds, deltaSeconds, context));
    }
    private double evaluate(Molang.Program program, double animSeconds, double deltaSeconds, Molang.Context context) {
        Objects.requireNonNull(context);
        Map<String,Object> queries = context.queryValues(); evaluating = true;
        try {
            context.query("query.anim_time", animSeconds); context.query("query.delta_time", deltaSeconds);
            return program.evaluate(context);
        } finally { context.restoreQueries(queries); evaluating = false; }
    }
    private static double secondsToTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        return multiply(seconds, 20);
    }
    private static double multiply(double a, double b) {
        if (a == 0 || b == 0) return 0;
        double value = a * b; return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }
    private static double add(double a, double b) {
        double value = a + b; return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }
}
