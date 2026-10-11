# 性能配置

[文档索引](README.md) · [模型部署](MODEL_DELIVERY.md) · [资源保护](SERVER_RESOURCE_PROTECTION.md) · [架构](../ARCHITECTURE.md) · [协议](CLIENT_PROTOCOL.md)

后台发现与模型动画使用独立频率；配置缺少以下字段时采用默认值，无需覆盖已有 `config.yml`。

```yaml
performance:
  audience-refresh-ticks: 40
  validation-ticks: 20
  adoption-scan-ticks: 100
```

| 配置 | 默认值／范围 | 影响 |
| --- | --- | --- |
| `audience-refresh-ticks` | 40／20–100 tick | 新观众发现、最近观众选择；默认约 2 秒，按实例错峰 |
| `validation-ticks` | 20／1–20 tick | 已有关联的有效性复查；默认约 1 秒 |
| `adoption-scan-ticks` | 100／20–100 tick | 原生服务器伪装的自动识别；默认约 5 秒，在线名单分批处理 |

时间按 20 TPS 换算。增大发现间隔会延迟新观众或原生伪装的接管，不降低既有模型动画、物理和活跃资源分片的推进频率。传送、换世界、解绑及相关可见性变化仍有即时失效处理。

这些频率决定正常发现节奏，`resource-protection` 另控制全服资源额度和负载降级。达到主线程非关键工作预算时，发现、目录及资源推进分散到后续 tick；保护等级升高会进一步减速或暂停新增。安全撤销和有效展示所需的维护不由发现间隔代替，具体阈值与全局 `status` 见 [资源保护](SERVER_RESOURCE_PROTECTION.md)。

## 每类工作如何执行

- 动画输入、动作选择、模型位置与服务端物理每 tick 更新，客户端显示按帧计算。
- 逻辑状态变化向已关联观众与本人广播；同 tick 的模型快照和编码结果复用。新观众依后台发现加入。
- 两端协商 `incremental_state` 后省略未变状态和动作目录；未协商时每 20 tick 发送完整状态。协商 `server_timeline` 后每 2 tick 发送服务器轨迹状态，客户端渲染坐标始终复用原版，不据此重算位置或增加缓冲。
- 服务端图库协商 `server_model_catalog` 后仅主动发送名称目录，不因浏览加载或推送全部资产。全局源缓存每 100 tick 更新，会话按既有发现周期复查；默认频率在 20 TPS 下最坏约 7 秒，每会话每 tick 最多成功发送一片，继续遵守连接与全局字节预算。
- 私人分享缓存当前发布代次的观众；资源传输每块仍检查权限和可见性。上传、磁盘缓存命中校验和写入在有界工作队列中执行；活跃资源、在途上传和校验展开内存仍独立限制。私人服务关闭或无发布时跳过重观众维护。
- 私人确认重试共用客户端每秒 16 包、突发 8 包的控制预算，不降低上传分片的每 tick 推进。publisher 每两秒补发当前参数；相同状态只续租，不向观看者重复广播或重启动作。
- 原生实体通道异步安装，主线程不等待网络线程。预算不足或安装尚未完成时保持服务器显示；完整快照用于拥堵后的状态恢复。

协议身份、心跳、ACK 和限流细节集中在[协议说明](CLIENT_PROTOCOL.md)，不由频率配置改变。

## 开销与容量

服务端 JAR 内原生动画模板的资源读取、JSON 解析和纯表达式编译进入共享有界后台运行时，沿用原有模板来源；准备完成后复用不可变动画／表达式，实例变量、时间线及物理状态独立。仅对确有内置模板的已注册 ME 模型准备；自定义模型没有模板时直接使用 ME 动画，不进入无资源的等待状态。有内置模板但尚未准备完成时返回可重试的 `asset_preparing`，保留原有外观；稍后再次使用即可，服务器不会为了等解析而阻塞主线程。这与客户端原模型下发的准备状态相互独立。

网络压缩／预编码缓存与原生解析模板共同计入 `models.max-asset-cache-bytes`、`max-asset-cache-models`，默认合计 128 MiB／512 条目；原始 JSON 是后台准备的临时输入，计入作业预算，网络缓存不继续持有它。两类缓存仍有自己的子上限。原生模板在当前后端生命周期内不逐出，没有原生内容的轻量记录可逐出；达到共同上限时拒绝新增模板。后端关闭并结束模型会话后归还模板额度，传输仍持有的内容不会因网络缓存移除提前扣账。ModelEngine 实例创建及运行中的动画／物理开销仍在，预算不能中断其单次 API 调用。

尚无千人在线或真实网络负载下的容量实测，刷新间隔不能作为玩家容量、TPS 或帧率保证。

源码入口：[PerformanceSettings](../src/main/java/com/simmc/meplayeractions/config/PerformanceSettings.java)、[ModelAudience](../src/main/java/com/simmc/meplayeractions/me/ModelAudience.java)、[RollingScan](../src/main/java/com/simmc/meplayeractions/action/RollingScan.java)、[ClientSyncService](../src/main/java/com/simmc/meplayeractions/client/ClientSyncService.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java)。
