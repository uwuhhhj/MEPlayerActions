# 客户端模型协议 v3

本文对应 0.4.2 实现。安装和专项说明见 [项目 README](../../../../../../../README.md)、[文档索引](../../../../../../../docs/README.md)、[客户端配置](../../../../../../../docs/CLIENT_CONFIG.md) 和 [模型同步与部署](../../../../../../../docs/MODEL_DELIVERY.md)；运行原理见 [架构说明](../../../../../../../ARCHITECTURE.md)。[旧资源包格式](../../../../../../../docs/CLIENT_RESOURCE_PACK.md) 仅用于对应兼容模式。

频道 `meplayeractions:main`，严格 UTF-8 JSON，每包带整数 `protocol:3`、字符串 `type`。不兼容 v1/v2。所有消息保持分包顺序；不允许重复键、类型强制转换、非法 UTF-8、尾随内容；当前服务端还拒绝客户端请求中的额外字段。每包受 maxPayload（默认 16000 字节）限制；连接流量和下载预算见下文。客户端请求只操作自己，仍经过服务端权限校验。

## 引擎与本地外观边界

v3 是玩家模型资产、动画状态及观众渲染接管协议。客户端只依赖 Fabric Loader、Minecraft、Java 与 Fabric API，协议字段不要求 ModelEngine 对象或引擎标识。本仓库的服务端实现使用 ModelEngine；文中涉及 ME、Paper、GSit、插件目录和命令权限的行为说明该适配器的实现。

其他引擎要使用现有客户端，应实现同频道、版本、字段范围及以下语义：主动推送当次授权的完整 `.bbmodel` 与对应 hash（兼容旧客户端时另提供下述资源包或 legacy 路径）；提供已解析的动画层和 `motion` 状态映射；为观众保留可追踪的原版玩家实体；匹配 ready/ack 后才切换该观众的后端显示；失败、解绑和租约超时恢复正常显示。服务器的姿态后端可将已支持的 sit/sleep/crawl 与接触面偏移映射到 `specialPose` 和 anchor 字段，无需安装 GSit 才能发送这些字段。实现兼容适配器后，更换服务器引擎本身不要求更新客户端；这不表示客户端会自动识别未知引擎、新状态枚举或不支持的模型格式。

0.4.2 新建客户端配置默认 `enabled=true`、`showSelf=true`、`followServerTimeline=false`：客户端渲染和本人显示开启，按原版实体即时跟随。进入兼容服务器后自动握手、加载授权模型和发起 ready；不需要启用私人外观或手动选择服务器模型。已有配置中的用户开关继续生效。服务端的观众与本人可见性许可仍是上限，客户端 `showSelf` 只控制本机绘制。

私人外观默认 `localAppearance.enabled=false`，默认模型 `openysm_default` 只是可选项，不会自动套用。私人模型不建立自己的服务器绑定、不发送本地动作请求，也不为这份本地模型申请 ready/heartbeat；已有服务器绑定的 ready 和租约仍独立维护。没有服务器插件时也可显示，但其他玩家看不到这份本地选择。服务器多人伪装仍按下述授权、资产和租约流程同步。

有服务器动作频道时，私人外观先等待握手和完整快照；已知服务器本人伪装存在时，还需其绑定完成 ready/ack 才覆盖本机的自己。服务器原模型尚未就绪、版本不匹配、租约丢失或资源重载期间，它不能叠在 ME 回退显示上。完整快照确认没有服务器本人伪装，或匹配的解除原因已清除该伪装后，才可恢复独立私人显示。这一约束不改变默认服务器自动接管。

## 握手与状态

客户端发送：
```json
{"protocol":3,"type":"hello","clientVersion":"0.4.2","capabilities":["local_render","server_push_models"]}
```
`capabilities` 必须含 `local_render`，还可声明 `server_push_models` 或旧 `resource_pack_models`；不允许重复，其他 capability 拒绝。`clientVersion` 可省略（最多 64 字符）。hello 两次接受之间至少相隔一个单调时钟秒；这一时间戳属于玩家连接，未知协议结束会话或重新 hello 都不能重置。新 hello 清理旧绑定并恢复旧 ME 可见性，不授予渲染权限。

