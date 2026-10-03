# 客户端模型协议 v3

频道 `meplayeractions:main`，严格 UTF-8 JSON，每包带整数 `protocol:3`、字符串 `type`。不兼容 v1/v2。所有消息保持分包顺序；不允许重复键、类型强制转换、非法 UTF-8、尾随内容；当前服务端还拒绝客户端请求中的额外字段。入包受 maxPayload（默认 16000 字节）和每观众每秒 48 包限制。客户端请求只操作自己，仍经过服务端权限校验。

## 引擎与本地外观边界

v3 是玩家模型资产、动画状态及观众渲染接管协议。客户端只依赖 Fabric Loader、Minecraft、Java 与 Fabric API，协议字段不要求 ModelEngine 对象或引擎标识。本仓库的服务端实现使用 ModelEngine；文中涉及 ME、Paper、GSit、插件目录和命令权限的行为说明该适配器的实现。

其他引擎要使用现有客户端，应实现同频道、版本、字段范围及以下语义：分发客户端支持的 `.bbmodel` 与对应 hash；提供已解析的动画层和 `motion` 状态映射；为观众保留可追踪的原版玩家实体；匹配 ready/ack 后才切换该观众的后端显示；失败、解绑和租约超时恢复正常显示。服务器的姿态后端可将已支持的 sit/sleep/crawl 与接触面偏移映射到 `specialPose` 和 anchor 字段，无需安装 GSit 才能发送这些字段。实现兼容适配器后，更换服务器引擎本身不要求更新客户端；这不表示客户端会自动识别未知引擎、新状态枚举或不支持的模型格式。

纯本地自己的外观使用客户端模型和设置，不发送 `state`、动作请求或 ready/heartbeat，不占服务器观众名额。没有服务器插件时也可显示，但其他玩家看不到这份本地选择。服务器多人伪装仍按下述授权、资产和租约流程同步；本地外观不能替代该流程。

## 握手与状态

客户端发送：
```json
{"protocol":3,"type":"hello","clientVersion":"0.4.0","capabilities":["local_render"]}
```
`capabilities` 必须含唯一的 `local_render`；`clientVersion` 可省略（最多 64 字符）。hello 每秒最多处理一次。新 hello 清理旧绑定并恢复旧 ME 可见性，不授予渲染权限。

返回 `hello_ack`：`mode:"local-render"`、`serverTick`、`heartbeatTicks:20`、`leaseTicks:100`、`maxPayload`、`requestCooldownTicks`、`capabilities:["local_render"]`。随后发送 `snapshot_begin {snapshotId,serverTick}`、可见状态、`snapshot_end {snapshotId}`；手动 `snapshot_request` 也会生成此快照边界。后续每 2 tick 发送可见实例的完整 state，动作切换可即时发送。相同 sequence 的 transform 仍可能变化，客户端不能按 sequence 丢弃同序号位置包。

```json
{"protocol":3,"type":"state","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","modelId":"ysm_01_jk","assetHash":"小写SHA256","sequence":12,"serverTick":12345,"world":"00000000-0000-0000-0000-000000000003","x":1.0,"y":64.0,"z":2.0,"bodyYaw":90.0,"headYaw":100.0,"headPitch":10.0,"scale":1.5,"hidePlayer":true,"showSelf":true,"foodLevel":20,"layers":[{"layer":"posture","animation":"crawl_idle","startedAtTick":12340,"speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"animations":[{"id":"wave","label":"挥手"}],"motion":{"features":["movement","crawl"],"clips":[{"state":"crawl-idle","animation":"crawl_idle","speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"jumpMinTicks":17,"landingGraceTicks":6,"movementThreshold":0.025,"interruptMove":true,"interruptPosture":true,"flying":false,"interaction":"","forcedPose":"","specialPose":"","anchorX":0,"anchorY":0,"anchorZ":0,"anchorYaw":0}}
```

