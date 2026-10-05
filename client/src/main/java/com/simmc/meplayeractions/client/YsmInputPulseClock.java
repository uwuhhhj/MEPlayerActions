package com.simmc.meplayeractions.client;

/** Sparkle b1230a4 InputStateKey's bounded local interaction clocks. No render-time mutation. */
final class YsmInputPulseClock {
    private int swingTicks,swingAge,swingSequence,useTicks,useAge;
    void swing() { swingTicks=10;swingAge=1;swingSequence=swingSequence==Integer.MAX_VALUE?1:swingSequence+1; }
    void use() { useTicks=2;useAge=1; }
    void tick(boolean nativeUsing,boolean validUse) {
        if(useTicks>0) {
            if(nativeUsing||!validUse)clearUse();
            else {useTicks--;useAge++;if(useTicks<=0)clearUse();}
        }
        if(swingTicks>0) {swingTicks--;swingAge++;}
    }
    void clearUse() { useTicks=0;useAge=0; }
    void reset() { swingTicks=0;swingAge=0;swingSequence=0;clearUse(); }
    int swingTicks() { return swingTicks; } int swingAge() { return swingAge; }
    int sequence() { return swingSequence; } int useTicks() { return useTicks; } int useAge() { return useAge; }
}