返回 `hello_ack`：`mode:"local-render"`、`serverTick`、`heartbeatTicks:20`、`leaseTicks:100`、`maxPayload`、`requestCooldownTicks`。服务端优先选择 `server_push_models`，得到 `assetMode:"server-push"` 和 `capabilities:["local_render","server_push_models"]`；否则声明 `resource_pack_models` 的旧连接得到 `assetMode:"resource-pack"` 和对应能力；仅声明 `local_render` 的旧客户端得到 `assetMode:"legacy-download"`。0.4.2 客户端要求 ACK 同时确认 `server-push`、`local_render` 和 `server_push_models`；旧服务器不支持时保持后端显示并提示升级，不自动切换旧模式。模式在本次会话内固定，客户端不能在请求中传入 `assetMode` 改写模式。随后发送 `snapshot_begin {snapshotId,serverTick}`、可见状态、`snapshot_end {snapshotId}`；手动 `snapshot_request` 也会生成此快照边界。后续每 2 tick 发送可见实例的完整 state，动作切换可即时发送。相同 sequence 的 transform 仍可能变化，客户端不能按 sequence 丢弃同序号位置包。

```json
{"protocol":3,"type":"state","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","modelId":"ysm_01_jk","assetHash":"小写SHA256","sequence":12,"serverTick":12345,"world":"00000000-0000-0000-0000-000000000003","x":1.0,"y":64.0,"z":2.0,"bodyYaw":90.0,"headYaw":100.0,"headPitch":10.0,"scale":1.5,"hidePlayer":true,"showSelf":true,"foodLevel":20,"layers":[{"layer":"posture","animation":"crawl_idle","startedAtTick":12340,"speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"animations":[{"id":"wave","label":"挥手"}],"motion":{"features":["movement","crawl"],"clips":[{"state":"crawl-idle","animation":"crawl_idle","speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"jumpMinTicks":17,"landingGraceTicks":6,"movementThreshold":0.025,"interruptMove":true,"interruptPosture":true,"flying":false,"interaction":"","forcedPose":"","specialPose":"","anchorX":0,"anchorY":0,"anchorZ":0,"anchorYaw":0}}
```

`instance` 是伪装生命周期 UUID，模型更换生成新值。UUID 必须是标准 36 字符小写形式。`sequence` 是动画变化序号；`serverTick` 是名义 20 TPS、unsigned 32 位的服务器 tick，当前适配器取 Paper 当前 tick。x/y/z 与 body/head yaw/pitch 为服务器展示轨迹，当前适配器保留 visual-follow.delay 历史帧，只在客户端显式开启 `followServerTimeline` 时使用。默认模式根据 owner UUID 查找观看者的原版 PlayerEntity，逐帧使用 getLerpedPos 与原版角度插值，既适用于本人，也适用于未安装模组的被观看者；不叠加服务器 delay 或客户端 interpolationTicks。未跟踪到原版实体时不绘制幽灵模型。动画使用客户端本地 tick 时钟，普通姿态在每个游戏 tick 采样；服务器手动层的开始时刻只转换一次，同实例回包不重启动画。

state 必须额外包含 `motion` 对象：`features` 为服务器及玩家允许的同步项，`clips` 为已解析且模型存在的 `{state,animation,speed,loop,inTicks,outTicks}` 集合；缺失映射不硬编码回退。另有 `jumpMinTicks`、`landingGraceTicks`、`movementThreshold`、`interruptMove`、`interruptPosture`、`flying`（远端能力不一定由原版同步）、`interaction`（空/mining/swing-mainhand/swing-offhand）。`forcedPose` 为空/crawl/sneak，仅传递 Paper 固定姿态或 GSit 强制爬行；本机原版重新计算为 STANDING 时仍必须遵守，不拿延迟的自动层猜测姿态。原生陆地爬行与游泳、床睡眠与 GSit 躺下分别判断；禁用姿态不落入其他状态。本人挖掘读取本地交互管理器，远端挖掘采用服务器的明确 interaction 状态，不能根据同名攻击动画猜测。

`motion.specialPose` 为空或 sit/sleep/crawl；`anchorX/Y/Z` 是**当前**姿态接触面相对真实玩家脚底的偏移，`anchorYaw` 为姿态朝向。当前 GSit 适配器取公共 Seat.location + SitService.baseOffset 与 Seat.yaw。这些字段不经过 visual-follow 历史，特殊姿态优先于其隐藏坐骑，睡姿头向保持 bodyYaw、headPitch=0；原生床则从客户端真实 Bed 两半读取几何中心、Y+0.5625 和朝向。不能把 GSit 假床包当作真实床，也不能使用下移的 rawPlayer Y 直接绘制。yaw/pitch 以度表示，scale 是正数均匀缩放；卧倒 Root 由模型动画负责，禁止再施加原生 SWIMMING 或睡姿整模型旋转。

