package com.simmc.meplayeractions.client.model;

/** Local disk imports have no network transfer; geometry, image pixels and file counts stay bounded separately. */
public final class LocalModelBudget {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private LocalModelBudget() { }
}
