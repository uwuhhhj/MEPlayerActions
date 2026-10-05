package com.simmc.meplayeractions.client.ui;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import java.util.*;

/** Extra animation keys are clips/categories; their values are labels or configuration links. */
public final class ModelActionMenu {
    public record Entry(String id, String label, String configGroup, String description) {
        public Entry(String id, String label, String configGroup) { this(id, label, configGroup, ""); }
        public boolean category() { return id.startsWith("#") && !id.equals("#return"); }
        public boolean back() { return id.equals("#return"); }
    }
    private final List<Entry> root;
    private final Map<String, List<Entry>> categories;
    private final Deque<String> path = new ArrayDeque<>();
    public ModelActionMenu(YsmModelProfile profile, String locale) {
        if (profile == null) { root = List.of(); categories = Map.of(); return; }
        ModelConfigSchema forms = ModelConfigSchema.from(profile, locale);
        root = entries(profile.extraAnimations(), forms, profile, locale);
        Map<String, List<Entry>> classified = new LinkedHashMap<>();
        for (JsonElement value : profile.extraAnimationClassify()) {
            JsonObject definition = value.getAsJsonObject(); String id = definition.get("id").getAsString();
            JsonObject extras = definition.has("extra_animation") ? definition.getAsJsonObject("extra_animation") : new JsonObject();
            if (classified.size() >= 32 || classified.containsKey(id) || extras.size() > 128)
                throw new IllegalArgumentException("模型动作分类无效");
            Map<String, String> mapping = new LinkedHashMap<>(); extras.entrySet().forEach(entry -> mapping.put(entry.getKey(), entry.getValue().getAsString()));
            classified.put(id, entries(mapping, forms, profile, locale));
        }
        categories = Collections.unmodifiableMap(classified);
    }
    private static List<Entry> entries(Map<String, String> mapping, ModelConfigSchema forms, YsmModelProfile profile, String locale) {
        List<Entry> entries = new ArrayList<>();
        mapping.forEach((id, value) -> {
            String config = value.startsWith("#") ? value.substring(1) : "";
            String label = config.isEmpty() ? value.isEmpty() ? id : value
                    : forms.group(config).map(ModelConfigSchema.Group::name).orElse(id);
            if (id.equals("#return")) label = "返回上级";
            else if (id.startsWith("#") && value.isEmpty()) label = id.substring(1);
            String path="properties.extra_animation."+id;
            entries.add(new Entry(id, profile.localized(locale, path, profile.localized(locale, label, label)), config,
                    profile.localized(locale, path+".desc", "")));
        });
        return List.copyOf(entries);
    }
    public List<Entry> entries() { return path.isEmpty() ? root : categories.getOrDefault(path.peekLast(), List.of()); }
    public String categoryId() { return path.isEmpty() ? "" : path.peekLast(); }
    public int depth() { return path.size(); }
    public boolean enter(Entry entry) {
        if (entry.back()) return back();
        if (!entry.category()) return false;
        String id = entry.id().substring(1);
        if (!categories.containsKey(id) || path.contains(id) || path.size() >= 32) return false;
        path.addLast(id); return true;
    }
    public boolean back() { if (path.isEmpty()) return false; path.removeLast(); return true; }
}
