package com.simmc.meplayeractions.action;

import com.simmc.meplayeractions.client.ClientSyncService.AnimationInfo;
import com.simmc.meplayeractions.config.Settings;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/** The client sends these IDs through the same play command as the server menu. */
public final class ActionDirectory {
    private ActionDirectory() { }

    public static List<AnimationInfo> build(Settings settings, Collection<String> animations) {
        var existing = new HashSet<>(animations);
        var directory = new TreeMap<String, AnimationInfo>();
        for (var action : settings.customActions.values()) {
            if (existing.contains(action.animation())) {
                directory.put(action.id(), new AnimationInfo(action.id(), action.label()));
            }
        }
        if (settings.rawPlay) for (String clip : existing) {
            // A configured alias always owns its ID, even when its target clip is missing.
            if (settings.customActions.containsKey(clip)) continue;
            try { Settings.id(clip); }
            catch (IllegalArgumentException invalidCommandId) { continue; }
            directory.putIfAbsent(clip, new AnimationInfo(clip, settings.animationLabel(clip)));
        }
        return List.copyOf(directory.values());
    }
}
