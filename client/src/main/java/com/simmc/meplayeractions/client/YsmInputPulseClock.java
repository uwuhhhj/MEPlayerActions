package com.simmc.meplayeractions.client;

/** Sparkle b1230a4 InputStateKey's bounded local interaction clocks. No render-time mutation. */
final class YsmInputPulseClock
{
    private int swingTicks, swingAge, swingSequence, useTicks, useAge;

    void swing()
    {
        this.swingTicks = 10;
        this.swingAge = 1;
        this.swingSequence = this.swingSequence == Integer.MAX_VALUE ? 1 : this.swingSequence + 1;
    }

    void use()
    {
        this.useTicks = 2;
        this.useAge = 1;
    }

    void tick(boolean nativeUsing, boolean validUse)
    {
        if (this.useTicks > 0)
        {
            if (nativeUsing || !validUse)
            {
                clearUse();
            }

            else
            {
                this.useTicks--;
                this.useAge++;
                if (this.useTicks <= 0)
                {
                    clearUse();
                }
            }
        }
        if (this.swingTicks > 0)
        {
            this.swingTicks--;
            this.swingAge++;
        }
    }

    void clearUse()
    {
        this.useTicks = 0;
        this.useAge = 0;
    }

    void reset()
    {
        this.swingTicks = 0;
        this.swingAge = 0;
        this.swingSequence = 0;
        clearUse();
    }

    int swingTicks()
    {
        return this.swingTicks;
    }

    int swingAge()
    {
        return this.swingAge;
    }

    int sequence()
    {
        return this.swingSequence;
    }

    int useTicks()
    {
        return this.useTicks;
    }

    int useAge()
    {
        return this.useAge;
    }

    /** InputStateKey.getSwingTicks prefers the real entity swing, then the local pending pulse. */
    float swingTicks(boolean nativeSwinging, int nativeTicks, float fraction, boolean local)
    {
        if (nativeSwinging)
        {
            return Math.max(0, nativeTicks + fraction);
        }

        return local && this.swingTicks > 0 ? Math.max(1, this.swingAge + fraction) : 0;
    }

    /** InputStateKey.getAttackProgress prefers positive vanilla interpolation before the main-hand pulse. */
    float attackProgress(float nativeProgress, boolean localMainHand, float resolvedSwingTicks)
    {
        if (nativeProgress > 0)
        {
            return nativeProgress;
        }

        return localMainHand && this.swingTicks > 0 ? Math.min(1, resolvedSwingTicks / 6) : 0;
    }
}
