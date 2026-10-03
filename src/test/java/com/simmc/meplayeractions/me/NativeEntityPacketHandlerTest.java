package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.nms.network.ProtectedPacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityPacketHandlerTest {
    private record EntityPacket(int owner,String kind) {}
    private record Bundle(List<Object> packets) {}
    private static NativeEntityPacketHandler handler() {
        return new NativeEntityPacketHandler((p,ids)->p instanceof EntityPacket e && ids.contains(e.owner),
                p->p instanceof Bundle b?b.packets:null);
    }
    @Test void onlyApprovedViewerOwnerPacketsBypassMe() {
        var handler=handler();handler.owners=Set.of(10);var channel=new EmbeddedChannel(handler);
        var nativeMove=new EntityPacket(10,"move");var otherMove=new EntityPacket(20,"move");
        channel.writeOutbound(nativeMove,otherMove,"ordinary-message");
        assertEquals(new ProtectedPacket(nativeMove),channel.readOutbound());assertSame(otherMove,channel.readOutbound());
        assertEquals("ordinary-message",channel.readOutbound());assertNull(channel.readOutbound());channel.finishAndReleaseAll();
    }
    @Test void unmoddedOrUnconfirmedViewerKeepsAllMeFiltering() {
        var channel=new EmbeddedChannel(handler());var bundle=new Bundle(List.of(new EntityPacket(10,"spawn"),new EntityPacket(10,"metadata")));
        channel.writeOutbound(bundle);assertSame(bundle,channel.readOutbound());assertNull(channel.readOutbound());channel.finishAndReleaseAll();
    }
    @Test void mixedBundlesKeepPacketOrderWithoutExemptingUnrelatedEntities() {
        var handler=handler();handler.owners=Set.of(10);var channel=new EmbeddedChannel(handler);
        var info="player-info";var spawn=new EntityPacket(10,"spawn");var foreign=new EntityPacket(20,"spawn");var data=new EntityPacket(10,"metadata");
        channel.writeOutbound(new Bundle(List.of(info,spawn,foreign,data)));
        assertSame(info,channel.readOutbound());assertEquals(new ProtectedPacket(spawn),channel.readOutbound());
        assertSame(foreign,channel.readOutbound());assertEquals(new ProtectedPacket(data),channel.readOutbound());assertNull(channel.readOutbound());channel.finishAndReleaseAll();
    }
    @Test void withdrawingLeaseImmediatelyRestoresMePacketHandling() {
        var handler=handler();handler.owners=Set.of(10);var channel=new EmbeddedChannel(handler);var move=new EntityPacket(10,"move");
        channel.writeOutbound(move);assertInstanceOf(ProtectedPacket.class,channel.readOutbound());
        handler.owners=Set.of();channel.writeOutbound(move);assertSame(move,channel.readOutbound());channel.finishAndReleaseAll();
    }
    @Test void alreadyProtectedPacketsAreNotWrappedAgain() {
        var handler=handler();handler.owners=Set.of(10);var channel=new EmbeddedChannel(handler);var packet=new ProtectedPacket(new EntityPacket(10,"remove"));
        channel.writeOutbound(packet);assertSame(packet,channel.readOutbound());channel.finishAndReleaseAll();
    }
    @Test void passengersAndBatchRemovalsCanMatchAnyAuthorizedOwnerButNoOthers() {
        assertTrue(NativeEntityPackets.contains(10,Set.of(10)));assertFalse(NativeEntityPackets.contains(20,Set.of(10)));
        assertTrue(NativeEntityPackets.contains(new int[]{20,10},Set.of(10)));
        assertTrue(NativeEntityPackets.contains(List.of(20,10),Set.of(10)));
        assertFalse(NativeEntityPackets.contains(List.of(20,30),Set.of(10)));
        assertFalse(NativeEntityPackets.contains(new int[]{10},Set.of()));
    }
}
