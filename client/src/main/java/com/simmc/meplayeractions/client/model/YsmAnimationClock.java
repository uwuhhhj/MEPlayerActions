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
    record Step(double beforeTimeTicks, double timeTicks, boolean crossedBoundary, boolean waiting, boolean finished,
            boolean restarted) {
    }

    private static final double FIRST_EVENT_TICK = -1e-5;
    private final String loop;
    private final double lengthTicks, speed, startTick;
    private Molang.Program animTimeUpdate, startDelay, loopDelay;
    private final boolean authoredTiming;
    private boolean initialized, cancelled, evaluating, finished, terminal, loopWaiting;
    private double startWaitUntil, loopWaitUntil, lastRealTick, lastSampleTick = Double.NaN;
    private double timeTicks, previousTicks = FIRST_EVENT_TICK, tickOffset;

    YsmAnimationClock(String loop, double lengthTicks, double speed, double startTick, Molang.Program animTimeUpdate,
            Molang.Program startDelay, Molang.Program loopDelay) {
        this.loop = Objects.requireNonNull(loop);
        if (!loop.equals("LOOP") && !loop.equals("ONCE") && !loop.equals("HOLD")) {
            throw new IllegalArgumentException("Invalid animation loop");
        }

        // Native YSM clips without animation_length use positive infinity.
        if (Double.isNaN(lengthTicks) || lengthTicks < 0 || !Double.isFinite(speed) || speed < 0
                || !Double.isFinite(startTick)) {
            throw new IllegalArgumentException("Invalid animation clock");
        }

        this.lengthTicks = lengthTicks;
        this.speed = speed;
        this.startTick = startTick;
        this.animTimeUpdate = animTimeUpdate;
        this.startDelay = startDelay;
        this.loopDelay = loopDelay;
        this.authoredTiming = animTimeUpdate != null || startDelay != null || loopDelay != null;
        this.lastRealTick = startTick;
        this.startWaitUntil = startTick;
    }

    /** Evaluate start_delay once in the activation scope, even when a beginning blend is pending. */
    void initialize(Molang.Context context) {
        if (this.cancelled || this.initialized) {
            return;
        }

        if (this.evaluating) {
            throw new IllegalStateException("Animation clock evaluation is not reentrant");
        }

        double delay = delayTicks(this.startDelay, 0, 0, context);
        if (this.cancelled) {
            return;
        }

        this.initialized = true;
        this.startWaitUntil = add(this.startTick, delay);
        this.lastRealTick = this.startWaitUntil;
    }

    Step advance(double realTick, Molang.Context context) {
        if (!Double.isFinite(realTick)) {
            throw new IllegalArgumentException("Invalid animation tick");
        }

        if (this.evaluating) {
            throw new IllegalStateException("Animation clock evaluation is not reentrant");
        }

        if (this.cancelled) {
            return snapshot(false, false, false);
        }

        initialize(context);
        if (this.cancelled) {
            return snapshot(false, false, false);
        }

        // A render can sample a controller repeatedly at the same world tick. Boundary flags
        // belong to the first sample only, just like expression side effects and event cursors.
        if (!Double.isNaN(this.lastSampleTick) && realTick <= this.lastSampleTick) {
            return snapshot(false, isWaiting(realTick), false);
        }

        if (realTick < this.startWaitUntil) {
            this.lastSampleTick = realTick;
            return snapshot(false, true, false);
        }
        // Authored HOLD clocks may seek away from their last frame after completion;
        // their finished query remains sticky while their absolute update keeps running.
        if (this.terminal && !(this.loop.equals("HOLD") && this.animTimeUpdate != null)) {
            this.lastSampleTick = realTick;
            return snapshot(false, false, false);
        }
        if (this.loopWaiting) {
            if (realTick < this.loopWaitUntil) {
                this.lastSampleTick = realTick;
                return snapshot(false, true, false);
            }
            // A delayed cycle starts at zero on the first available frame. Extra real time
            // across this wait is deliberately discarded; it cannot cause recursive loops.
            this.loopWaiting = false;
            this.timeTicks = 0;
            this.previousTicks = FIRST_EVENT_TICK;
            this.lastRealTick = this.lastSampleTick = realTick;
            return snapshot(false, false, true);
        }
        double before = this.previousTicks;
        double deltaTicks = multiply(Math.max(0, realTick - this.lastRealTick), this.speed);
        double candidate;
        if (!this.authoredTiming) {
            double rawElapsed = multiply(Math.max(0, realTick - this.startTick), this.speed);
            candidate = Math.max(0, rawElapsed - this.tickOffset);
        } else {
            candidate = this.animTimeUpdate == null ? add(this.timeTicks, deltaTicks)
                    : secondsToTicks(evaluate(this.animTimeUpdate, this.timeTicks / 20, deltaTicks / 20, context));
        }
        if (this.cancelled) {
            return snapshot(false, false, false);
        }

        boolean boundary = this.loop.equals("ONCE") ? candidate >= this.lengthTicks : candidate > this.lengthTicks;
        boolean crossed = boundary && (!this.loop.equals("HOLD") || !this.terminal);
        boolean restarted = false, waiting = false;
        if (boundary) {
            if (this.loop.equals("LOOP")) {
                double delay = delayTicks(this.loopDelay, this.lengthTicks / 20, deltaTicks / 20, context);
                if (this.cancelled) {
                    return snapshot(false, false, false);
                }

                if (delay > 0) {
                    this.timeTicks = this.lengthTicks;
                    this.loopWaiting = waiting = true;
                    this.loopWaitUntil = add(realTick, delay);
                } else {
                    this.timeTicks = this.lengthTicks > 0 ? candidate % this.lengthTicks : 0;
                    restarted = true;
                    if (!this.authoredTiming) {
                        double rawElapsed = multiply(Math.max(0, realTick - this.startTick), this.speed);
                        this.tickOffset = rawElapsed - this.timeTicks;
                    }
                }
            } else {
                this.timeTicks = this.lengthTicks;
                this.terminal = true;
            }
            this.finished = true;
        } else {
            this.timeTicks = candidate;
        }

        this.lastRealTick = this.lastSampleTick = realTick;
        this.previousTicks = this.timeTicks;
        return new Step(before, this.timeTicks, crossed, waiting, this.finished, restarted);
    }

    /** Freeze the animation time at an explicit native controller STOP. */
    double stoppingTimeTicks(double realTick) {
        // OpenYSM startEndingTransition saves the current adjusted clock, capped at clip length.
        // An authored absolute clock freezes its last evaluated value when explicitly stopped.
        double sampled = this.authoredTiming ? this.timeTicks
                : Math.max(0, multiply(Math.max(0, realTick - this.startTick), this.speed) - this.tickOffset);
        return Math.min(sampled, this.lengthTicks);
    }

    /** Discard the playback and expression references; restarting creates a new clock. */
    void cancel() {
        this.cancelled = true;
        this.animTimeUpdate = this.startDelay = this.loopDelay = null;
        this.timeTicks = this.tickOffset = 0;
        this.previousTicks = FIRST_EVENT_TICK;
        this.initialized = this.finished = this.terminal = this.loopWaiting = false;
        this.lastSampleTick = Double.NaN;
        this.lastRealTick = this.startWaitUntil = this.loopWaitUntil = 0;
    }

    private Step snapshot(boolean boundary, boolean waiting, boolean restarted) {
        double before = restarted ? FIRST_EVENT_TICK : this.previousTicks;
        if (restarted) {
            this.previousTicks = this.timeTicks;
        }

        return new Step(before, this.timeTicks, boundary, waiting, this.finished, restarted);
    }

    private boolean isWaiting(double realTick) {
        return realTick < this.startWaitUntil || this.loopWaiting;
    }

    private double delayTicks(Molang.Program program, double animSeconds, double deltaSeconds, Molang.Context context) {
        return program == null ? 0 : secondsToTicks(evaluate(program, animSeconds, deltaSeconds, context));
    }

    private double evaluate(Molang.Program program, double animSeconds, double deltaSeconds, Molang.Context context) {
        Objects.requireNonNull(context);
        Map<String, Object> queries = context.queryValues();
        this.evaluating = true;
        try {
            context.query("query.anim_time", animSeconds);
            context.query("query.delta_time", deltaSeconds);
            return program.evaluate(context);
        } finally {
            context.restoreQueries(queries);
            this.evaluating = false;
        }
    }

    private static double secondsToTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return 0;
        }

        return multiply(seconds, 20);
    }

    private static double multiply(double a, double b) {
        if (a == 0 || b == 0) {
            return 0;
        }

        double value = a * b;
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }

    private static double add(double a, double b) {
        double value = a + b;
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }
}
