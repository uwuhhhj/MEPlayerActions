/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Format and conversion code are retained; package/type/logging adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import java.util.HashMap;
import java.util.Map;

/**
 * Blockbench 显示设置
 * 用于定义物品在不同位置的显示方式
 */
public class BBDisplaySettings {
    public BBDisplayTransform thirdperson_righthand;
    public BBDisplayTransform thirdperson_lefthand;
    public BBDisplayTransform firstperson_righthand;
    public BBDisplayTransform firstperson_lefthand;
    public BBDisplayTransform gui;
    public BBDisplayTransform head;
    public BBDisplayTransform ground;
    public BBDisplayTransform fixed;

    public static class BBDisplayTransform {
        public float[] rotation = new float[3];
        public float[] translation = new float[3];
        public float[] scale = new float[3];
    }
}
