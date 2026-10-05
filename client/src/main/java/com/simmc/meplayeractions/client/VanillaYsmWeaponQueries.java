package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric WeaponActionBridgeImpl/YSMBinding port from Sparkle-Morpher b1230a4 (MIT).
 * Inputs are native tracked state; these predicates never infer a successful hit or wind burst.
 */
public final class VanillaYsmWeaponQueries {
    private VanillaYsmWeaponQueries() { }
    public record Sample(String kind, boolean usingMainHand, String useAction, boolean attackingMainHand,
                         boolean riptide, boolean riding, boolean gliding, boolean grounded,
                         double horizontalSpeed, double verticalSpeed, double fallDistance,
                         double useTicks, double attackTicks, double maxUseTicks) { }

    public static Map<String,Double> values(Sample sample) {
        Map<String,Double> result = new LinkedHashMap<>();
        for (String key : PROPERTY_NAMES) result.put(key, 0d);
        int kind = switch (sample.kind()) { case "spear" -> 1; case "lance" -> 2; case "mace" -> 3; default -> 0; };
        result.put("weapon_type", (double) kind);
        if (kind == 0) return Map.copyOf(result); // WeaponActionState.EMPTY retains zero movement.
        result.put("weapon_speed", sample.horizontalSpeed());
        boolean using = sample.usingMainHand() && (sample.useAction().equals("spear") || sample.useAction().equals("trident"));
        boolean attack = sample.attackingMainHand();
        double useTicks = using ? Math.max(0, sample.useTicks()) : 0;
        double attackTicks = attack ? Math.max(0, sample.attackTicks()) : 0;
        if (kind == 1) {
            set(result, "weapon_is_trident", true);set(result,"trident_holding",true);
            set(result,"trident_using",using);set(result,"trident_throwing",using);
            set(result,"trident_riptide",sample.riptide());set(result,"trident_attack",attack);
            result.put("trident_use_ticks",useTicks);result.put("trident_attack_ticks",attackTicks);
            set(result,"weapon_attacking",attack);set(result,"weapon_using",using);
        } else if (kind == 2) {
            boolean lunging = sample.horizontalSpeed() >= .35 && (using || attack || sample.riding() || sample.gliding());
            boolean jabbing = attack && !lunging;
            set(result,"weapon_is_lance",true);set(result,"lance_holding",true);
            set(result,"lance_using",using);set(result,"lance_charging",using);
            set(result,"lance_jabbing",jabbing);set(result,"lance_lunging",lunging);
            set(result,"lance_riding",sample.riding());set(result,"lance_riding_charge",sample.riding()&&using);
            set(result,"lance_fall_flying",sample.gliding());result.put("lance_speed",sample.horizontalSpeed());
            result.put("lance_use_ticks",useTicks);result.put("lance_attack_ticks",attackTicks);
            result.put("lance_charge_progress",using&&sample.maxUseTicks()>0?Math.min(1,useTicks/sample.maxUseTicks()):0);
            set(result,"weapon_attacking",jabbing);set(result,"weapon_using",using);
            set(result,"weapon_riding",sample.riding());set(result,"weapon_fall_flying",sample.gliding());
        } else {
            boolean falling=!sample.grounded()&&sample.verticalSpeed()<-.08&&sample.fallDistance()>0;
            boolean canSmash=falling&&sample.fallDistance()>=1.5;
            set(result,"weapon_is_mace",true);set(result,"mace_holding",true);
            set(result,"mace_falling",falling);set(result,"mace_can_smash",canSmash);
            set(result,"mace_smashing",attack&&canSmash); // Author's Fabric bridge has no observed wind-burst flag.
            set(result,"mace_attacking",attack);set(result,"mace_riding",sample.riding());
            set(result,"mace_fall_flying",sample.gliding());result.put("mace_fall_distance",sample.fallDistance());
            result.put("mace_vertical_speed",sample.verticalSpeed());result.put("mace_attack_ticks",attackTicks);
            result.put("mace_smash_progress",canSmash?Math.min(1,sample.fallDistance()/3):0);
            set(result,"weapon_attacking",attack);set(result,"weapon_riding",sample.riding());
            set(result,"weapon_fall_flying",sample.gliding());
            useTicks=0;
        }
        result.put("weapon_attack_ticks",attackTicks);result.put("weapon_use_ticks",useTicks);
        return Map.copyOf(result);
    }
    public static void populate(Molang.Context context, Sample sample) {
        values(sample).forEach((name,value)->context.query("ysm."+name,value));
    }
    public static void unavailable(Molang.Context context) {
        for(String name:PROPERTY_NAMES) context.query("ysm."+name,(Object)null);
    }
    private static void set(Map<String,Double> result,String name,boolean value) { result.put(name,value?1d:0d); }
    public static final String[] PROPERTY_NAMES = {
            "weapon_type","weapon_is_trident","weapon_is_lance","weapon_is_mace","weapon_attacking","weapon_using",
            "weapon_riding","weapon_fall_flying","weapon_speed","weapon_attack_ticks","weapon_use_ticks",
            "trident_holding","trident_using","trident_throwing","trident_riptide","trident_attack","trident_use_ticks","trident_attack_ticks",
            "lance_holding","lance_using","lance_charging","lance_jabbing","lance_lunging","lance_riding","lance_riding_charge",
            "lance_fall_flying","lance_use_ticks","lance_attack_ticks","lance_speed","lance_charge_progress",
            "mace_holding","mace_falling","mace_can_smash","mace_smashing","mace_wind_bursting","mace_attacking","mace_riding",
            "mace_fall_flying","mace_fall_distance","mace_vertical_speed","mace_attack_ticks","mace_smash_progress"
    };
}
