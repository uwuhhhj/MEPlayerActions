package com.simmc.meplayeractions.client;

import java.util.*;
import java.util.function.Supplier;

/** Main-thread, generation-scoped nearest audiences. Each publisher has its own refresh phase. */
final class PrivateAudienceCache {
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Map<UUID,Set<UUID>> ownersByViewer=new HashMap<>();
    private int refreshTicks=40;

    void configure(int refreshTicks) {
        if(refreshTicks<1 || refreshTicks>1200)throw new IllegalArgumentException("Private audience refresh interval");
        if(this.refreshTicks!=refreshTicks){this.refreshTicks=refreshTicks;clear();}
    }
    boolean refreshIfDue(UUID owner,UUID generation,long tick,Supplier<Set<UUID>> discover) {
        Entry entry=entries.get(owner);
        if(entry!=null && entry.generation.equals(generation) && distance(tick,entry.refreshed)<entry.delay)return false;
        Set<UUID> viewers=Collections.unmodifiableSet(new LinkedHashSet<>(discover.get()));
        replace(owner,new Entry(generation,viewers,tick,nextDelay(owner,tick)));return true;
    }
    Set<UUID> viewers(UUID owner,UUID generation) {
        Entry entry=entries.get(owner);return entry!=null && entry.generation.equals(generation)?entry.viewers:Set.of();
    }
    boolean contains(UUID owner,UUID generation,UUID viewer){return viewers(owner,generation).contains(viewer);}
    void invalidate(UUID owner,UUID generation,long tick){replace(owner,new Entry(generation,Set.of(),tick,nextDelay(owner,tick)));}
    void remove(UUID owner){Entry old=entries.remove(owner);if(old!=null)unlink(owner,old.viewers);}
    void removeViewer(UUID viewer) {
        Set<UUID> owners=ownersByViewer.remove(viewer);if(owners==null)return;
        for(UUID owner:owners) {
            Entry entry=entries.get(owner);if(entry==null)continue;
            Set<UUID> retained=new LinkedHashSet<>(entry.viewers);retained.remove(viewer);
            entries.put(owner,new Entry(entry.generation,Collections.unmodifiableSet(retained),entry.refreshed,entry.delay));
        }
    }
    void clear(){entries.clear();ownersByViewer.clear();}
    private int nextDelay(UUID owner,long tick){int phase=Math.floorMod(owner.hashCode(),refreshTicks);return Math.floorMod(phase-(int)(tick%refreshTicks)-1,refreshTicks)+1;}
    private void replace(UUID owner,Entry entry) {
        Entry old=entries.put(owner,entry);if(old!=null)unlink(owner,old.viewers);
        for(UUID viewer:entry.viewers)ownersByViewer.computeIfAbsent(viewer,unused->new HashSet<>()).add(owner);
    }
    private void unlink(UUID owner,Set<UUID> viewers) {
        for(UUID viewer:viewers) {
            Set<UUID> owners=ownersByViewer.get(viewer);if(owners==null)continue;
            owners.remove(owner);if(owners.isEmpty())ownersByViewer.remove(viewer);
        }
    }
    private static long distance(long tick,long previous){return(tick-previous)&0xffffffffL;}
    private record Entry(UUID generation,Set<UUID> viewers,long refreshed,int delay) {}
}
