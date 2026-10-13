# 资源生命周期与性能风险

[文档索引](README.md) · [性能配置](PERFORMANCE.md) · [服务器资源保护](SERVER_RESOURCE_PROTECTION.md) · [客户端配置](CLIENT_CONFIG.md)

本文对应当前本地 0.6.3 源码，覆盖服务端、客户端、共享表达式解释器、ME／GSit 接入、缓存、网络与 GUI 生命周期。静态检查能确认调用线程、循环、容量和释放路径；具体阻塞时长、堆保留量、TPS 和帧率仍需运行采样或实机验证。本轮测试、实际命令及输入身份保存到独立 0.6.3 验证目录，旧版本结果不作为当前验收。

六项已确认风险已按下表优化。客户端失败 hash 元数据加入总容量与过期清理；同步偏好写盘、纹理解码和预览 atlas 拼接进入有界后台任务；源缓存刷新不再等待完整加载。不能据此宣称不存在其他泄漏或主线程尖峰，尤其单次 GPU 上传和第三方实例装配仍不可抢占。

纹理准备 worker 会在 128 MiB 像素额度不足时等待，等待期间释放调度监视器，由客户端提交或取消唤醒；客户端主线程不等待 worker。GUI 预留覆盖源图像与最大允许 atlas 的峰值，可能减少同时准备的数量。该额度不包含压缩 PNG 副本、模型 JVM 对象或已提交 GPU，因此不是客户端总内存上限。偏好首次读取仍在插件启动阶段同步执行，普通修改与后端重载不等待磁盘；停服的 250 ms 等待是明确接受的例外。

## 本轮处理的六项问题

| 编号 | 原触发 | 0.6.3 处理与保留规则 | 仍有的边界 |
| --- | --- | --- | --- |
| S1 | 修改同步偏好时重复保存整个 `players.yml` | [ActionController](../src/main/java/com/simmc/meplayeractions/action/ActionController.java) 内存立即应用；相同值跳过，首次变更约 1 秒后合并提交。后台独立 YAML、临时文件替换和失败重试；协调器跨后端重载保留最新变更，同一路径等待实际 worker 退出后再开始新写 | 偏好文件越大，后台序列化和磁盘成本越大。停服仅最多等 250 ms，超时允许最后一批未保存；底层 I/O 可能继续存在 |
| C2 | 完整私人源 bundle 未命中时持库锁加载，UI 刷新等待该锁 | [LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java) 的读取、导入、解析、编码和数组复制移出缓存锁；清缓存提升世代，旧成功／错误结果不能回填。源和错误缓存各保留 16 项上限 | 重加载本身仍需时间；刷新过程中返回的过时代次会失败并按已有流程重试，不把旧身份作为新发布源 |
| C1 | 世界模型、组件、资源重载和 GUI 首次预览同步处理纹理像素 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) 与 [ModelPreview](../client/src/main/java/com/simmc/meplayeractions/client/ui/ModelPreview.java) 共用有界准备调度。后台完成几何快检、PNG、透明度与 atlas；客户端线程分批安装 GPU，完整完成后才确认 ready。队列、工作像素与待提交 GPU 预留计账，取消／重载释放迟到资源 | 默认 1 worker、8 个未完成请求、128 MiB 工作像素预留；每客户端 tick 最多一张纹理、约 1 ms 软门槛。单张上传不能截断，约 5 秒首次准备是正常负载目标，不含下载／解析 |
| C3 | 不同失败 hash 时间戳长期累积 | [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) 失败记录最多 256 条、TTL 60 秒，每秒清理；优先裁剪不再关联当前模型的记录，保留 30 秒重试冷却，成功安装清除对应失败记录 | 这是小型元数据边界，不能据此推断完整模型或原生资源已做长期泄漏测量 |
| S2 | 每 8 个发现候选后重复遍历全部绑定生成资产授权集合 | [ClientSyncService](../src/main/java/com/simmc/meplayeractions/client/ClientSyncService.java) 按观察者合并整次发现泵送和绑定校验批次的集合重算；批次结束／资源推进前刷新，独立撤权立即处理 | ready、资源请求／发送和租约继续实时检查；128 个候选额度仍不包含这些必要检查 |
| S3 | 私人观众先排序所有合格跟踪者再截取人数 | [PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) 使用最近 K 人堆，只排序最终入选者；保留距离、UUID 平局顺序、权限与全局关系准入顺序 | 排序变为 `O(N log K)`，仍需 `O(N)` 遍历跟踪者完成可见性检查；不降低发现、动作或物理频率 |