`instance` 是伪装生命周期 UUID，模型更换生成新值。UUID 必须是标准 36 字符小写形式。`sequence` 是动画变化序号；`serverTick` 是名义 20 TPS、unsigned 32 位的服务器 tick，当前适配器取 Paper 当前 tick。x/y/z 与 body/head yaw/pitch 为服务器展示轨迹，当前适配器保留 visual-follow.delay 历史帧，只在客户端显式开启 `followServerTimeline` 时使用。默认模式根据 owner UUID 查找观看者的原版 PlayerEntity，逐帧使用 getLerpedPos 与原版角度插值，既适用于本人，也适用于未安装模组的被观看者；不叠加服务器 delay 或客户端 interpolationTicks。未跟踪到原版实体时不绘制幽灵模型。动画使用客户端本地 tick 时钟，普通姿态在每个游戏 tick 采样；服务器手动层的开始时刻只转换一次，同实例回包不重启动画。

state 必须额外包含 `motion` 对象：`features` 为服务器及玩家允许的同步项，`clips` 为已解析且模型存在的 `{state,animation,speed,loop,inTicks,outTicks}` 集合；缺失映射不硬编码回退。另有 `jumpMinTicks`、`landingGraceTicks`、`movementThreshold`、`interruptMove`、`interruptPosture`、`flying`（远端能力不一定由原版同步）、`interaction`（空/mining/swing-mainhand/swing-offhand）。`forcedPose` 为空/crawl/sneak，仅传递 Paper 固定姿态或 GSit 强制爬行；本机原版重新计算为 STANDING 时仍必须遵守，不拿延迟的自动层猜测姿态。原生陆地爬行与游泳、床睡眠与 GSit 躺下分别判断；禁用姿态不落入其他状态。本人挖掘读取本地交互管理器，远端挖掘采用服务器的明确 interaction 状态，不能根据同名攻击动画猜测。

`motion.specialPose` 为空或 sit/sleep/crawl；`anchorX/Y/Z` 是**当前**姿态接触面相对真实玩家脚底的偏移，`anchorYaw` 为姿态朝向。当前 GSit 适配器取公共 Seat.location + SitService.baseOffset 与 Seat.yaw。这些字段不经过 visual-follow 历史，特殊姿态优先于其隐藏坐骑，睡姿头向保持 bodyYaw、headPitch=0；原生床则从客户端真实 Bed 两半读取几何中心、Y+0.5625 和朝向。不能把 GSit 假床包当作真实床，也不能使用下移的 rawPlayer Y 直接绘制。yaw/pitch 以度表示，scale 是正数均匀缩放；卧倒 Root 由模型动画负责，禁止再施加原生 SWIMMING 或睡姿整模型旋转。

`layers` 是完整集合：缺席的层应退出；名称 posture / interaction / manual，优先级依次 100 / 150 / 200。posture/manual 覆盖有关键帧的骨骼，interaction 叠加；in/outTicks 是 20 TPS 的过渡时长。loop 为 ONCE / LOOP / HOLD，startedAtTick 用于从服务器时间同步动画进度。animations 是可发送 play 请求的动作中文菜单目录：有模型目标动画的 custom-actions 使用动作别名 ID 和 label；仅 manual.allow-raw-animation=true 时补充符合服务器命令 ID（1–64 位小写英文、数字、_、-）的原始动画。同 ID 的自定义动作优先，目标缺失的别名也不会回退为不可执行的原始动作，目录去重并按 ID 排序。label 去除 ISOControl 后最多 64 个 Unicode code point。若 state 超出 maxPayload，先去掉目录并发送 animations:[]；位置与动作层仍超限时解绑并保持 ME，日志按观众/owner 去重。hidePlayer 控制真实人物渲染；showSelf 控制本人可见性。服务器尊重同世界、Player.canSee、disguise.view-distance 的严格三维距离限制、max-viewers 最近观众上限和 show-self，客户端不能据状态突破可见性。

