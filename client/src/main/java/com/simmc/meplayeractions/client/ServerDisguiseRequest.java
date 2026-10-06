package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.network.ServerModelCatalogSnapshot;

/** A sent command is pending selection, never proof that the server applied the appearance. */
final class ServerDisguiseRequest {
    static final long TIMEOUT = 10_000_000_000L;
    private String modelId = "", previousInstance = "", status = "";
    private String confirmedModelId = "", confirmedInstance = "";
    private ServerDisguisePreferences pendingPreferences, confirmedPreferences;
    private java.util.UUID requestId;
    private boolean resultRequired, resultAccepted;
    private String resultInstance = "";
    private long started, savedRevision, pendingSavedRevision;
    private final java.util.Map<String,Long> savedPreferences=new java.util.LinkedHashMap<>();

    void sent(String id, String oldInstance, ServerDisguisePreferences preferences, long now) {
        sent(java.util.UUID.randomUUID(),id,oldInstance,preferences,false,now);
    }
    void sent(java.util.UUID nonce, String id, String oldInstance, ServerDisguisePreferences preferences,
              boolean needsResult, long now) {
        if (!ServerModelCatalogSnapshot.validId(id)) throw new IllegalArgumentException("Server disguise ID");
        java.util.Objects.requireNonNull(nonce); java.util.Objects.requireNonNull(oldInstance); java.util.Objects.requireNonNull(preferences);
        modelId = id; previousInstance = oldInstance; pendingPreferences = preferences;
        requestId = nonce; resultRequired = needsResult; resultAccepted = false; resultInstance = "";
        pendingSavedRevision=savedPreferences.getOrDefault(id,0L);
        started = now; status = "等待服务器确认：" + id;
    }
    boolean confirmed(String id, String instance) {
        if (modelId.isEmpty() || !modelId.equals(id) || instance.isEmpty()) return false;
        if (resultRequired ? !resultAccepted || !instance.equals(resultInstance) : previousInstance.equals(instance)) return false;
        confirmedModelId = id; confirmedInstance = instance; confirmedPreferences = pendingPreferences;
        if(savedPreferences.getOrDefault(id,0L)==pendingSavedRevision)savedPreferences.remove(id);
        modelId = previousInstance = ""; pendingPreferences = null;
        requestId = null; resultAccepted = resultRequired = false; resultInstance = "";
        status = "服务器伪装已确认"; return true;
    }
    boolean result(java.util.UUID nonce, String id, boolean success, String instance,
                   ServerDisguisePreferences actual, String code) {
        if (!resultRequired || modelId.isEmpty() || !java.util.Objects.equals(requestId,nonce) || !modelId.equals(id)) return false;
        if (!success) { failed("服务器伪装失败：" + code); return true; }
        if (instance == null || instance.isEmpty() || actual == null) throw new IllegalArgumentException("Server disguise result");
        if (!matchesActual(pendingPreferences,actual)) {
            failed("服务器实际参数与请求不一致；请检查服务器限制"); return true;
        }
        resultAccepted = true; resultInstance = instance; status = "服务器已接受伪装，等待模型绑定"; return true;
    }
    private static boolean matchesActual(ServerDisguisePreferences expected, ServerDisguisePreferences actual) {
        return matches(expected.scale(),actual.scale()) && matches(expected.hideSelf(),actual.hideSelf())
                && matches(expected.showSelf(),actual.showSelf()) && matches(expected.viewDistance(),actual.viewDistance())
                && matches(expected.maxViewers(),actual.maxViewers()) && matches(expected.delay(),actual.delay())
                && matches(expected.effect(),actual.effect());
    }
    private static boolean matches(Object expected,Object actual) {return expected==null || expected.equals(actual);}
    boolean expire(long now) {
        if (modelId.isEmpty() || now - started < TIMEOUT) return false;
        failed("服务器未确认伪装请求；请检查权限或服务器提示后重试"); return true;
    }
    void failed(String message) {
        modelId = previousInstance = ""; pendingPreferences = null; requestId = null;
        resultRequired = resultAccepted = false; resultInstance = ""; status = message;
    }
    void reset() {
        modelId = previousInstance = confirmedModelId = confirmedInstance = "";
        pendingPreferences = confirmedPreferences = null; status = ""; started = savedRevision = pendingSavedRevision = 0; savedPreferences.clear();
        requestId = null; resultRequired = resultAccepted = false; resultInstance = "";
    }
    void preferencesSaved(String id) {
        if(!ServerModelCatalogSnapshot.validId(id))throw new IllegalArgumentException("Server disguise ID");
        savedPreferences.remove(id);savedPreferences.put(id,++savedRevision);
        while(savedPreferences.size()>64)savedPreferences.remove(savedPreferences.keySet().iterator().next());
    }
    boolean preferencesNeedApply(String id, String instance, ServerDisguisePreferences saved) {
        if(savedPreferences.containsKey(id))return true;
        if (id.equals(confirmedModelId) && instance.equals(confirmedInstance) && confirmedPreferences != null)
            return !saved.equals(confirmedPreferences);
        return !saved.equals(ServerDisguisePreferences.defaults());
    }
    String modelId() { return modelId; }
    String status() { return status; }
}