`layers` 是完整集合：缺席的层应退出；名称 posture / interaction / manual，优先级依次 100 / 150 / 200。posture/manual 覆盖有关键帧的骨骼，interaction 叠加；in/outTicks 是 20 TPS 的过渡时长。loop 为 ONCE / LOOP / HOLD，startedAtTick 用于从服务器时间同步动画进度。animations 是可发送 play 请求的动作中文菜单目录：有模型目标动画的 custom-actions 使用动作别名 ID 和 label；仅 manual.allow-raw-animation=true 时补充符合服务器命令 ID（1–64 位小写英文、数字、_、-）的原始动画。同 ID 的自定义动作优先，目标缺失的别名也不会回退为不可执行的原始动作，目录去重并按 ID 排序。label 去除 ISOControl 后最多 64 个 Unicode code point。若 state 超出 maxPayload，先去掉目录并发送 animations:[]；位置与动作层仍超限时解绑并保持 ME，日志按观众/owner 去重。hidePlayer 控制真实人物渲染；showSelf 控制本人可见性。服务器尊重同世界、Player.canSee、disguise.view-distance 的严格三维距离限制、max-viewers 最近观众上限和 show-self，客户端不能据状态突破可见性。

非空 `assetHash` 表示服务器允许客户端准备这份实例的资产，空值表示保持后端显示，不允许 render_ready。当前 ModelEngine 适配器只有本插件创建、实体没有外来模型且有可分发资产的实例提供非空值；接管原生 ME、外来模型共存或资产失败时 hash 为空。state 还提供 `assetStatus`（pending/ready/missing/invalid，或不允许接管时的 server-only）、`assetReason` 和 `assetSource` 诊断；它们不授予渲染权限，原因不包含服务器私有文件路径。

`state.accessories` 是原模型持久附件状态，仅可包含有限的 `a`、`b` 数字，范围 0–1，对应 `variable.roaming.a/b`。服务器按当前伪装实例同步，模型切换或解除后重置。客户端在时间脚本执行后、几何采样前应用它，避免新观众或离开可视范围后回来时重放动作造成重复切换。物理变量仍由各观看者按本地实体运动计算。该字段缺省为空对象，本地预览自行执行附件脚本。

## 服务器主动推送与资产身份

所有模式保持相同的 `state.modelId`、`state.assetHash` 和 ready/ack/heartbeat 语义。hash 是**原始完整 .bbmodel JSON 字节** SHA-256 小写 64 位十六进制，不是 ZIP、PNG 或 GZIP hash。协议模型 ID 为 1–64 位小写英文、数字、`_`、`-`。

当前服务器查找完整资产的顺序为 `plugins/MEPlayerActions/models/<id>.bbmodel` → 服务端 JAR 的 `models/<id>.bbmodel` → ME `blueprints/` 内匹配文件名，再匹配 `model_identifier`。0.4.2 服务端 JAR 提供两套内置完整原模型；安装 ZIP 的 raw 副本仅放 `examples/models/` 供参考，不自动放入优先级最高的覆盖目录。ME 数值蓝图不能恢复已经烘焙掉的表达式和物理，其他模型缺失完整源时需显式 raw 覆盖。

主动推送会话不接受 `asset_request`，返回 `asset_server_push_mode`。客户端观察到 hash 不等于获准接收任意资产；服务器先根据当前可见绑定发 offer：

```json
{"protocol":3,"type":"asset_offer","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","modelId":"ysm_01_jk","hash":"0000000000000000000000000000000000000000000000000000000000000000","offerId":"00000000-0000-0000-0000-000000000003"}
```

示例 UUID/hash 仅表示字段格式。`offerId` 是服务器生成的 UUID token，绑定本次连接、初始 owner/instance/modelId/hash；offer 不包含 size 或 expiry 字段，时限由两端本地状态机管理。客户端初次接收时必须已有完全匹配的绑定，不能根据 unsolicited bytes 建立下载。重复 token 不重置时限或阶段。

客户端只对当前 offer 反馈缓存状态，不携带 modelId、URL 或路径：