非空 `assetHash` 表示服务器允许客户端准备这份实例的资产，空值表示保持后端显示，不允许 render_ready。当前 ModelEngine 适配器只有本插件创建、实体没有外来模型且有可分发资产的实例提供非空值；接管原生 ME、外来模型共存或资产失败时 hash 为空。

`state.accessories` 是原模型持久附件状态，仅可包含有限的 `a`、`b` 数字，范围 0–1，对应 `variable.roaming.a/b`。服务器按当前伪装实例同步，模型切换或解除后重置。客户端在时间脚本执行后、几何采样前应用它，避免新观众或离开可视范围后回来时重放动作造成重复切换。物理变量仍由各观看者按本地实体运动计算。该字段缺省为空对象，本地预览自行执行附件脚本。

## 资产传输

资产是原始 .bbmodel JSON（含 elements/outliner/animations 与内嵌 PNG），以 GZIP 压缩传输，hash 为**原始 JSON 字节** SHA-256 小写 64 位十六进制，不是 gzip hash。原始上限 8 MiB，压缩上限 4 MiB。客户端依据 hash 缓存；无需每次重新下载。

```json
{"protocol":3,"type":"asset_request","modelId":"ysm_01_jk","hash":"小写SHA256"}
```

协议 modelId 仅允许 1–64 位小写英文字母/数字/_/-；hash 必须匹配当前可见绑定。当前服务器适配器的资产查找：`plugins/MEPlayerActions/models/<id>.bbmodel` → JAR `models/<id>.bbmodel` → `plugins/ModelEngine/blueprints/` 内文件名或 model_identifier。资产在本服务生命周期缓存，更新后 reload。其他引擎可从自己的资产库提供相同 wire 内容，无需复用这些目录。客户端本地外观的 `local:文件名.bbmodel` 只用于本地文件选择，不属于服务器 modelId，也不发送到此协议。

服务端顺序发送：
- `asset_begin {modelId,hash,rawBytes,compressedBytes,chunks}`
- `asset_chunk {hash,index,data}`：index 从 0 连续递增，data 是 gzip 分片标准 Base64。
- `asset_end {hash}`

每片最多 9000 gzip 字节（小 maxPayload 会进一步缩小，1024 字节负载时每片 384 gzip 字节），客户端分片上限 16384；每观众每 tick 最多 2 个 chunk，最多 2 个排队传输。重复在途请求忽略，同 hash 完成后 100 tick 内请求受冷却。离开范围/实例解绑且无人需要该 hash 时取消传输。客户端检查声明长度、分片连续性、压缩与解压上限、完整 SHA256，模型解析及纹理 GPU 注册成功后才可 ready；资产失败不隐藏 ME。

## 就绪、确认与恢复

客户端就绪：
```json
{"protocol":3,"type":"render_ready","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}
```
服务器再次验证当前 owner/instance/hash、可见性、owned 且无外来模型，然后只对该观众抑制 ME 显示，返回 `render_ack {owner,instance,hash}`。客户端只有匹配本地待确认实例的 ACK 到达后才开启本地绘制。hello 和 state 本身均不切换 ME。就绪客户端仍占用原来的观众名额，不额外增加 max-viewers。

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

`error {code}` 向已握手观众反馈：invalid_payload、unsupported_protocol、request_cooldown、action_failed、asset_not_authorized、asset_cooldown、asset_queue_full、asset_unavailable、render_not_authorized、render_unavailable。渲染拒绝的 error 会额外携带 owner/instance/hash，客户端据精确绑定退避。未知协议结束该观众会话并恢复 ME。

0.4.0 增加 state.foodLevel（0–20）；远端饥饿值用于 YSM 条件动画，缺省 20。模型资产优先顺序为插件 models/、内嵌表达式原模型、外部 ME 蓝图，避免把数值烘焙蓝图发送给本地解释器。脚本物理只影响模型绘制，不修改真实玩家碰撞或坐标。
