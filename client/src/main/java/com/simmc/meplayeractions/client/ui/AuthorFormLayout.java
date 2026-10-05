package com.simmc.meplayeractions.client.ui;

/** Shared bounded layout for the author's ordered radio choices and scrollable forms. */
public final class AuthorFormLayout {
    private AuthorFormLayout() { }
    public static int radioColumns(int width, int largestLabelWidth, int choices) {
        return Math.max(1, Math.min(Math.max(1, choices), Math.max(1, width) / Math.max(20, largestLabelWidth + 16)));
    }
    public static int radioRows(int choices, int columns) {
        return (Math.max(0, choices) + Math.max(1, columns) - 1) / Math.max(1, columns);
    }
    public static int clampScroll(int requested, int contentHeight, int viewportHeight) {
        return Math.max(0, Math.min(requested, Math.max(0, contentHeight - Math.max(0, viewportHeight))));
    }
}
