package com.simmc.meplayeractions.me;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.nms.network.ProtectedPacket;
import io.netty.channel.*;
import org.bukkit.Bukkit;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Retains vanilla tracking for modded viewers while ME remains unchanged for every other viewer. */
final class NativeEntityRelay implements AutoCloseable {
    private static final String NAME="meplayeractions_native_entities",ME="model_engine_packet_handler";
    private final NativeEntityPackets packets=new NativeEntityPackets();
    private final Map<UUID,Viewer> viewers=new HashMap<>();
    private final Executor main;
    private boolean closed;
    private record Owner(int entityId,boolean hiddenBase,Object lease) {}
    private static final class Viewer {
        final Channel channel;final NativeEntityPacketHandler handler;final Map<UUID,Owner> owners=new HashMap<>();
        NativeHandlerInstallation installation;
        Viewer(Channel channel,NativeEntityPacketHandler handler){this.channel=channel;this.handler=handler;}
        void publish(){handler.owners=owners.values().stream().map(Owner::entityId).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    }
    NativeEntityRelay(Executor main){this.main=Objects.requireNonNull(main);}
    boolean enable(UUID viewerId,UUID ownerId,int entityId,boolean hiddenBase,Object lease,BooleanSupplier authorized) {
        if(closed||!authorized.getAsBoolean())return false;
        Channel channel=channel(viewerId);if(channel==null||!channel.isActive())return false;
        Viewer viewer=viewers.get(viewerId);
        if(viewer!=null&&(viewer.channel!=channel||viewer.installation.state()==NativeHandlerInstallation.State.CLOSED)) {
            retire(viewerId,viewer);viewer=null;
        }
        if(viewer==null) {
            NativeEntityPacketHandler handler=new NativeEntityPacketHandler(packets::belongsTo,packets::bundle,packets::partitionRemoval);
            viewer=new Viewer(channel,handler);viewers.put(viewerId,viewer);Viewer created=viewer;
            viewer.installation=new NativeHandlerInstallation(channel.eventLoop(),main,channel::isActive,
                    ()->!closed&&viewers.get(viewerId)==created&&channel(viewerId)==channel&&online(viewerId),
                    ()->install(channel,handler),()->removeHandler(channel,handler));
        }
        if(!viewer.installation.request(ownerId,lease,authorized))return false;
        // Readiness alone grants no owner: this call has just rechecked its live main-thread authorization.
        viewer.owners.put(ownerId,new Owner(entityId,hiddenBase,lease));viewer.publish();return true;
    }
    boolean pending(UUID viewerId,UUID ownerId,Object lease) {
        Viewer viewer=viewers.get(viewerId);return !closed&&viewer!=null&&viewer.installation.pending(ownerId,lease);
    }
    void disable(UUID viewerId,UUID ownerId) {
        Viewer viewer=viewers.get(viewerId);if(viewer==null)return;
        Owner owner=viewer.owners.remove(ownerId);viewer.publish();
        if(owner!=null&&owner.hiddenBase&&viewer.channel.isActive())viewer.channel.writeAndFlush(new ProtectedPacket(packets.remove(owner.entityId)));
        viewer.installation.withdraw(ownerId);
        if(viewer.installation.empty())viewers.remove(viewerId,viewer);
    }
    void removeOwner(UUID ownerId) { for(UUID viewer:List.copyOf(viewers.keySet()))disable(viewer,ownerId); }
    private void retire(UUID id,Viewer viewer) {
        viewers.remove(id,viewer);viewer.owners.clear();viewer.publish();viewer.installation.close();
    }
    @Override public void close(){closed=true;for(var entry:List.copyOf(viewers.entrySet()))retire(entry.getKey(),entry.getValue());}
    private static boolean online(UUID id){var player=Bukkit.getPlayer(id);return player!=null&&player.isOnline();}
    private static Channel channel(UUID id){var wrapper=ModelEngineAPI.getNetworkHandler().getPipeline(id).orElse(null);return wrapper==null?null:wrapper.getChannel();}
    private static boolean install(Channel channel,NativeEntityPacketHandler handler) {
        ChannelPipeline pipeline=channel.pipeline();
        if(pipeline.get(ME)==null||pipeline.get("protected_packet_unpacker")==null
                ||pipeline.names().indexOf("protected_packet_unpacker")>=pipeline.names().indexOf(ME))return false;
        if(pipeline.get(NAME)!=null)return pipeline.get(NAME)==handler;
        pipeline.addAfter(ME,NAME,handler);return true;
    }
    private static void removeHandler(Channel channel,NativeEntityPacketHandler handler){if(channel.pipeline().get(NAME)==handler)channel.pipeline().remove(NAME);}
}