```json
{"protocol":3,"type":"asset_status","offerId":"00000000-0000-0000-0000-000000000003","hash":"0000000000000000000000000000000000000000000000000000000000000000","status":"missing"}
```

`status` 只允许 `cached`、`missing`、`rejected`。`cached` 表示客户端已校验资产、解析并准备 GPU，不授予渲染租约；仍需另发 `render_ready` 并收到精确 ACK。`missing` 将已签发的 offer 转入服务器受限传输队列，客户端不能借反馈选择其他模型。`rejected` 取消该 offer。旧或重复 token 的反馈静默忽略，无法重新开始已完成的传输。

服务器只为有效 missing offer 发送：

- `asset_begin {offerId,hash,modelId,rawBytes,compressedBytes,chunks}`。
- `asset_chunk {offerId,hash,index,data}`：index 从 0 连续，data 为该片 GZIP 字节的标准 Base64。
- `asset_end {offerId,hash}`。
- `asset_cancel {offerId,hash,reason}`：授权失效、超时、拒绝或发送失败时取消。

上述消息还包含 `protocol:3,type`。客户端只接收已反馈 missing 的对应 token，检查 modelId、声明长度、分片连续性、压缩及解压上限和完整 SHA-256；end 后再解析完整模型、准备纹理。原始模型包含骨架、动画、表达式及内嵌 PNG，原始上限 8 MiB、压缩上限 4 MiB。仅收到文件、end 或反馈缓存命中都不能提前切换 ME 显示。

资产按 hash 共享：offer 最初精确匹配一份绑定，在途期间同 hash 仍须至少被一份当前可见、授权绑定需要；无人再需要时取消。可见实例切换不允许客户端把旧 token 用于不同 hash，渲染就绪和 ACK 始终匹配各自 owner/instance/hash。选择同 hash 的其他模型别名不会重置服务器重试额度。

每片最多 9000 GZIP 字节，小 maxPayload 进一步缩小（1024 字节负载时为 384 字节）；客户端分片上限 16384。每观众每 tick 最多 2 个 chunk，每连接最多 2 个排队或进行中的传输，服务端合计最多 32 个；主动推送与旧下载共享传输及带宽预算。

| 主动推送限制 | 当前值 |
| --- | --- |
| 服务器未完成 offer / offer 记录 | 每连接 2 / 64 |
| offer 发送间隔 / 同 hash 重试冷却 | 10 tick / 100 tick，跨新 hello 保留 |
| 同 hash 当前授权实例集合的重试额度 | 最多 3 次；仍有相同实例时，换别名或初始 owner 不能绕过 |
| 等待缓存反馈 | 100 tick（名义 5 秒） |
| missing 排队与传输空闲 | 300 tick（名义 15 秒），成功 begin/chunk 刷新进度 |
| 未完成 offer 总时限 | 自签发起 1200 tick（名义 60 秒） |
| cached/delivered 等待 ready | 200 tick（名义 10 秒） |
| 客户端活动 offer / 空闲 / 总时限 | 4 / 15 秒 / 60 秒 |

重试计数以同 hash 当前授权服务器实例集合的交集维护；全部实例实际更换、实际断开或成功 render_ack 才可重置对应失败计数，新 hello 不重置。已有相同 hash 的有效渲染租约不重复签发 offer。

客户端 `config/meplayeractions/cache/<hash>.bbmodel` 缓存完整原始 JSON，读取时重新检查文件、8 MiB 和 SHA；模型解析由加载流程完成。缓存总量 128 MiB、最多 512 个有效条目，按最近使用时间裁剪，离服保留，与私人 `config/meplayeractions/models/` 分开。缓存命中仍需当前 offer、绑定和 ready/ACK；文件存在不授予使用权。存储不可用时不信任缓存，也不允许绕过资产校验。

## 旧客户端兼容模式

0.4.1 客户端声明 `resource_pack_models`，服务器返回 `assetMode:"resource-pack"`；该会话的 `asset_request` 返回 `asset_resource_pack_mode`，不排队或发送文件。模型来自 ResourceManager 的 `assets/meplayeractions/models/index.json`、元数据和 PNG，按原始 hash 恢复校验。完整资源包缺失时保持 ME；格式与离线工具见 [旧资源包说明](../../../../../../../docs/CLIENT_RESOURCE_PACK.md)。这不是 0.4.2 默认部署要求。

