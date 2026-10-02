package com.simmc.meplayeractions.action;

/** A real takeoff is distinct from walking off a ledge; a short landing tail is bounded. */
public final class JumpTracker {
    private boolean active, airborneSeen, initialized, previousGrounded;
    private long started, landed = -1, cycle;

    public void signal(long tick) {
        active = true; airborneSeen = false; started = tick; landed = -1; cycle++;
    }

    public ActionState sample(boolean grounded, double rise, boolean blocked, long tick,
                              int minimumTicks, int landingGrace) {
        if (blocked) { reset(); initialized = true; previousGrounded = grounded; return null; }
        if (initialized && previousGrounded && !grounded && rise > 0.03 && (!active || landed >= 0)) signal(tick);
        initialized = true; previousGrounded = grounded;
        if (!active) return grounded ? null : ActionState.FALL;
        if (!grounded) {
            airborneSeen = true; landed = -1;
            return tick - started < minimumTicks ? ActionState.JUMP : ActionState.FALL;
        }
        if (!airborneSeen) {
            if (tick - started <= 3) return ActionState.JUMP;
            active = false; return null;
        }
        if (landed < 0) landed = tick;
        if (tick - started < minimumTicks && tick - landed < landingGrace) return ActionState.JUMP;
        active = false; return null;
    }

    public long cycle() { return cycle; }
    public void reset() { active = false; airborneSeen = false; landed = -1; initialized = false; }
}
