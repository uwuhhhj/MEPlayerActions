package com.simmc.meplayeractions.action;

/** A real takeoff is distinct from walking off a ledge; a short landing tail is bounded. */
public final class JumpTracker {
    private boolean active, airborneSeen, initialized, previousGrounded;
    private long started, landed = -1, cycle;

    public void signal(long tick) {
        this.active = true;
        this.airborneSeen = false;
        this.started = tick;
        this.landed = -1;
        this.cycle++;
    }

    public ActionState sample(boolean grounded, double rise, boolean blocked, long tick, int minimumTicks,
            int landingGrace) {
        if (blocked) {
            reset();
            this.initialized = true;
            this.previousGrounded = grounded;
            return null;
        }
        if (this.initialized && this.previousGrounded && !grounded && rise > 0.03
                && (!this.active || this.landed >= 0)) {
            signal(tick);
        }

        this.initialized = true;
        this.previousGrounded = grounded;
        if (!this.active) {
            return grounded ? null : ActionState.FALL;
        }

        if (!grounded) {
            this.airborneSeen = true;
            this.landed = -1;
            return tick - this.started < minimumTicks ? ActionState.JUMP : ActionState.FALL;
        }
        if (!this.airborneSeen) {
            if (tick - this.started <= 3) {
                return ActionState.JUMP;
            }

            this.active = false;
            return null;
        }
        if (this.landed < 0) {
            this.landed = tick;
        }

        if (tick - this.started < minimumTicks && tick - this.landed < landingGrace) {
            return ActionState.JUMP;
        }

        this.active = false;
        return null;
    }

    public long cycle() {
        return this.cycle;
    }

    public void reset() {
        this.active = false;
        this.airborneSeen = false;
        this.landed = -1;
        this.initialized = false;
    }
}
