package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import java.util.*;

/** Directory cards are derived from validated model IDs, never from untrusted path navigation. */
public final class ModelGalleryIndex {
    private ModelGalleryIndex() { }
    public record Folder(String path,String label,int count) { }
    public static int source(String id) {return id.startsWith("ysm:")?2:id.startsWith("local:")?3:1;}
    public static String root(int source) {return switch(source){case 1->"builtin/";case 2->"ysm/";case 3->"bbmodel/";default->"";};}
    public static String path(String id) {
        if(!LocalAppearanceSettings.isValidModelId(id))return "";
        int source=source(id);
        return root(source)+(source==2?id.substring(4):source==3?id.substring(6):id);
    }
    public static String parent(String path) {
        String trimmed=path.endsWith("/")?path.substring(0,path.length()-1):path;
        int slash=trimmed.lastIndexOf('/');return slash<0?"":trimmed.substring(0,slash+1);
    }
    public static boolean contains(String directory,String id) {String path=path(id);return !path.isEmpty() && path.startsWith(directory);}
    public static boolean direct(String directory,String id) {String path=path(id);return !path.isEmpty() && parent(path).equals(directory);}
    public static List<Folder> folders(Collection<String> modelIds,String directory) {
        Map<String,Integer> counts=new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for(String id:modelIds) {
            String path=path(id);
            if(path.isEmpty() || !path.startsWith(directory))continue;
            String remainder=path.substring(directory.length());int slash=remainder.indexOf('/');
            if(slash<0)continue;
            String child=directory+remainder.substring(0,slash+1);counts.merge(child,1,Integer::sum);
        }
        return counts.entrySet().stream().map(entry->{
            String path=entry.getKey(),trimmed=path.substring(0,path.length()-1);
            return new Folder(path,trimmed.substring(trimmed.lastIndexOf('/')+1),entry.getValue());
        }).toList();
    }
}
