# 服务器资源保护

[文档索引](README.md) · [性能配置](PERFORMANCE.md) · [模型部署](MODEL_DELIVERY.md) · [客户端协议](CLIENT_PROTOCOL.md)

MPA 将模型资源任务、两个同步频道和服务器伪装纳入共享预算。资源忙时模型可能晚加载或暂时不能上传；保护不会修改玩家真实坐标、移动或权限，也不会把私人分享转换为服务器伪装。纯本地模型不使用这些服务器资源。

## 管理员查看状态

以下命令需要 `mact.debug`，玩家与控制台均可使用：

```text
/meplayeractions status
/meplayeractions status network
/meplayeractions status tasks
/meplayeractions status models
/meplayeractions status protection
/meplayeractions status player <在线玩家名>
```

无参数查看全服健康概况；分类页查看网络、后台任务、模型和保护原因；`player` 查看具体连接、绑定和私人分享状态。指标从运行时维护的计数与快照读取，执行命令不扫描模型目录。速率是最近采样窗口的实际流量，峰值和拒绝等累计指标从当前插件运行实例开始，不是持久化历史或容量保证。

## 配置与共享预算

所有新配置位于 `resource-protection`。升级保留已有 `config.yml`，缺失字段自动使用默认值；无效范围在重载替换旧后端前报错。完整键名见 [默认配置](../src/main/resources/config.yml)，这里只列调优常用项。字节额度以实际 UTF-8 Plugin Message 计算，包含 Base64 与 JSON 封装；`MiB` 为 1024² 字节。

| 配置组 | 默认预算 |
| --- | --- |
| `tasks.worker-threads` / `queue-capacity` / `jobs-per-player` | 2 个工作线程，32 个排队作业，每玩家最多 4 个作业 |
| `tasks.max-reserved-bytes` / `per-player-reserved-bytes` | 排队与执行作业预留最多 128 MiB，每玩家 64 MiB；待提交结果另有结果预算 |
| `tasks.pending-completions` / `max-pending-completion-bytes` / `callbacks-per-tick` | 64 个待提交结果、128 MiB 结果额度，每 tick 最多提交 8 个回调 |
| `network.global-upload-bytes-per-second` / `global-download-bytes-per-second` | 两频道合计上传 4 MiB/s、下载 8 MiB/s |
| `network.upload-bytes-per-second` / `download-bytes-per-second` / `asset-download-bytes-per-second` | 单玩家上传 256 KiB/s、全部下载 2 MiB/s，其中资产下载 512 KiB/s |
| `network.global-packets-per-second` / `packets-per-second` | 入站与出站各自全服 4096 包/s、单玩家 48 包/s |
| `network.burst-bytes` / `bytes-per-tick` / `asset-bytes-per-tick` | 8 MiB 令牌桶突发；全服每 tick 全部出站 512 KiB，其中资产 256 KiB |
| `network.global-transfers` / `transfers-per-player` / `waiting-transfers` | 合计 32 个传输槽，单玩家 2 个；最多 128 个等待传输 |
| `network.max-queued-outgoing-bytes` | 延后出站数据合计最多 16 MiB |
| `models.max-disguises` / `max-publications` / `max-relations` / `max-models-per-viewer` | 256 个服务器伪装、256 个私人发布、4096 个观看关系、单观看者最多 64 个模型 |
| `models.new-disguises-per-second` / `disguise-cooldown-ticks` | 全服每秒最多 20 次新建／切换，单玩家间隔至少 20 tick |
| `models.max-asset-cache-bytes` / `max-asset-cache-models` | 网络资产缓存与原生解析模板合计最多 128 MiB、512 个内容／模板条目 |
| `disk.max-temporary-bytes` / `min-free-bytes` | 临时文件预算 256 MiB，磁盘可用空间最低 512 MiB |

原有 `client-sync.private-models` 仍决定分享是否启用、单包大小、私人资源内存和持久缓存额度；必须同时满足共享与子模块预算。两个资产下发路径另共用全局下载速率的一半（默认 4 MiB/s），再受单玩家、每 tick 与保护倍率限制。资产包数最多占出站总包数的 75%（余量向上取整），默认为每玩家 36 包/s、全服 3072 包/s，为 ACK、撤权和清理消息保留余量。突发额度不会越过每 tick 上限。缓存存在、目录显示“已上传”或资源 hash 相同都不能替代当次所有者、权限、代次与观看授权检查。

预留字节是作业可能持有资源的保守估算，包含展开及解析空间，并非只有 ZIP 大小。私人验证最多按原包加 40 MiB 预留，目录校验按 48 MiB、删除按 4 MiB；同时受共享任务额度与原有私人内存额度限制。下调任务内存时应保留至少一份所需作业的空间，否则会明确拒绝而不是无界等待。

网络压缩／预编码内容与原生动画解析模板共用缓存账本，两类名称空间的条目相加，仍各有子上限；原始 JSON 属于后台准备的临时输入，由作业预算覆盖。JAR 原生模板后台准备并在后端生命周期内保留，没有原生内容的轻量记录可逐出；首次使用未完成时提示 `asset_preparing`，原外观保留，可稍后重试。模板作业保守预留 96 MiB、单模板估算最多 48 MiB；默认 128 MiB 工作额度可容纳一份模板准备，下调时也需考虑这一路。共同额度不足时不无限缓存；后端关闭且模型会话结束后才归还模板额度，传输仍持有的网络内容保持记账，关闭运行时不会直接把仍存活的资源算作已释放。

## 负载保护

