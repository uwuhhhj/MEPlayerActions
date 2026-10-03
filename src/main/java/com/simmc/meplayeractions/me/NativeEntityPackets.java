package com.simmc.meplayeractions.me;

import java.lang.invoke.*;
import java.util.*;

/** Validated Minecraft 1.21.11 packet access. Unknown packet types retain ME's normal handling. */
final class NativeEntityPackets {
    private static final String PREFIX="net.minecraft.network.protocol.game.";
    private final Map<Class<?>,MethodHandle[]> readers=new LinkedHashMap<>();
    private final Class<?> bundleClass;
    private final MethodHandle bundleItems,removeConstructor;
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
            method("ClientboundRemoveEntitiesPacket","getEntityIds");
            method("ClientboundSetPassengersPacket","getVehicle","getPassengers");
            bundleClass=Class.forName(PREFIX+"ClientboundBundlePacket");
            bundleItems=MethodHandles.publicLookup().unreflect(bundleClass.getMethod("subPackets"));
            removeConstructor=MethodHandles.publicLookup().unreflectConstructor(Class.forName(PREFIX+"ClientboundRemoveEntitiesPacket").getConstructor(int[].class));
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
        if(ids instanceof int[] many) { for(int id:many)if(owners.contains(id))return true;return false; }
        if(ids instanceof Iterable<?> many) { for(Object id:many)if(id instanceof Number number && owners.contains(number.intValue()))return true;return false; }
        throw new IllegalArgumentException("Unexpected native entity ID accessor");
    }
    Object remove(int id) {
        try { return removeConstructor.invoke(new int[]{id}); }
        catch(Throwable failure) { throw new IllegalStateException("Cannot remove native entity",failure); }
    }
}
