package com.simmc.meplayeractions.me;

import java.lang.invoke.*;
import java.util.*;

/** Validated Minecraft 1.21.11 packet access. Unknown packet types retain ME's normal handling. */
final class NativeEntityPackets {
    private static final String PREFIX="net.minecraft.network.protocol.game.";
    private final Map<Class<?>,MethodHandle[]> readers=new LinkedHashMap<>();
    private final Class<?> bundleClass,removeClass;
    private final MethodHandle bundleItems,removeIds,removeConstructor;
    NativeEntityPackets() {
        try {
            method("ClientboundAddEntityPacket","getId");
            field("ClientboundMoveEntityPacket","entityId");
            field("ClientboundRotateHeadPacket","entityId");
            method("ClientboundSetEntityMotionPacket","getId");
            method("ClientboundTeleportEntityPacket","id");
            method("ClientboundEntityPositionSyncPacket","id");
            method("ClientboundSetEntityDataPacket","id");
            method("ClientboundSetEquipmentPacket","getEntity");
            method("ClientboundAnimatePacket","getId");
            // Removals can contain unrelated entities, so they must be partitioned by ID.
            // Passengers retain ME's normal pivot-passenger augmentation and are never exempted.
            removeClass=Class.forName(PREFIX+"ClientboundRemoveEntitiesPacket");
            removeIds=MethodHandles.publicLookup().unreflect(removeClass.getMethod("getEntityIds"));
            bundleClass=Class.forName(PREFIX+"ClientboundBundlePacket");
            bundleItems=MethodHandles.publicLookup().unreflect(bundleClass.getMethod("subPackets"));
            removeConstructor=MethodHandles.publicLookup().unreflectConstructor(removeClass.getConstructor(int[].class));
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Minecraft 1.21.11 native entity packet contract unavailable",failure);
        }
    }
    private void method(String type,String... names) throws ReflectiveOperationException {
        Class<?> packet=Class.forName(PREFIX+type);MethodHandle[] access=new MethodHandle[names.length];
        for(int i=0;i<names.length;i++)access[i]=MethodHandles.publicLookup().unreflect(packet.getMethod(names[i]));
        readers.put(packet,access);
    }
    private void field(String type,String name) throws ReflectiveOperationException {
        Class<?> packet=Class.forName(PREFIX+type);
        readers.put(packet,new MethodHandle[]{MethodHandles.privateLookupIn(packet,MethodHandles.lookup()).findGetter(packet,name,int.class)});
    }
    List<Object> bundle(Object packet) {
        if(!bundleClass.isInstance(packet))return null;
        try {
            List<Object> result=new ArrayList<>();for(Object child:(Iterable<?>)bundleItems.invoke(packet))result.add(child);
            return result;
        } catch(Throwable failure) { throw new IllegalStateException("Cannot read native bundle",failure); }
    }
    boolean belongsTo(Object packet,Set<Integer> owners) {
        if(owners.isEmpty())return false;
        for(var entry:readers.entrySet())if(entry.getKey().isInstance(packet)) {
            try {
                for(MethodHandle getter:entry.getValue())if(contains(getter.invoke(packet),owners))return true;
                return false;
            } catch(Throwable failure) { throw new IllegalStateException("Cannot read native entity packet",failure); }
        }
        return false;
    }
    static boolean contains(Object ids,Set<Integer> owners) {
        if(ids instanceof Number id)return owners.contains(id.intValue());
        throw new IllegalArgumentException("Native entity exemption requires a single entity ID");
    }
    NativeEntityPacketHandler.Removal partitionRemoval(Object packet,Set<Integer> owners) {
        if(!removeClass.isInstance(packet))return null;
        try {
            IdPartition ids=partitionIds(removeIds.invoke(packet),owners);
            if(ids.approved.length==0)return null;
            if(ids.remaining.length==0)return new NativeEntityPacketHandler.Removal(packet,null);
            return new NativeEntityPacketHandler.Removal(removeConstructor.invoke(ids.approved),
                    removeConstructor.invoke(ids.remaining));
        } catch(Throwable failure) { throw new IllegalStateException("Cannot partition native entity removal",failure); }
    }
    record IdPartition(int[] approved,int[] remaining) {}
    static IdPartition partitionIds(Object ids,Set<Integer> owners) {
        int[] all;
        if(ids instanceof int[] many)all=many;
        else if(ids instanceof Iterable<?> many) {
            List<Integer> values=new ArrayList<>();
            for(Object value:many) {
                if(!(value instanceof Number number))throw new IllegalArgumentException("Unexpected native entity ID");
                values.add(number.intValue());
            }
            all=values.stream().mapToInt(Integer::intValue).toArray();
        } else throw new IllegalArgumentException("Unexpected native entity removal accessor");
        int approvedCount=0;for(int id:all)if(owners.contains(id))approvedCount++;
        int[] approved=new int[approvedCount],remaining=new int[all.length-approvedCount];
        int approvedIndex=0,remainingIndex=0;
        for(int id:all)if(owners.contains(id))approved[approvedIndex++]=id;else remaining[remainingIndex++]=id;
        return new IdPartition(approved,remaining);
    }
    Object remove(int id) {
        try { return removeConstructor.invoke(new int[]{id}); }
        catch(Throwable failure) { throw new IllegalStateException("Cannot remove native entity",failure); }
    }
}