## 规模敏感的同步工作

**ME 实例和动态动画装配仍在主线程。** [ModelEngineBridge](../src/main/java/com/simmc/meplayeractions/me/ModelEngineBridge.java) 调用 ME 创建、添加、销毁实例；[YsmAnimations](../src/main/java/com/simmc/meplayeractions/me/YsmAnimations.java) 的接收预备模板构造器仍逐动画、骨骼和关键帧创建 ME 的 `BlueprintAnimation`、`Timeline` 与关键帧对象。后台模板准备已移走资源读取、JSON 解析和纯表达式编译；每实例的 ME 对象装配没有因此变成后台或分批操作。大模型及多次创建会放大分配和调用成本。后续可增加创建复杂度准入、分段装配和阶段耗时指标，保持未完成实例不可见、失败可回滚和实例状态隔离。

**逐 tick 动作、表达式与物理没有全服硬耗时上限。** [ActionController](../src/main/java/com/simmc/meplayeractions/action/ActionController.java) 每 tick 遍历活动会话，继续更新玩法、姿态与表达式；[Molang](../src/main/java/com/simmc/meplayeractions/expression/Molang.java) 有求值步数、解析深度、变量和诊断数量限制，[YsmRuntime](../src/main/java/com/simmc/meplayeractions/expression/YsmRuntime.java) 与客户端物理也有步数／时间补偿边界。这些限制防止某些无限执行，但实例数、骨骼、控制器和调用次数仍会叠加。`recordWork()` 是记录已执行耗时，不能中断正在运行的循环或第三方调用；ME 状态机访问还有共享监视器。应先采样和收紧准入，调整动画／物理频率会改变体验，需要单独决策。

**观众发现的上游工作仍然存在。** [ModelAudience](../src/main/java/com/simmc/meplayeractions/me/ModelAudience.java) 重选时读取原生跟踪候选，再经过最近观众堆与全局关系准入。新反向索引消除了同步服务对受管拥有者目录的反复全量发现，没有消除 ME 自身观众选择。外部 `/meg` 模型缺少受管观众集合，仍通过有界回退扫描发现，总候选量可能随外部拥有者数与观察者数的乘积增加。

**服务器时间线模式可能超出默认网络供给。** 客户端 `followServerTimeline` 默认关闭；协商开启后，[ClientSyncService](../src/main/java/com/simmc/meplayeractions/client/ClientSyncService.java) 按每 2 tick 向已有关系尝试发送状态。20 TPS 下，R 条关系的理论状态发送需求约为 `10 × R` 包／秒，还未计心跳和控制消息；默认全服出站预算为 4096 包／秒。预算会延后发送并合并待发状态，不等于无限积压，但大量观察者同时启用时仍会产生遍历、编码／队列维护开销和同步延迟。提高预算会增加带宽与 CPU 成本，需要按实际负载权衡。

**客户端仍有少量同步文件操作。** [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) 的 `localModels()` 从 UI／命令补全路径调用本地目录扫描，扫描有深度和数量上限；[ClientOptions](../client/src/main/java/com/simmc/meplayeractions/client/ClientOptions.java) 的设置保存使用主线程同步临时文件写入与原子替换，变化中的 roaming 配置由客户端 tick 每秒尝试持久化。配置文件有 64 KiB 上限，仍不能约束慢磁盘操作耗时。可以复用异步目录快照与合并写入，但需要保持设置失败回滚、旧结果失效和退出保存语义。

## 已存在的容量与释放机制

