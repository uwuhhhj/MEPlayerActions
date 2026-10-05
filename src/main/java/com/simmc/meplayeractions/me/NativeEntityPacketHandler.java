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
    private final BiFunction<Object,Set<Integer>,Removal> removal;
    record Removal(Object approved,Object remaining) {}
    NativeEntityPacketHandler(BiPredicate<Object,Set<Integer>> belongs,Function<Object,List<Object>> bundle) {
        this(belongs,bundle,(packet,owners)->null);
    }
    NativeEntityPacketHandler(BiPredicate<Object,Set<Integer>> belongs,Function<Object,List<Object>> bundle,
                              BiFunction<Object,Set<Integer>,Removal> removal) {
        this.belongs=belongs;this.bundle=bundle;this.removal=removal;
    }
    @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise) throws Exception {
        Set<Integer> authorized=owners;
        if(authorized.isEmpty() || packet instanceof ProtectedPacket) { context.write(packet,promise);return; }
        List<Object> routed=route(packet,authorized);
        if(routed==null){context.write(packet,promise);return;}
        for(int i=0;i<routed.size();i++)context.write(routed.get(i),
                i==routed.size()-1?promise:context.voidPromise());
    }
    private List<Object> route(Object packet,Set<Integer> authorized) {
        if(packet instanceof ProtectedPacket)return null;
        Removal split=removal.apply(packet,authorized);
        if(split!=null) {
            Object approved=new ProtectedPacket(split.approved);
            return split.remaining==null?List.of(approved):List.of(approved,split.remaining);
        }
        List<Object> children=bundle.apply(packet);
        if(children!=null) {
            List<Object> routed=null;
            for(int i=0;i<children.size();i++) {
                Object child=children.get(i);List<Object> childRoute=route(child,authorized);
                if(childRoute!=null) {
                    if(routed==null)routed=new ArrayList<>(children.subList(0,i));
                    routed.addAll(childRoute);
                } else if(routed!=null)routed.add(child);
            }
            // ProtectedPacket cannot be inserted into a Minecraft bundle. Flatten only bundles
            // containing an exemption, recursively, while keeping ordinary bundles unchanged.
            return routed;
        }
        return belongs.test(packet,authorized)?List.of(new ProtectedPacket(packet)):null;
    }
}
