package com.simmc.meplayeractions.client.ui;

import java.util.List;
import java.util.Objects;

/** A screen's browsing draft; it has no runtime/network/settings reference and cannot apply an appearance. */
final class GalleryPreviewSelection {
    enum Source { CURRENT, SELECTED, VANILLA }
    record Target(Source source, String modelId) { }
    record Request(String modelId, long revision) { }
    private String modelId;
    private boolean browsed;
    private long revision;

    GalleryPreviewSelection(String initialModelId) { restore(initialModelId); }
    String modelId() { return modelId; }

    void browse(String id) {
        modelId = Objects.requireNonNull(id);
        browsed = !id.isEmpty();
        revision++;
    }

    void restore(String id) {
        modelId = Objects.requireNonNull(id);
        browsed = false;
        revision++;
    }

    void retain(List<String> available) {
        if (!available.contains(modelId)) restore(available.isEmpty() ? "" : available.getFirst());
    }

    Target target(String currentId, String currentHash, String selectedHash) {
        if (browsed && !modelId.isEmpty()) {
            boolean current = modelId.equals(currentId) && selectedHash != null && selectedHash.equals(currentHash);
            return new Target(current ? Source.CURRENT : Source.SELECTED, modelId);
        }
        return currentId == null || currentId.isEmpty() ? new Target(Source.VANILLA, "")
                : new Target(Source.CURRENT, currentId);
    }

    Request request(String id) { return new Request(id, revision); }
    boolean isSelectedRequest(Request request) {
        return browsed && request.revision() == revision && request.modelId().equals(modelId);
    }
}
