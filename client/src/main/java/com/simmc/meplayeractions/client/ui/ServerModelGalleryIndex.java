package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.network.ServerModelCatalogSnapshot;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Display-only server folders. Neither a directory card nor a cached ID grants download authority. */
public final class ServerModelGalleryIndex {
    public record Entry(String id, String source, String folder, boolean cachedOnly) {
        public Entry {
            if (!ServerModelCatalogSnapshot.validSource(source) || !ServerModelCatalogSnapshot.validFolder(folder))
                throw new IllegalArgumentException("Server gallery location");
        }
    }
    public record Folder(String path, String label, int count) { }
    private static final List<String> SOURCES = List.of("own", "modelengine", "unknown", "cache");
    private static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    /** Virtual unclassified cards are separate from every possible authored directory name. */
    public static String path(Entry entry) {
        String source = entry.cachedOnly() ? "cache" : entry.source();
        return source + "/" + (entry.folder().isEmpty() ? "unclassified/" : "folders/" + entry.folder() + "/") + entry.id();
    }
    public static boolean direct(String directory, Entry entry) { return parentModel(path(entry)).equals(directory); }
    public static boolean contains(String directory, Entry entry) { return path(entry).startsWith(directory); }
    public static int priority(Entry entry) {return SOURCES.indexOf(entry.cachedOnly()?"cache":entry.source());}
    public static List<Folder> folders(Collection<Entry> entries, String directory) {
        Map<String, Integer> counts = new TreeMap<>(ORDER);
        for (Entry entry : entries) {
            String path = path(entry);
            if (!path.startsWith(directory)) continue;
            String remainder = path.substring(directory.length());
            int slash = remainder.indexOf('/');
            if (slash < 0) continue;
            String child = directory + remainder.substring(0, slash + 1);
            // The internal folders/ segment separates author folders from the virtual unclassified group.
            if (directory.indexOf('/') == directory.length() - 1 && remainder.startsWith("folders/")) {
                String authored = remainder.substring(8); int childEnd = authored.indexOf('/');
                if (childEnd < 0) continue;
                child = directory + "folders/" + authored.substring(0, childEnd + 1);
            }
            counts.merge(child, 1, Integer::sum);
        }
        return counts.entrySet().stream().sorted((left, right) -> {
            if (directory.isEmpty()) {
                int order = Integer.compare(sourceOrder(left.getKey()), sourceOrder(right.getKey()));
                if (order != 0) return order;
            }
            return ORDER.compare(left.getKey(), right.getKey());
        }).map(entry -> new Folder(entry.getKey(), label(entry.getKey()), entry.getValue())).toList();
    }
    public static String parent(String directory) {
        if (directory.isEmpty()) return "";
        String path = directory.endsWith("/") ? directory.substring(0, directory.length() - 1) : directory;
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "";
        String parent = path.substring(0, slash + 1);
        return SOURCES.stream().anyMatch(source -> parent.equals(source + "/folders/"))
                ? parent.substring(0, parent.length() - 8) : parent;
    }
    public static String location(Entry entry) {
        String source = entry.cachedOnly() ? "本地历史缓存 · 仅预览" : sourceName(entry.source());
        return source + "\n目录：" + (entry.folder().isEmpty() ? "未分类（根目录文件）" : entry.folder());
    }
    public static String directoryLabel(String directory) {
        if (directory.isEmpty()) return "全部模型来源";
        int slash = directory.indexOf('/');
        String source = directory.substring(0, slash), suffix = directory.substring(slash + 1);
        if (suffix.equals("unclassified/")) return sourceName(source) + " / 未分类";
        if (suffix.startsWith("folders/")) suffix = suffix.substring(8);
        if (suffix.endsWith("/")) suffix = suffix.substring(0, suffix.length() - 1);
        return sourceName(source) + (suffix.isEmpty() ? "" : " / " + suffix);
    }
    private static String label(String path) {
        if (path.equals("own/")) return "MPA models";
        if (path.equals("modelengine/")) return "ME 蓝图";
        if (path.equals("unknown/")) return "来源未分类";
        if (path.equals("cache/")) return "本地缓存";
        if (path.endsWith("/unclassified/") && !path.contains("/folders/")) return "未分类";
        String trimmed = path.substring(0, path.length() - 1);
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }
    private static String sourceName(String source) {
        return switch (source) {
            case "own" -> "MEPlayerActions/models";
            case "modelengine" -> "ModelEngine/blueprints";
            case "cache" -> "本地历史缓存";
            default -> "服务器来源未分类";
        };
    }
    private static String parentModel(String path) { return path.substring(0, path.lastIndexOf('/') + 1); }
    private static int sourceOrder(String path) { return SOURCES.indexOf(path.substring(0, path.indexOf('/'))); }
    private ServerModelGalleryIndex() { }
}
