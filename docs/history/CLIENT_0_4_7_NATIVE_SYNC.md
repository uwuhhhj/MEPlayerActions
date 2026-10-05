# 0.4.7 原生 YSM 与私人多人同步

[文档索引](../README.md) · [功能与源码](../YSM_COMPATIBILITY.md) · [客户端配置](../CLIENT_CONFIG.md)

客户端与服务端均为 **0.4.7**。本轮按固定 Sparkle-Morpher、OpenYSM-Updated、ModernYSM 源码完善原版和本地功能，并增加私人模型多人同步；旧聊天提示已移除。

## 玩家使用

私人外观与私人同步分别保存，**同步初始关闭**。选择并启用模型后默认只有自己可见。J 轮盘右上角进入统一主页，齿轮内明确开启“多人同步”；服务器未支持或未授权时不能开启，已保存开启状态始终可关闭。状态只在设置或 `/mpaclient status` 查看。

在当前实例 `config/meplayeractions/models/` 放入 `.bbmodel`、`spec:2` 文件夹、完整 ZIP／ZIP `.ysm`，或公开自包含 `.ysm` 版本 1–32，图库显式刷新。新增原 CC0 Alex／Steve 模型不替换已有选择；服务器 01／02 模型不内置客户端。

服务器授权后，完整骨架、动画、函数、组件、贴图、声音与语言归档一次，服务器按可见范围分发给安装模组的观看者。皮肤、表单、radio、额外动作和原生 `ysm.sync` 事件只传状态；作者数字 roaming 脏变更按模型保存并同步，每模型最多 64 项／名称最多 32 字符。弹簧、查询缓存和逐帧位置不上传。普通动作从真实客户端原版玩家实体读取，被观看者无需模组。

关闭同步／私人外观、离服或授权到期会撤销私人显示，本地原文件及配置保留。内容地址缓存可保留复用，但没有新 offer／ACK／续租时不能凭缓存显示旧身份。未安装模组者继续看到服务器决定的外观。

模型绘制失败会拒绝当前私人显示身份并恢复原版玩家；旧代次的失败不能移除新模型。服务器撤销本人发布后，客户端清理发布状态，等待退避后重新协商发布。

服务器伪装优先；本人手动 CLIENT 只覆盖自己看到的本人，不改变其他人看到的服务器伪装。关闭渲染总开关仍可使用 SERVER 动作轮盘、停止动作和解除服务器伪装。

## 管理员配置

升级服务端 0.4.7，原有 ModelEngine／CraftEngine 蓝图和资源包流程继续使用，不增加离线合并工具。管理员明确设置 `plugins/MEPlayerActions/config.yml`：

```yaml
client-sync:
  enabled: true
  private-models:
    enabled: true
    max-bundle-bytes: 8388608
    max-stored-bytes: 33554432
    view-distance-blocks: 64
    max-viewers: 10
```

分别授予发布者 `mact.private.upload`、观看者 `mact.private.view`，两项默认 false；玩家也需明确开启客户端同步。重载会撤销旧会话，重新协商。

观看者必须同世界、实际跟踪实体、可见且满足距离／人数范围。客户端只反馈服务器指定 offer 的缓存与就绪，不提交任意模型 ID／路径／URL 下载请求。上传下载共用连接限流、并发与内存预算。ZIP 与展开各最多 8 MiB／256 文件，纹理有像素预算；低 payload 会协商较小模型上限，避免无法完成的传输占住资源。

私人同步桥和 ModelEngine API 分离，集成在同一个 MPA JAR；客户端仍只有 Fabric Loader、Fabric API、Minecraft、Java 21 的必需依赖，未来服务器适配器实现同一协议即可复用。

## 交付与验证

[功能与来源](../YSM_COMPATIBILITY.md)逐项索引实现及具体格式条件；[第三方说明](../../THIRD_PARTY_NOTICES.md)记录原始模型和移植代码许可。新包包括两端 JAR／安装 ZIP、完整当前源码、定向测试与 build JSON。

仅运行新增和受影响检查、编译和包校验，按用户要求不启动游戏／服务器、不重复历史场景；旧实机报告不作为新版本的通过证据。实际画面、交互与多人效果由用户测试。
