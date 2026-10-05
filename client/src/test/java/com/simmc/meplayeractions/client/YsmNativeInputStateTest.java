package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.VanillaYsmAnimations.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YsmNativeInputStateTest {
    @Test void realSwingPulseHasTheFixedTenTickWindowAndAdvancesOnlyOnGameTicks() {
        var clock=new YsmInputPulseClock();clock.swing();
        assertEquals(10,clock.swingTicks());assertEquals(1,clock.swingAge());assertEquals(1,clock.sequence());
        for(int i=0;i<5;i++)assertEquals(10,clock.swingTicks()); // Queries/renders are reads.
        for(int i=0;i<9;i++)clock.tick(false,false);
        assertEquals(1,clock.swingTicks());assertEquals(10,clock.swingAge());
        clock.tick(false,false);assertEquals(0,clock.swingTicks());assertEquals(11,clock.swingAge());
        clock.swing();assertEquals(10,clock.swingTicks());assertEquals(1,clock.swingAge());assertEquals(2,clock.sequence());
    }
    @Test void useIntentExpiresAfterTwoTicksOrActualUseOrItemChange() {
        var clock=new YsmInputPulseClock();clock.use();assertEquals(2,clock.useTicks());assertEquals(1,clock.useAge());
        clock.tick(false,true);assertEquals(1,clock.useTicks());assertEquals(2,clock.useAge());
        clock.tick(false,true);assertEquals(0,clock.useTicks());assertEquals(0,clock.useAge());
        clock.use();clock.tick(true,true);assertEquals(0,clock.useTicks());
        clock.use();clock.tick(false,false);assertEquals(0,clock.useTicks());
        clock.swing();clock.use();clock.reset();assertEquals(0,clock.swingTicks());assertEquals(0,clock.useTicks());assertEquals(0,clock.sequence());
    }
    private static VanillaState event(long sequence,int nativeTicks) {
        var sword=new ItemState("minecraft:iron_sword",Set.of("minecraft:swords"),"sword","none",false,false,1);
        return new VanillaState(false,0,false,false,false,sword,ItemState.EMPTY,Hand.NONE,0,Hand.MAIN,nativeTicks,
                false,"",Set.of(),false,false,Map.of(),"",Set.of(),false,sequence);
    }
    @Test void rawPulseAndLaterNativeSwingMirrorUseOnePlaybackStartUntilTheNextRealEdge() {
        var catalog=new Catalog(List.of("swing:sword"));var playback=new HandPlayback();
        var first=event(1,1);var selection=VanillaYsmAnimations.select(first,catalog).slots().get("player.swing");
        assertEquals(100,playback.start("player.swing",selection,first,100));
        var nativeMirror=event(1,0);
        assertEquals(100,playback.start("player.swing",selection,nativeMirror,101));
        assertEquals(100,playback.start("player.swing",selection,event(1,5),105));
        assertEquals(106,playback.start("player.swing",selection,event(2,1),106));
    }
}
