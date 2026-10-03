package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.nms.network.ProtectedPacket;
import io.netty.channel.*;
import org.bukkit.Bukkit;
import java.util.*;

/** Retains vanilla tracking for modded viewers while ME remains unchanged for every other viewer. */
final class NativeEntityRelay {
    private static final String NAME="meplayeractions_native_entities",ME="model_engine_packet_handler";
    private final NativeEntityPackets packets=new NativeEntityPackets();
    private final Map<UUID,Viewer> viewers=new HashMap<>();
    private record Owner(int entityId,boolean hiddenBase) {}
    private static final class Viewer {
        final Channel channel;final NativeEntityPacketHandler handler;final Map<UUID,Owner> owners=new HashMap<>();
        Viewer(Channel channel,NativeEntityPacketHandler handler){this.channel=channel;this.handler=handler;}
        void publish(){handler.owners=owners.values().stream().map(Owner::entityId).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    }
    boolean enable(UUID viewerId,UUID ownerId,int entityId,boolean hiddenBase) {
        var wrapper=ModelEngineAPI.getNetworkHandler().getPipeline(viewerId).orElse(null);
        if(wrapper==null || !wrapper.getChannel().isActive())return false;
        Channel channel=wrapper.getChannel();Viewer viewer=viewers.get(viewerId);
        if(viewer==null || viewer.channel!=channel) {
            NativeEntityPacketHandler handler=new NativeEntityPacketHandler(packets::belongsTo,packets::bundle);
            var installed=channel.eventLoop().submit(()-> {
                ChannelPipeline pipeline=channel.pipeline();
                if(pipeline.get(ME)==null || pipeline.get("protected_packet_unpacker")==null
                        || pipeline.names().indexOf("protected_packet_unpacker")>=pipeline.names().indexOf(ME))return false;
                if(pipeline.get(NAME)!=null)return false;
                pipeline.addAfter(ME,NAME,handler);return true;
            });
            if(!installed.awaitUninterruptibly(1000) || !installed.isSuccess() || !Boolean.TRUE.equals(installed.getNow()))return false;
            viewer=new Viewer(channel,handler);viewers.put(viewerId,viewer);
        }
        viewer.owners.put(ownerId,new Owner(entityId,hiddenBase));viewer.publish();return true;
    }
    void disable(UUID viewerId,UUID ownerId) {
        Viewer viewer=viewers.get(viewerId);if(viewer==null)return;
        Owner owner=viewer.owners.remove(ownerId);if(owner==null)return;
        if(owner.hiddenBase && viewer.channel.isActive()) viewer.channel.writeAndFlush(new ProtectedPacket(packets.remove(owner.entityId)));
        viewer.publish();
        if(viewer.owners.isEmpty()) {
            viewers.remove(viewerId);
            viewer.channel.eventLoop().execute(()->{if(viewer.channel.pipeline().get(NAME)==viewer.handler)viewer.channel.pipeline().remove(NAME);});
        }
    }
    void removeOwner(UUID ownerId) { for(UUID viewer:List.copyOf(viewers.keySet()))disable(viewer,ownerId); }
}
