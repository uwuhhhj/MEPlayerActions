package com.simmc.meplayeractions.client.ui;

/** Presentation shared by the use button and selected preview; only runtime state proves application. */
final class GalleryModelUseState {
    enum Phase { EMPTY, CURRENT, WAITING, AVAILABLE, BLOCKED }
    record State(Phase phase, String buttonLabel, boolean canUse) {
        boolean current() { return phase == Phase.CURRENT; }
        boolean waiting() { return phase == Phase.WAITING; }
    }

    static State server(String selectedId, String currentId, String pendingId, boolean canRequest) {
        return server(selectedId,currentId,pendingId,canRequest,false);
    }

    static State server(String selectedId, String currentId, String pendingId, boolean canRequest, boolean settingsChanged) {
        if (empty(selectedId)) return new State(Phase.EMPTY, "使用模型", false);
        if (selectedId.equals(pendingId)) return new State(Phase.WAITING, "等待服务器确认", false);
        // A server binding remains current while its resource loads or local rendering is paused.
        if (selectedId.equals(currentId)) return new State(Phase.CURRENT,settingsChanged?"应用设置":"当前使用",settingsChanged&&canRequest);
        return new State(canRequest ? Phase.AVAILABLE : Phase.BLOCKED, "使用模型", canRequest);
    }

    static State local(String selectedId, String selectedHash, String currentId, String currentHash,
                       String pendingId, boolean canActivate, boolean previewReady) {
        if (empty(selectedId)) return new State(Phase.EMPTY, "使用模型", false);
        if (!canActivate) return new State(Phase.BLOCKED, "使用模型", false);
        if (selectedId.equals(pendingId)) return new State(Phase.WAITING, "正在加载", false);
        if (selectedId.equals(currentId) && !empty(currentHash)
                && (empty(selectedHash) || selectedHash.equals(currentHash)))
            return new State(Phase.CURRENT, "当前使用", false);
        return new State(previewReady ? Phase.AVAILABLE : Phase.BLOCKED, "使用模型", previewReady);
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }
    private GalleryModelUseState() { }
}
