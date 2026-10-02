package com.simmc.meplayeractions.gameplay;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.List;

/** Public Paper potion API adapter. Types are resolved before any disguise mutations. */
public final class PaperEffectPort implements DisguiseEffects.Port {
    private final Player player;
    public PaperEffectPort(Player player) { this.player = player; }
    public static boolean supported(String id) {
        if (!id.equals("slowness")) return false;
        PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(id));
        return type != null && !type.isInstant();
    }
    public static List<String> names() {
        return supported("slowness") ? List.of("slowness") : List.of();
    }
    private static PotionEffectType type(String id) {
        PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(id));
        if (type == null) throw new IllegalArgumentException("药水不存在：" + id);
        return type;
    }
    @Override public DisguiseEffects.State current(String id) { return state(player.getPotionEffect(type(id))); }
    @Override public boolean add(DisguiseEffects.State state) { return player.addPotionEffect(potion(state)); }
    @Override public void remove(String id) { player.removePotionEffect(type(id)); }
    private static DisguiseEffects.State state(PotionEffect effect) {
        return effect == null ? null : new DisguiseEffects.State(effect.getType().getKey().getKey(),
                effect.getAmplifier(), effect.getDuration(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon(),
                state(effect.getHiddenPotionEffect()));
    }
    private static PotionEffect potion(DisguiseEffects.State state) {
        return state == null ? null : new PotionEffect(type(state.id()), state.duration(), state.amplifier(),
                state.ambient(), state.particles(), state.icon(), potion(state.hidden()));
    }
}
