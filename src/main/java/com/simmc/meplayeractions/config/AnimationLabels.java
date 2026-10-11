package com.simmc.meplayeractions.config;

import java.util.Map;
import static java.util.Map.entry;

/** Built-in labels remain available when an administrator keeps an older config.yml. */
public final class AnimationLabels {
    private AnimationLabels() {}
    /** Keep a named action identifiable while avoiding the fallback form "ID (ID)". */
    public static String displayWithId(String id, String label) {
        return id.equals(label) ? id : label + " (" + id + ")";
    }
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            entry("idle", "站立待机"), entry("walk", "行走"), entry("run", "奔跑"),
            entry("death", "死亡"), entry("jump", "跳跃"), entry("player_jump", "完整跳跃"), entry("fall", "坠落"),
            entry("fly", "飞行"), entry("hover", "悬停"), entry("elytra_fly", "鞘翅滑翔"), entry("hurt", "受伤"),
            entry("crouch_idle", "潜行待机"), entry("crouch_walk", "潜行移动"),
            entry("swim", "游泳"), entry("swim_idle", "踩水待机"), entry("swim_prone_idle", "水平游泳待机"),
            entry("sit", "坐下"), entry("sleep", "卷曲睡眠"), entry("bed_sleep", "横卧睡眠"), entry("lay", "躺下"),
            entry("sit_boat", "乘船"), entry("sit_minecart", "乘坐矿车"), entry("ride", "骑乘"),
            entry("climb", "爬行移动"), entry("climbing", "趴下待机"), entry("climb_idle", "攀爬待机"),
            entry("sneak", "潜行移动"), entry("sneaking", "潜行待机"), entry("swim_stand", "踩水待机"),
            entry("swing_hand", "挥臂"), entry("attacked", "受伤"), entry("boat", "乘船"), entry("minecart", "乘坐矿车"),
            entry("ride_pig", "骑乘猪"), entry("ladder_up", "攀爬梯子"), entry("ladder_stillness", "梯子停留"),
            entry("gui", "展示姿态"), entry("focus", "注视"), entry("hover_fadeout", "悬停结束"),
            entry("extra0", "切换花朵与帽子"), entry("extra1", "专用动作一"), entry("extra2", "专用动作二"),
            entry("extra3", "专用动作三"), entry("extra4", "专用动作四"), entry("extra5", "专用动作五"),
            entry("extra6", "专用动作六"), entry("extra7", "专用动作七"),
            entry("pre_parallel1", "发丝与飘带物理"), entry("pre_parallel2", "耳朵物理"),
            entry("pre_parallel3", "辅助眨眼"), entry("parallel0", "配饰开关"),
            entry("crawl_idle", "趴下待机"), entry("crawl_walk", "爬行移动"),
            entry("use_mainhand", "使用主手"), entry("use_offhand", "使用副手"),
            entry("attack", "主手攻击／挥臂"), entry("attack_offhand", "副手攻击／挥臂"),
            entry("swing", "挥臂"), entry("mining", "挖掘"), entry("dig", "挖掘"),
            entry("ribbon_sway", "飘带摆动"), entry("tail_hair_sway", "尾巴与发丝摆动"),
            entry("blink", "眨眼"), entry("wave", "挥手"), entry("nod", "点头"), entry("talk", "说话"),
            entry("smile", "微笑"), entry("surprised", "惊讶"));
}
