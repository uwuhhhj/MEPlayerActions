package com.simmc.meplayeractions.client.ui;

import java.util.List;
import java.util.Objects;

/** A screen's browsing draft; it has no runtime/network/settings reference and cannot apply an appearance. */
final class GalleryPreviewSelection {
    enum Source {
        CURRENT, SELECTED, VANILLA
    }

    record Target(Source source, String modelId) {
    }

    record Request(String modelId, long revision) {
    }

    private String modelId;
    private boolean browsed;
    private long revision;

    GalleryPreviewSelection(String initialModelId) {
        restore(initialModelId);
    }

    String modelId() {
        return this.modelId;
    }

    void browse(String id) {
        this.modelId = Objects.requireNonNull(id);
        this.browsed = !id.isEmpty();
        this.revision++;
    }

    void restore(String id) {
        this.modelId = Objects.requireNonNull(id);
        this.browsed = false;
        this.revision++;
    }

    void retain(List<String> available) {
        if (!available.contains(this.modelId)) {
            restore(available.isEmpty() ? "" : available.getFirst());
        }

    }

    Target target(String currentId, String currentHash, String selectedHash) {
        if (this.browsed && !this.modelId.isEmpty()) {
            boolean current = this.modelId.equals(currentId) && selectedHash != null
                    && selectedHash.equals(currentHash);
            return new Target(current ? Source.CURRENT : Source.SELECTED, this.modelId);
        }
        return currentId == null || currentId.isEmpty() ? new Target(Source.VANILLA, "")
                : new Target(Source.CURRENT, currentId);
    }

    Request request(String id) {
        return new Request(id, this.revision);
    }

    boolean isSelectedRequest(Request request) {
        return this.browsed && request.revision() == this.revision && request.modelId().equals(this.modelId);
    }
}
