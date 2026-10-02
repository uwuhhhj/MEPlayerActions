package com.simmc.meplayeractions.action;

import java.util.Locale;

public enum ActionState {
    IDLE, WALK, RUN, JUMP, FALL, SIT, SLEEP, BOAT, MINECART, RIDE, CRAWL_IDLE, CRAWL_WALK,
    CROUCH_IDLE, CROUCH_WALK, SWIM_IDLE, SWIM_PRONE_IDLE, SWIM_WALK, HOVER, FLY, ELYTRA,
    SWING_MAINHAND, SWING_OFFHAND, MINING;

    public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
}
