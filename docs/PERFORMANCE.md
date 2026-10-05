# 性能配置

[文档索引](README.md) · [模型部署](MODEL_DELIVERY.md) · [架构](../ARCHITECTURE.md) · [协议](CLIENT_PROTOCOL.md)

当前两端版本为 0.4.9。后台发现与模型动画使用独立频率；旧服务器配置缺少以下字段时，自动采用默认值，无需覆盖已有 `config.yml`。

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

## 每类工作如何执行

- 动画输入、动作选择、模型位置与服务端物理每 tick 更新，客户端显示按帧计算。
- 逻辑状态变化向已关联观众与本人广播；同 tick 的模型快照和编码结果复用。新观众依后台发现加入。
- 两端协商 `incremental_state` 后省略未变状态和动作目录；未协商的旧客户端仍每 20 tick 接收完整状态。显式服务器轨迹模式保留每 2 tick 的位置同步。
- 私人分享缓存当前发布代次的观众；资源传输每块仍检查权限和可见性。私人服务关闭或无发布时跳过重维护，上传校验在有界工作队列中执行。
- 原生实体通道异步安装，主线程不等待网络线程。预算不足或安装尚未完成时保持服务器显示；完整快照用于拥堵后的状态恢复。

协议身份、心跳、ACK 和限流细节集中在[协议说明](CLIENT_PROTOCOL.md)，不由频率配置改变。

## 开销与验证

服务端按蓝图名称缓存最多 64 个模型模板，复用只读动画和已编译表达式；实例变量、时间线及物理状态各自独立。首次加载或缓存逐出后的加载仍会在主线程读取并解析资源，动画和物理本身的开销也仍存在。

0.4.9 的定向检查、构建及包校验记录见[历史说明](history/CLIENT_0_4_9_PERFORMANCE.md)和同版本交付的 tests ZIP／build JSON。没有千人在线、TPS、帧率或真实网络负载的性能实测，配置间隔不等于服务器容量保证。

源码入口：[PerformanceSettings](../src/main/java/com/simmc/meplayeractions/config/PerformanceSettings.java)、[ModelAudience](../src/main/java/com/simmc/meplayeractions/me/ModelAudience.java)、[RollingScan](../src/main/java/com/simmc/meplayeractions/action/RollingScan.java)、[ClientSyncService](../src/main/java/com/simmc/meplayeractions/client/ClientSyncService.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java)。
