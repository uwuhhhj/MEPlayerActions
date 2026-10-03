package com.simmc.meplayeractions.client;

import java.util.Objects;

/** Bounded wheel memory. The runtime decides the effective source for the current disguise instance. */
public record WheelPreferences(Source source, int clientPage, int serverPage, boolean keepOpen) {
    public static final int MAX_PAGE = 127;
    public enum Source { CLIENT, SERVER }

    public WheelPreferences {
        Objects.requireNonNull(source);
        if (clientPage < 0 || clientPage > MAX_PAGE || serverPage < 0 || serverPage > MAX_PAGE)
            throw new IllegalArgumentException("Wheel page outside limits");
    }

    public WheelPreferences(Source source, int clientPage, int serverPage) { this(source, clientPage, serverPage, false); }

    public static WheelPreferences defaults() { return new WheelPreferences(Source.CLIENT, 0, 0, false); }

    public int page(Source source) { return Objects.requireNonNull(source) == Source.CLIENT ? clientPage : serverPage; }

    /** Restore the remembered page within the current source's actual catalog, without destroying the other memory. */
    public int page(Source source, int pageCount) {
        if (pageCount < 1) throw new IllegalArgumentException("Wheel page count must be positive");
        return Math.min(page(source), pageCount - 1);
    }

    public WheelPreferences withSource(Source selected) { return new WheelPreferences(selected, clientPage, serverPage, keepOpen); }

    public WheelPreferences withPage(Source selected, int page) {
        Objects.requireNonNull(selected);
        return selected == Source.CLIENT ? new WheelPreferences(source, page, serverPage, keepOpen)
                : new WheelPreferences(source, clientPage, page, keepOpen);
    }

    public WheelPreferences withKeepOpen(boolean value) { return new WheelPreferences(source, clientPage, serverPage, value); }
}
