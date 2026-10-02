package com.simmc.meplayeractions.action;

import java.util.function.Predicate;

/** Gameplay state is authoritative; a disabled posture must not fall through to swimming. */
public final class StateSelector {
    private StateSelector() {}

    public enum Vehicle { NONE, BOAT, MINECART, OTHER }

    public record Sample(boolean sitting, boolean crawling, boolean gliding, boolean flying,
                         boolean swimming, boolean inWater, boolean grounded, boolean sneaking,
                         boolean sprinting, boolean moving, boolean sleeping, Vehicle vehicle) {
        public Sample(boolean sitting, boolean crawling, boolean gliding, boolean flying,
                      boolean swimming, boolean inWater, boolean grounded, boolean sneaking,
                      boolean sprinting, boolean moving) {
            this(sitting, crawling, gliding, flying, swimming, inWater, grounded, sneaking,
                    sprinting, moving, false, Vehicle.NONE);
        }

        public String postureKey() {
            if (sleeping) return "sleep";
            if (sitting) return "sit";
            if (crawling) return "crawl";
            if (vehicle != Vehicle.NONE) return vehicle.name();
            if (gliding) return "elytra";
            if (flying) return "flight";
            if (swimming) return "swim-prone";
            if (inWater && !grounded) return "swim";
            if (sneaking) return "sneak";
            return "standing";
        }
    }

    public static ActionState select(Sample s, Predicate<SyncFeature> enabled) {
        return select(s, enabled, !s.grounded ? ActionState.JUMP : null);
    }

    public static ActionState select(Sample s, Predicate<SyncFeature> enabled, ActionState air) {
        if (s.sleeping) return enabled.test(SyncFeature.SLEEP) ? ActionState.SLEEP : null;
        // GSit poses can also mount the player on an invisible seat entity.
        if (s.sitting) return enabled.test(SyncFeature.SIT) ? ActionState.SIT : null;
        if (s.crawling) return enabled.test(SyncFeature.CRAWL)
                ? (s.moving ? ActionState.CRAWL_WALK : ActionState.CRAWL_IDLE) : null;
        if (s.vehicle != Vehicle.NONE) return enabled.test(SyncFeature.RIDE) ? switch (s.vehicle) {
            case BOAT -> ActionState.BOAT;
            case MINECART -> ActionState.MINECART;
            case OTHER -> ActionState.RIDE;
            case NONE -> throw new IllegalStateException();
        } : null;
        if (s.gliding) return enabled.test(SyncFeature.ELYTRA) ? ActionState.ELYTRA : null;
        if (s.flying) return enabled.test(SyncFeature.FLIGHT)
                ? (s.moving ? ActionState.FLY : ActionState.HOVER) : null;
        if (s.swimming) return enabled.test(SyncFeature.SWIM)
                ? (s.moving ? ActionState.SWIM_WALK : ActionState.SWIM_PRONE_IDLE) : null;
        if (s.inWater && !s.grounded) return enabled.test(SyncFeature.SWIM) ? ActionState.SWIM_IDLE : null;
        if (air != null && !s.inWater && enabled.test(SyncFeature.JUMP)) return air;
        if (s.sneaking && s.grounded) return enabled.test(SyncFeature.SNEAK)
                ? (s.moving ? ActionState.CROUCH_WALK : ActionState.CROUCH_IDLE) : null;
        if (!enabled.test(SyncFeature.MOVEMENT)) return null;
        if (!s.moving) return ActionState.IDLE;
        return s.sprinting && enabled.test(SyncFeature.SPRINT) ? ActionState.RUN : ActionState.WALK;
    }
}
