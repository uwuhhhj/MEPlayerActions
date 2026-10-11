package com.simmc.meplayeractions.client;

/** Starting actions and snapshots share a cooldown; stopping/resetting remain immediate. */
final class RequestCooldown
{
    private long lastTick;
    private boolean requested;

    boolean allow(String type, String action, long tick, int cooldown)
    {
        if (type.equals("request") && (action.equals("stop") || action.equals("reset")))
        {
            return true;
        }

        if (this.requested && ((tick - this.lastTick) & 0xffffffffL) < cooldown)
        {
            return false;
        }

        this.requested = true;
        this.lastTick = tick;
        return true;
    }
}
