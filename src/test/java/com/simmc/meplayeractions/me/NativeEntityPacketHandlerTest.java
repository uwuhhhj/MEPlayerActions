package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.nms.network.ProtectedPacket;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityPacketHandlerTest {
    private record EntityPacket(int owner, String kind) {
    }

    private record Bundle(List<Object> packets) {
    }

    private record RemovalPacket(List<Integer> ids) {
    }

    private record PassengersPacket(int vehicle, List<Integer> passengers) {
    }

    private static NativeEntityPacketHandler handler() {
        return new NativeEntityPacketHandler((p, ids) -> p instanceof EntityPacket e && ids.contains(e.owner),
                p -> p instanceof Bundle b ? b.packets : null, (p, owners) -> {
                    if (!(p instanceof RemovalPacket removal)) {
                        return null;
                    }

                    NativeEntityPackets.IdPartition ids = NativeEntityPackets.partitionIds(removal.ids, owners);
                    if (ids.approved().length == 0) {
                        return null;
                    }

                    if (ids.remaining().length == 0) {
                        return new NativeEntityPacketHandler.Removal(p, null);
                    }

                    return new NativeEntityPacketHandler.Removal(removal(ids.approved()), removal(ids.remaining()));
                });
    }

    private static RemovalPacket removal(int... ids) {
        return new RemovalPacket(Arrays.stream(ids).boxed().toList());
    }

    @Test
    void onlyApprovedViewerOwnerPacketsBypassMe() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.EntityPacket nativeMove = new EntityPacket(10, "move");
        NativeEntityPacketHandlerTest.EntityPacket otherMove = new EntityPacket(20, "move");
        channel.writeOutbound(nativeMove, otherMove, "ordinary-message");
        assertEquals(new ProtectedPacket(nativeMove), channel.readOutbound());
        assertSame(otherMove, channel.readOutbound());
        assertEquals("ordinary-message", channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void unmoddedOrUnconfirmedViewerKeepsAllMeFiltering() {
        EmbeddedChannel channel = new EmbeddedChannel(handler());
        NativeEntityPacketHandlerTest.Bundle bundle = new Bundle(
                List.of(new EntityPacket(10, "spawn"), new EntityPacket(10, "metadata")));
        channel.writeOutbound(bundle);
        assertSame(bundle, channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void mixedBundlesKeepPacketOrderWithoutExemptingUnrelatedEntities() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        String info = "player-info";
        NativeEntityPacketHandlerTest.EntityPacket spawn = new EntityPacket(10, "spawn");
        NativeEntityPacketHandlerTest.EntityPacket foreign = new EntityPacket(20, "spawn");
        NativeEntityPacketHandlerTest.EntityPacket data = new EntityPacket(10, "metadata");
        channel.writeOutbound(new Bundle(List.of(info, spawn, foreign, data)));
        assertSame(info, channel.readOutbound());
        assertEquals(new ProtectedPacket(spawn), channel.readOutbound());
        assertSame(foreign, channel.readOutbound());
        assertEquals(new ProtectedPacket(data), channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void withdrawingLeaseImmediatelyRestoresMePacketHandling() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.EntityPacket move = new EntityPacket(10, "move");
        channel.writeOutbound(move);
        assertInstanceOf(ProtectedPacket.class, channel.readOutbound());
        handler.owners = Set.of();
        channel.writeOutbound(move);
        assertSame(move, channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void alreadyProtectedPacketsAreNotWrappedAgain() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ProtectedPacket packet = new ProtectedPacket(new EntityPacket(10, "remove"));
        channel.writeOutbound(packet);
        assertSame(packet, channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void mixedBatchRemovalProtectsOnlyApprovedIds() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10, 11);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.writeOutbound(removal(20, 10, 30, 11));
        assertEquals(new ProtectedPacket(removal(10, 11)), channel.readOutbound());
        assertEquals(removal(20, 30), channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void fullyApprovedRemovalPreservesOriginalPacketWithoutExemptingAnotherId() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10, 11);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.RemovalPacket packet = removal(11, 10, 11);
        channel.writeOutbound(packet);
        assertEquals(new ProtectedPacket(packet), channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void fullyUnapprovedAndEmptyRemovalsKeepOriginalMeHandling() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.RemovalPacket foreign = removal(20, 30);
        NativeEntityPacketHandlerTest.RemovalPacket empty = removal();
        channel.writeOutbound(foreign, empty);
        assertSame(foreign, channel.readOutbound());
        assertSame(empty, channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void partitionPreservesIdOrderAndDuplicatesInBothAccessorForms() {
        NativeEntityPackets.IdPartition primitive = NativeEntityPackets
                .partitionIds(new int[] { 20, 10, 20, 11, 10, 30 }, Set.of(10, 11));
        NativeEntityPackets.IdPartition iterable = NativeEntityPackets.partitionIds(List.of(20, 10, 20, 11, 10, 30),
                Set.of(10, 11));
        assertArrayEquals(new int[] { 10, 11, 10 }, primitive.approved());
        assertArrayEquals(new int[] { 20, 20, 30 }, primitive.remaining());
        assertArrayEquals(primitive.approved(), iterable.approved());
        assertArrayEquals(primitive.remaining(), iterable.remaining());
    }

    @Test
    void mixedBatchRemainderStillPassesThroughMeVisibilityFiltering() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        ChannelOutboundHandlerAdapter me = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) {
                if (packet instanceof RemovalPacket removal) {
                    packet = new RemovalPacket(removal.ids.stream().filter(id -> id != 20).toList());
                }

                context.write(packet, promise);
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(me, handler);
        channel.writeOutbound(removal(10, 20, 30));
        assertEquals(new ProtectedPacket(removal(10)), channel.readOutbound());
        assertEquals(removal(30), channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void passengerRelationshipsRetainOriginalIdsAndMePivotAugmentation() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        ChannelOutboundHandlerAdapter me = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) {
                if (packet instanceof PassengersPacket passengers) {
                    LinkedHashSet<Integer> ids = new LinkedHashSet<>(passengers.passengers);
                    ids.add(99);
                    packet = new PassengersPacket(passengers.vehicle, List.copyOf(ids));
                }
                context.write(packet, promise);
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(me, handler);
        channel.writeOutbound(new PassengersPacket(40, List.of(10, 20)), new PassengersPacket(10, List.of(30)));
        assertEquals(new PassengersPacket(40, List.of(10, 20, 99)), channel.readOutbound());
        assertEquals(new PassengersPacket(10, List.of(30, 99)), channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void nestedBundlesRouteApprovedDescendantsAndSplitMixedRemovalInOrder() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.EntityPacket foreign = new EntityPacket(20, "move");
        NativeEntityPacketHandlerTest.Bundle ordinaryBundle = new Bundle(List.of("foreign-info", foreign));
        NativeEntityPacketHandlerTest.EntityPacket nativeData = new EntityPacket(10, "metadata");
        channel.writeOutbound(new Bundle(List.of("before",
                new Bundle(List.of(ordinaryBundle, new Bundle(List.of(removal(20, 10), nativeData)))), "after")));
        assertEquals("before", channel.readOutbound());
        assertSame(ordinaryBundle, channel.readOutbound());
        assertEquals(new ProtectedPacket(removal(10)), channel.readOutbound());
        assertEquals(removal(20), channel.readOutbound());
        assertEquals(new ProtectedPacket(nativeData), channel.readOutbound());
        assertEquals("after", channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void whollyUnapprovedNestedBundlesRemainIntact() {
        NativeEntityPacketHandler handler = handler();
        handler.owners = Set.of(10);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        NativeEntityPacketHandlerTest.Bundle bundle = new Bundle(
                List.of(new Bundle(List.of(removal(20, 30), new EntityPacket(20, "move")))));
        channel.writeOutbound(bundle);
        assertSame(bundle, channel.readOutbound());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }
}