`resource-protection.enabled` 默认 `true`。保护同时观察服务器 TPS／MSPT 与 MPA 本身主线程工作预算；即使负载来自其他插件，也会减少非核心模型工作。

| 等级 | 默认触发值 | 行为 |
| --- | --- | --- |
| `NORMAL` | 未达到以下阈值 | 按配置预算运行 |
| `THROTTLED` | TPS ≤ 18 或 MSPT ≥ 55 ms | 非关键项数与资产出口额度减半，后台同时执行量减少，延后发现及资源准备 |
| `PROTECTED` | TPS ≤ 15 或 MSPT ≥ 75 ms | 拒绝新的上传、下载和服务器伪装；保留仍健康的已有展示 |
| `EMERGENCY` | TPS ≤ 10 或 MSPT ≥ 150 ms | 停止新增资源工作，取消排队的非关键作业，分批清理本插件拥有的实例 |

阈值位于 `resource-protection.protection`。恢复要求 TPS ≥ 19、MSPT ≤ 45 ms，连续健康 `recovery-ticks: 600` 折算的 30 秒，逐级恢复；该等待使用单调时钟，期间再次超限重新计时。`main-budget-millis` 默认 3 ms、`main-work-per-tick` 默认 64 项，限制被纳入预算的非关键工作；它们不能强行中断已经进入的 Bukkit／ModelEngine 调用。动画与安全清理各自维护必要时序，不能把预算理解为整个插件任意代码的硬执行时间上限。

已计量的 MPA 主线程工作均值达到主预算 1 倍、3 倍、6 倍时，也分别进入减速、保护、紧急等级；默认对应 3／9／18 ms。该均值只覆盖接入计量的插件工作段，不等于全进程采样，仍可在服务器 TPS 看似正常时提前减少本插件负载。

`models.emergency-removals-per-tick` 默认 2，服务器伪装与私人中继分别按该额度分批结束本插件拥有的展示／发布；设为 0 保留现有实例，仍拒绝新增负载。清理不会销毁外来模型。解绑、撤权、断线、必要确认及租约维护继续执行，避免保护状态造成永久隐藏或泄漏。

ME 拒绝移除时，自有实例进入 `serverCleanupPending`，暂不重新绑定客户端，也不提前归还实例名额；后续按验证间隔和每 tick 数量界限重试。即使关闭紧急移除，必要清理仍可每 tick 尝试最多 1 项；这类等待可在 `status models` 查看，不能把移除请求发出当成已经销毁。

## 超时、取消与错误

默认后台排队／无进展各 30 秒，单作业总时限 120 秒；`tasks` 配置以 tick 单位乘以 50 ms 后使用单调时钟计算，低 TPS 不延长作业预留。传输等待 600 tick、无进展 300 tick、总时限 2400 tick、等待 ready 300 tick，位于 `network`；资源收到、排队、校验、GPU 准备和显示授权仍是独立阶段。按服务器 tick 推进的网络时限在低 TPS 时可能延长实际秒数，不能用于毫秒级网络 SLA。

取消会使结果身份失效、终止未开始的任务，并让运行工作协作退出；迟到结果不能重新发布模型。仍被工作线程持有的 byte[]、展开资源和文件流，不会因为 Future 超时就提前归还内存预算。超出队列、内存或保护额度明确失败，不把重工作转回主线程执行。

取消后的待提交成功或原错误会转换为一次取消清理回调，先释放对应服务账目，再按原会话／代次核对是否可回应；它不能向重连后的新会话提交旧结果，也不能简单跳过回调而泄漏上传或删除名额。

资源错误提供 `code`、`stage`、`retryable`、`retryAfter`（秒），能关联时携带请求或传输身份。忙／限速／保护类可重试，格式无效、复杂度超限和失去权限等应先处理原因；客户端不会对不可重试资源自动循环重传。撤销显示仍以精确绑定为准，错误提示本身不授予使用权。异常日志按事件聚合与限频，详细预算和传输状态从 `status` 查看。

## 能力边界与调优

模型复杂度、JSON／归档展开、纹理像素与表达式执行同时受限，具体默认值见配置与 [协议](CLIENT_PROTOCOL.md)。受管实例的变量、动画时间线、物理状态和权限独立；可复用的资源与预编码数据只共享不可变内容。

`complexity` 默认最多 4096 个骨骼、1024 个动画、65536 个关键帧、32768 条表达式、2097152 个表达式字符、200000 个 JSON 节点、256 个归档条目、8 MiB 展开数据、16777216 个纹理像素；这些可配置值只能收紧对应硬上限。模型服务器验证不执行私人脚本，观看客户端仍独立解析并检查。共享 Molang 解释器另有既有执行步数预算，不因扩大文件字节额度而取消。

传输继续使用游戏 Plugin Message，没有启用 HTTP／HTTPS 资产服务。Paper 公共 API 没有通用的连接可写性查询；可用的后端通道探针用于背压，不具备探针时仍执行字节、队列、进展与总时限限制，不能宣称已经测得或控制底层所有网络缓存。MPA 原生模板的读取、JSON 解析及纯编译已进入后台，ModelEngine 自身蓝图 API 和实例创建仍受其线程契约约束，单次昂贵调用无法在执行中抢占。

先用 `status` 判断触顶的是带宽、作业、内存还是实例／关系数，再调整对应预算。发现与目录更新可接受秒级延后，实际坐标继续使用原版同步；不要为了增加展示人数无差别放大所有额度。目前没有千人负载、TPS、网络或实机容量测试，默认值是保护策略，不是在线人数保证。
