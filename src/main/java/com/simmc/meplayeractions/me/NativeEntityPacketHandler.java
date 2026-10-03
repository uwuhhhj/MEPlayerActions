package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.nms.network.ProtectedPacket;
import io.netty.channel.*;
import java.util.*;
import java.util.function.*;

/** Only approved owner IDs bypass ME's native-entity filtering; all other packets follow ME normally. */
final class NativeEntityPacketHandler extends ChannelOutboundHandlerAdapter {
    volatile Set<Integer> owners=Set.of();
    private final BiPredicate<Object,Set<Integer>> belongs;
    private final Function<Object,List<Object>> bundle;
    NativeEntityPacketHandler(BiPredicate<Object,Set<Integer>> belongs,Function<Object,List<Object>> bundle) {
        this.belongs=belongs;this.bundle=bundle;
    }
    @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise) throws Exception {
        Set<Integer> authorized=owners;
        if(authorized.isEmpty() || packet instanceof ProtectedPacket) { context.write(packet,promise);return; }
        List<Object> children=bundle.apply(packet);
        if(children!=null && children.stream().anyMatch(child->belongs.test(child,authorized))) {
            // A ProtectedPacket is not a Minecraft Packet and cannot be inserted inside a BundlePacket.
            // Preserve child order while passing only approved entity packets through ME's public unpacker.
            for(int i=0;i<children.size();i++) {
                Object child=children.get(i);
                context.write(belongs.test(child,authorized)?new ProtectedPacket(child):child,
                        i==children.size()-1?promise:context.voidPromise());
            }
        } else context.write(belongs.test(packet,authorized)?new ProtectedPacket(packet):packet,promise);
    }
}
