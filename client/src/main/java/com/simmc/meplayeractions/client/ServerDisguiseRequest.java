package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.network.ServerModelCatalogSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A sent command is pending selection, never proof that the server applied the appearance. */
final class ServerDisguiseRequest
{
    static final long TIMEOUT = 10_000_000_000L;
    private String modelId = "", previousInstance = "", status = "";
    private String confirmedModelId = "", confirmedInstance = "";
    private ServerDisguisePreferences pendingPreferences, confirmedPreferences;
    private UUID requestId;
    private boolean resultRequired, resultAccepted;
    private String resultInstance = "";
    private long started, savedRevision, pendingSavedRevision;
    private final Map<String, Long> savedPreferences = new LinkedHashMap<>();

    void sent(String id, String oldInstance, ServerDisguisePreferences preferences, long now)
    {
        sent(UUID.randomUUID(), id, oldInstance, preferences, false, now);
    }

    void sent(UUID nonce, String id, String oldInstance, ServerDisguisePreferences preferences, boolean needsResult,
            long now)
    {
        if (!ServerModelCatalogSnapshot.validId(id))
        {
            throw new IllegalArgumentException("Server disguise ID");
        }

        Objects.requireNonNull(nonce);
        Objects.requireNonNull(oldInstance);
        Objects.requireNonNull(preferences);
        this.modelId = id;
        this.previousInstance = oldInstance;
        this.pendingPreferences = preferences;
        this.requestId = nonce;
        this.resultRequired = needsResult;
        this.resultAccepted = false;
        this.resultInstance = "";
        this.pendingSavedRevision = this.savedPreferences.getOrDefault(id, 0L);
        this.started = now;
        this.status = "等待服务器确认：" + id;
    }

    boolean confirmed(String id, String instance)
    {
        if (this.modelId.isEmpty() || !this.modelId.equals(id) || instance.isEmpty())
        {
            return false;
        }

        if (this.resultRequired ? !this.resultAccepted || !instance.equals(this.resultInstance)
                : this.previousInstance.equals(instance))
        {
            return false;
        }

        this.confirmedModelId = id;
        this.confirmedInstance = instance;
        this.confirmedPreferences = this.pendingPreferences;
        if (this.savedPreferences.getOrDefault(id, 0L) == this.pendingSavedRevision)
        {
            this.savedPreferences.remove(id);
        }

        this.modelId = this.previousInstance = "";
        this.pendingPreferences = null;
        this.requestId = null;
        this.resultAccepted = this.resultRequired = false;
        this.resultInstance = "";
        this.status = "服务器伪装已确认";
        return true;
    }

    boolean result(UUID nonce, String id, boolean success, String instance, ServerDisguisePreferences actual,
            String code)
    {
        if (!this.resultRequired || this.modelId.isEmpty() || !Objects.equals(this.requestId, nonce)
                || !this.modelId.equals(id))
        {
            return false;
        }

        if (!success)
        {
            failed("服务器伪装失败：" + code);
            return true;
        }
        if (instance == null || instance.isEmpty() || actual == null)
        {
            throw new IllegalArgumentException("Server disguise result");
        }

        if (!matchesActual(this.pendingPreferences, actual))
        {
            failed("服务器实际参数与请求不一致；请检查服务器限制");
            return true;
        }
        this.resultAccepted = true;
        this.resultInstance = instance;
        this.status = "服务器已接受伪装，等待模型绑定";
        return true;
    }

    private static boolean matchesActual(ServerDisguisePreferences expected, ServerDisguisePreferences actual)
    {
        return matches(expected.scale(), actual.scale()) && matches(expected.hideSelf(), actual.hideSelf())
                && matches(expected.showSelf(), actual.showSelf())
                && matches(expected.viewDistance(), actual.viewDistance())
                && matches(expected.maxViewers(), actual.maxViewers()) && matches(expected.delay(), actual.delay())
                && matches(expected.effect(), actual.effect());
    }

    private static boolean matches(Object expected, Object actual)
    {
        return expected == null || expected.equals(actual);
    }

    boolean expire(long now)
    {
        if (this.modelId.isEmpty() || now - this.started < TIMEOUT)
        {
            return false;
        }

        failed("服务器未确认伪装请求；请检查权限或服务器提示后重试");
        return true;
    }

    void failed(String message)
    {
        this.modelId = this.previousInstance = "";
        this.pendingPreferences = null;
        this.requestId = null;
        this.resultRequired = this.resultAccepted = false;
        this.resultInstance = "";
        this.status = message;
    }

    void reset()
    {
        this.modelId = this.previousInstance = this.confirmedModelId = this.confirmedInstance = "";
        this.pendingPreferences = this.confirmedPreferences = null;
        this.status = "";
        this.started = this.savedRevision = this.pendingSavedRevision = 0;
        this.savedPreferences.clear();
        this.requestId = null;
        this.resultRequired = this.resultAccepted = false;
        this.resultInstance = "";
    }

    void preferencesSaved(String id)
    {
        if (!ServerModelCatalogSnapshot.validId(id))
        {
            throw new IllegalArgumentException("Server disguise ID");
        }

        this.savedPreferences.remove(id);
        this.savedPreferences.put(id, ++this.savedRevision);
        while (this.savedPreferences.size() > 64)
        {
            this.savedPreferences.remove(this.savedPreferences.keySet().iterator().next());
        }

    }

    boolean preferencesNeedApply(String id, String instance, ServerDisguisePreferences saved)
    {
        if (this.savedPreferences.containsKey(id))
        {
            return true;
        }

        if (id.equals(this.confirmedModelId) && instance.equals(this.confirmedInstance)
                && this.confirmedPreferences != null)

        {
            return !saved.equals(this.confirmedPreferences);
        }

        return !saved.equals(ServerDisguisePreferences.defaults());
    }

    String modelId()
    {
        return this.modelId;
    }

    String status()
    {
        return this.status;
    }
}
