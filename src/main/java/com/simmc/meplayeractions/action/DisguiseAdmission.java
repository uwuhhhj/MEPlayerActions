package com.simmc.meplayeractions.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread admission; refused changes never release an existing disguise. */
final class DisguiseAdmission
{
    record Rejection(String code, int retryTicks)
    {
    }

    private final int maximum, cooldown;
    private final Map<UUID, Long> nextAttempt = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    DisguiseAdmission(int maximum, int cooldown)
    {
        if (maximum < 1 || cooldown < 0)
        {
            throw new IllegalArgumentException("Invalid disguise admission limits");
        }

        this.maximum = maximum;
        this.cooldown = cooldown;
    }

    Rejection admit(UUID owner, boolean replacing, int active, long tick)
    {
        if (tick < this.lastTick)
        {
            this.nextAttempt.clear();
        }

        this.lastTick = tick;
        long wait = this.nextAttempt.getOrDefault(owner, Long.MIN_VALUE);
        if (wait > tick)
        {
            return new Rejection("disguise_cooldown", (int) Math.min(Integer.MAX_VALUE, wait - tick));
        }

        if (!replacing && active >= this.maximum)
        {
            return new Rejection("disguise_limit", 20);
        }

        if (this.cooldown > 0)
        {
            this.nextAttempt.put(owner, tick + this.cooldown);
        }

        return null;
    }

    void forget(UUID owner)
    {
        this.nextAttempt.remove(owner);
    }

    void clear()
    {
        this.nextAttempt.clear();
        this.lastTick = Long.MIN_VALUE;
    }
}