仅声明 `local_render` 的旧 0.4.0 客户端保留 `legacy-download`：发送 `asset_request {modelId,hash}`，两者须匹配当前可见绑定。返回不带 offerId 的旧 `asset_begin/chunk/end`，仍受原始/GZIP 上限、并发、连接及全局预算、100 tick hash 冷却限制。每次分片检查授权，无人再需要时取消，失败保持 ME。

新客户端不会在主动推送协商失败后自动发送 legacy 请求或切入资源包模式。同为 v3 不表示所有 capability 扩展互通；部署时建议插件和模组一同升级到 0.4.2。

## 连接预算

服务端在主线程按玩家连接保存预算；普通会话结束、未知协议、新 hello、同步配置禁用/恢复都不会重置流量、hello 时间、动作冷却或 hash 冷却。只有明确 quit 或实际离线清理才移除该连接预算。插件实例销毁和服务器重启不属于持久化限流。

字节数按本频道实际 UTF-8 JSON 计算，包含 Base64、字段名和消息封装；秒窗口使用单调时钟的一秒固定窗口，tick 使用服务器 unsigned 32 位 tick。当前硬上限为：

| 预算 | 单连接 | 服务端合计 |
| --- | --- | --- |
| 入站解析流量 | 48 包/秒且 256 KiB/秒 | 不另设入站合计额度 |
| 全部出站消息 | 2 MiB/秒 | 512 KiB/tick |
| 其中资产传输消息（push 与 legacy） | 512 KiB/秒 | 256 KiB/tick |
| 排队或进行中的 transfer（共享） | 2 | 32 |

超出入站预算的包直接忽略，不解析或执行动作。出站包在扣减前同时核对所有适用预算，超限不消耗其他预算。资产 begin/chunk/end 超限时保留传输阶段和分片下标，后续 tick 继续；未发送的分片不会跳号，正常完成只在 end 成功发送后释放传输。实际发送失败或失去授权则取消传输并释放并发槽。控制/状态包发送失败沿用后端回退和客户端租约超时恢复；限流不保证所有拥挤连接仍可接管显示。

主动推送只接收已签发 token 的状态反馈，资源包模式关闭本频道资产下载，legacy 只接受当前可见绑定的 modelId/hash；都不接受任意文件、URL 或路径。兼容能力不是可信客户端身份，恶意客户端仍可只声明 `local_render`，但受同一授权和预算约束。已接收的资产不能因解绑收回；ready/heartbeat 也是客户端声明，服务器能验证绑定和可见性，不能证明 GPU 实际绘制。完整 raw 包含内嵌 PNG，可能与 CE 包重复；跨包贴图复用尚未实现，ME／CE 的资源包保护不加密本频道资产。

## 就绪、确认与恢复

客户端就绪：
```json
{"protocol":3,"type":"render_ready","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}
```
服务器再次验证当前 owner/instance/hash、可见性、owned 且无外来模型，然后只对该观众抑制 ME 显示，返回 `render_ack {owner,instance,hash}`。客户端只有匹配本地待确认实例的 ACK 到达后才开启本地绘制。hello 和 state 本身均不切换 ME。就绪客户端仍占用原来的观众名额，不额外增加 max-viewers。

资源重载先撤销服务器渲染租约并发送 `render_failed`，让后端恢复显示；保留服务器绑定身份、完整推送模型及仍有效的在途 offer/下载，重新准备 GPU 后必须再次 ready/ack。私人与旧资源包预览的后台结果失效，旧资源包来源按当前 ResourceManager 重新加载；服务器磁盘缓存保留。默认推送不依赖 MPA 资源包索引，不能仅凭文件继续旧租约。私人外观设置保留，但有已知服务器本人伪装时仍等待该绑定重新就绪，避免与 ME 回退同时显示。断开、世界切换或无人再授权需要相同 hash 时，才清理对应旧推送授权和结果。

对于其他玩家，ME 隐藏基础实体时原版追踪包也会被过滤。服务器在 ACK 前仅为该就绪观众及 owner 实体 ID 安装原版追踪通道，用 ME 公共 `ProtectedPacket` 保留出生、位移、朝向、姿态、装备、挥臂及乘客数据，并通过公共 forceSpawn 初始化原版实体。混合 bundle 保留顺序，其他实体包继续经过 ME。通道无法建立时拒绝接管并保持 ME；退租时移除隐藏基础实体的客户端副本及对应例外。被观看者无需握手或安装模组。

