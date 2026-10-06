/*
 * Migrated from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT; see assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Format and conversion code are retained; package/type/logging adapters target MPA.
 */
package com.simmc.meplayeractions.client.model.nativebbmodel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Blockbench 集合
 * 用于组织和管理模型元素
 */
public class BBCollection {
    public String uuid = "";
    public String name = "";
    public boolean isOpen = false;
    public String export_path = "";
    public boolean saved = false;
    public boolean locked = false;

    public List<String> children = new ArrayList<>();

    public BBCollection() {}

    public BBCollection(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }
}
