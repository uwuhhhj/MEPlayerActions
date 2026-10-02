package com.simmc.meplayeractions.client.render;

/** Captures the hide decision during extraction, before render states reach drawing. */
public interface HiddenPlayerRenderState {
    boolean meplayeractions$isHidden();
    void meplayeractions$setHidden(boolean hidden);
}