| 资源 | 当前保护与清理 | 检查结论的边界 |
| --- | --- | --- |
| 服务端后台资源作业 | [ResourceProtection](../src/main/java/com/simmc/meplayeractions/protection/ResourceProtection.java) 使用固定线程池、有界队列、拒绝策略、结果回调和字节预留；取消运行作业后直到工作线程退出才释放工作预留，普通关闭不等待线程退出；偏好停服收尾另有合计 250 ms 等待上限 | 卡在不响应中断的底层 I/O 时，工作线程和预留可能继续存在；不能把超时理解为强制终止或立即释放内存 |
| 服务端资产与模板 | [ModelAssets](../src/main/java/com/simmc/meplayeractions/client/ModelAssets.java) 有数量／字节、内容引用和传输 pin；[ServerTemplatePreparation](../src/main/java/com/simmc/meplayeractions/me/ServerTemplatePreparation.java) 接入共享缓存账本，后端关闭归还模板额度 | 已编译模板在后端生命周期保留是设计，不是每次解除伪装都释放；字节预算是估算，不是 JVM 全堆占用 |
| 服务端私人磁盘缓存 | [PrivateModelStore](../src/main/java/com/simmc/meplayeractions/client/PrivateModelStore.java) 限制字节、总条目、每 owner 条目和单轮检查量，后台执行文件操作；主线程目录／状态读不可变 volatile 快照 | 存储方法虽然持锁，主线程状态查询不获取该磁盘工作锁；没有据此确认 status 阻塞于后台磁盘 |
| 同步关系、额度与延后消息 | [ClientSyncService](../src/main/java/com/simmc/meplayeractions/client/ClientSyncService.java)、[PrivateModelSyncService](../src/main/java/com/simmc/meplayeractions/client/PrivateModelSyncService.java) 在解绑、退出、撤权和关闭时清理；传输结束释放 pin 与额度；控制队列限制条目与保留时间，待发状态按 owner 合并 | 新发现队列每会话至多一个扫描，退出移除；这些边界不意味着安全复查和状态更新也受 128 个发现候选限制 |
| Netty 接入 | [NativeHandlerInstallation](../src/main/java/com/simmc/meplayeractions/me/NativeHandlerInstallation.java) 调度到网络线程安装，再回主线程核对身份；撤销清理请求与 handler，不等待网络线程完成 | 当前接入路径没有同步等待网络 Future；第三方 ME 动画锁和 API 调用耗时仍需采样 |
| 客户端模型与预览 | [ClientRuntime](../client/src/main/java/com/simmc/meplayeractions/client/ClientRuntime.java) 的活动资产、local profile 和缓存预览分别有数量上限；[LocalModelLibrary](../client/src/main/java/com/simmc/meplayeractions/client/LocalModelLibrary.java) 的完整源 bundle 缓存最多 16 项；GUI 切页裁剪预览，关闭停止页面工作线程 | 多个独立缓存及后台任务没有统一的客户端堆字节账本；单模型／条目数量上限不能直接换算为进程内存上限 |
| GPU、动画与音频 | [ModelRenderer](../client/src/main/java/com/simmc/meplayeractions/client/render/ModelRenderer.java) 限制纹理像素并在释放、断线、重载时销毁纹理和动画播放器；GUI 预览也有自己的容量与释放；[YsmAudioRegistry](../client/src/main/java/com/simmc/meplayeractions/client/effects/YsmAudioRegistry.java) 以租约管理 OGG 资源，声音／粒子另有活动数量与速率限制 | 正常释放路径存在；异常路径、原生分配及长期堆保留需要实机与 heap／native-memory 采样，不应仅凭静态字段判断泄漏 |
| GSit 与备用菜单 | [GameplayBackend](../src/main/java/com/simmc/meplayeractions/gameplay/GameplayBackend.java) 保留确切姿态／飞行所有权，停用时注销监听；[PoseReplicaVisibility](../src/main/java/com/simmc/meplayeractions/gameplay/PoseReplicaVisibility.java) 恢复显示缓存；[ActionDialog](../src/main/java/com/simmc/meplayeractions/ui/ActionDialog.java) 每玩家替换会话，退出／关闭清理 | 清理失败可保留身份供后续重试，不能将“发出清理请求”当成实体已经销毁；过期菜单身份不代表无限增加的会话数量 |

## 后续验证与取舍

本轮六项优化保留即时内存状态、世代校验、实时撤权、最近观众排序与人数上限；后台写入失败保留待保存变更，GPU 安装失败回滚部分资源。单元与组件模拟验证规则和账目，实机验收由用户完成，不能把测试通过表述为游戏性能或长期内存实测。

需要改变创建流程、动画／物理频率、服务器时间线频率或客户端总内存准入时，再单独确定体验代价。运行验证应覆盖反复伪装／解绑／重连、模型源刷新、加载失败、资源重载与长时间漫游，并同时观察服务器主线程调用栈、客户端主线程／渲染帧耗时、队列与预留、Java 堆和原生纹理资源。

约 5 秒发现时间是默认节奏和正常负载下的设计目标；发现队列、上游观众选择、状态／控制包预算、外部模型回退、保护等级及资源准备都可能增加等待。静态源码和组件模拟不能证明该时间在所有实机负载下成立。
