/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Format and conversion code are retained; package/type/logging adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import java.util.UUID;

/**
 * Blockbench 纹理
 * 支持嵌入式（base64）和外部引用
 */
public class BBTexture {
    public String uuid = "";
    public String name = "";
    public String source = "";
    public String path = "";
    public String relative_path = "";
    public int[] frames = new int[0];
    public int frame_time = 1;
    public boolean visible = true;
    public boolean internal = false;
    public int id = 0;
    public int uv_width = 16;
    public int uv_height = 16;
    public int width = 0;
    public int height = 0;

    /**
     * 检查是否为嵌入式纹理（base64 数据）
     */
    public boolean isEmbedded() {
        return source != null && source.startsWith("data:");
    }

    /**
     * 检查是否为外部文件引用
     */
    public boolean isExternal() {
        return path != null && !path.isEmpty();
    }
}