解除伪装时，退租删除副本发生在 ME 解除基础实体隐藏之前，Paper 不会因为显示标记恢复而重新配对已追踪的玩家。服务器因此记住本实例接管过的远端观众，恢复原观众过滤器与 forcedInvisible 后补发原版配对数据；仅发送给仍在线、同世界、canSee 且当前追踪集合允许的观众。原版自己、消失的玩家、离开原版追踪距离的观众及其他插件的隐藏关系不在补发范围内。

客户端每 20 tick 发送：
```json
{"protocol":3,"type":"render_heartbeat","bindings":[{"owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}]}
```
bindings 最多 64 项，owner 唯一。只续租已有且 instance/hash 完全相符的就绪项；未列出的项不立即移除，但到 100 tick 无续租时恢复 ME。旧实例心跳不能续新实例。GPU/绘制失败立即发送 `render_failed {owner,instance,hash}`，停止本地绘制，服务器恢复该观众的 ME。失败 hash 应使用客户端退避，避免无限重试。

服务端 `heartbeat {serverTick,leaseTicks:100}` 每 20 tick 刷新客户端连接租约。客户端使用单调时钟按名义 20 TPS（5 秒）检查服务租约，世界暂停或卡顿不能无限保留绑定；断开、世界切换立即清理。`unbind {owner,instance,reason}` 只清理匹配实例；服务器解绑、观众离开、超时、断开、禁用、重载、新 hello 都恢复 ME。Bukkit onDisable 之后可能拒发 unbind，客户端超时清理是必要的。

## 动作请求与错误

```json
{"protocol":3,"type":"request","action":"play","argument":"wave"}
{"protocol":3,"type":"request","action":"reset"}
```
支持 play / stop / sit / crawl / reset。play 需要 1–128 位英文字母、数字、_ . : / - 的 argument，其他动作无参数。原始模型/动作命令校验与权限仍以服务器为准；不能携带 owner/target/command 或改写他人状态。play/sit/crawl 与手动快照共享请求冷却；stop/reset 即刻执行且不占用冷却，仍受每秒流量限制。资产/ready/续租独立于动作冷却。

`error {code}` 向已握手观众反馈：invalid_payload、unsupported_protocol、request_cooldown、action_failed、asset_server_push_mode、asset_resource_pack_mode、asset_status_wrong_mode、asset_not_authorized、asset_cooldown、asset_queue_full、asset_unavailable、render_not_authorized、render_unavailable。`asset_queue_full` 同时覆盖连接和全局并发上限。渲染拒绝的 error 会额外携带 owner/instance/hash，客户端据精确绑定退避。未知协议结束该观众会话并恢复 ME，连接预算保留；超流量包不保证收到 error。

0.4.0 增加 state.foodLevel（0–20）；远端饥饿值用于 YSM 条件动画，缺省 20。模型资产优先顺序为插件 models/、内嵌表达式原模型、外部 ME 蓝图，避免把数值烘焙蓝图发送给本地解释器。脚本物理只影响模型绘制，不修改真实玩家碰撞或坐标。

## 0.4.2 主动推送迁移

频道和 `protocol:3` 保持不变，通过 `server_push_models` 与 `assetMode:"server-push"` 协商扩展。客户端不需要 MPA 资源包索引或离线合并；ME 生成原版资源，CE 沿用合并、保护和下发流程。服务器提供完整 raw、签发授权 offer 并推送缺失资产；客户端校验、解析与 GPU 准备后自动 ready，精确 ACK 后绘制。

0.4.2 安装 ZIP 不自动部署内置 raw 到 `plugins/MEPlayerActions/models/`；两套示例由服务器 JAR 提供，`examples/models/` 只供参考。升级时核对管理员显式覆盖文件，避免旧 OWN 副本遮蔽新的 JAR。其他模型可从完整 ME 蓝图读取，已烘焙丢失的数据须另有完整 raw。

未升级客户端继续按其能力走有界资源包或 legacy 路径。新客户端要求支持主动推送的服务端；旧服务端不支持扩展时保持后端显示，建议两端一起升级。私人选择仍默认关闭，服务器渲染默认开启；已有总开关关闭的用户见 [客户端配置](../../../../../../../docs/CLIENT_CONFIG.md)。引擎替换需要服务端适配器实现相同资产与接管语义，客户端无需因引擎名称变化而更新。
