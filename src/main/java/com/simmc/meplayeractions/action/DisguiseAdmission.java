package com.simmc.meplayeractions.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread admission; refused changes never release an existing disguise. */
final class DisguiseAdmission {
    record Rejection(String code, int retryTicks) { }
    private final int maximum, cooldown;
    private final Map<UUID, Long> nextAttempt = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    DisguiseAdmission(int maximum, int cooldown) {
        if (maximum < 1 || cooldown < 0) throw new IllegalArgumentException("Invalid disguise admission limits");
        this.maximum = maximum;
        this.cooldown = cooldown;
    }

    Rejection admit(UUID owner, boolean replacing, int active, long tick) {
        if (tick < lastTick) nextAttempt.clear();
        lastTick = tick;
        long wait = nextAttempt.getOrDefault(owner, Long.MIN_VALUE);
        if (wait > tick) return new Rejection("disguise_cooldown", (int) Math.min(Integer.MAX_VALUE, wait - tick));
        if (!replacing && active >= maximum) return new Rejection("disguise_limit", 20);
        if (cooldown > 0) nextAttempt.put(owner, tick + cooldown);
        return null;
    }

    void forget(UUID owner) { nextAttempt.remove(owner); }
    void clear() { nextAttempt.clear(); lastTick = Long.MIN_VALUE; }
}
