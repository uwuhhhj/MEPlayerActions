package com.simmc.meplayeractions.protection;

/** Command/API refusal retaining its machine-readable cause for client confirmations. */
public final class ResourceRejectedException extends IllegalStateException {
    private final ResourceError error;
    public ResourceRejectedException(ResourceError error) {
        super("模型资源操作暂不可用 ["+error.code()+"]，阶段："+error.stage()
                +(error.retryable()?"；请在 "+error.retryAfterSeconds()+" 秒后重试":"；请检查模型或权限"));
        this.error=error;
    }
    public ResourceError error() { return error; }
}
