package com.simmc.meplayeractions.client.render;

import java.util.UUID;

/** Native entity identity captured before drawing; component decisions never look up mutable entities there. */
public interface ComponentRenderState {
    UUID meplayeractions$entityUuid();
    void meplayeractions$entityUuid(UUID uuid);
}
