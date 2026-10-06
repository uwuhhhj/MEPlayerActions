# 客户端协议

本文定义当前线格式、授权与限制。本地私人外观不上传模型；私人多人分享使用独立 `meplayeractions:private` protocol 1；服务器伪装使用 `meplayeractions:main` protocol 3。私人发布不是服务器伪装实例，两频道身份和权限不能混用。模块与生命周期见 [架构](../ARCHITECTURE.md)，部署见 [模型同步](MODEL_DELIVERY.md)，后台频率见 [性能说明](PERFORMANCE.md)，全服预算与降级见 [资源保护](SERVER_RESOURCE_PROTECTION.md)。

频道 `meplayeractions:main`，严格 UTF-8 JSON，每包带整数 `protocol:3`、字符串 `type`。不兼容 v1/v2。所有消息保持分包顺序；不允许重复键、类型强制转换、非法 UTF-8、尾随内容；当前服务端还拒绝客户端请求中的额外字段。每包受 maxPayload（默认 16000 字节）限制；连接流量和下载预算见下文。客户端请求只操作自己，仍经过服务端权限校验。

## 适配器约束

v3 是玩家模型资产、动画状态及观众渲染接管协议。客户端只依赖 Fabric Loader、Minecraft、Java 与 Fabric API，协议字段不要求 ModelEngine 对象或引擎标识。本仓库的服务端实现使用 ModelEngine；文中涉及 ME、Paper、GSit、插件目录和命令权限的行为说明该适配器的实现。

其他引擎要使用现有客户端，应实现同频道、版本、字段范围及以下语义：主动推送当次授权的完整 `.bbmodel` 与对应 hash（兼容旧客户端时另提供下述资源包或 legacy 路径）；提供已解析的动画层和 `motion` 状态映射；为观众保留可追踪的原版玩家实体；匹配 ready/ack 后才切换该观众的后端显示；失败、解绑和租约超时恢复正常显示。服务器的姿态后端可将已支持的 sit/sleep/crawl 与接触面偏移映射到 `specialPose` 和 anchor 字段，无需安装 GSit 才能发送这些字段。实现兼容适配器后，更换服务器引擎本身不要求更新客户端；这不表示客户端会自动识别未知引擎、新状态枚举或不支持的模型格式。

纯本地外观不建立 v3 绑定，不发送该模型的动作请求、ready 或渲染心跳。本人已有服务器伪装时，其绑定与租约独立维护，同时暂停私人外观与分享。暂停标记在本机保存；完整快照或精确解绑确认服务器伪装结束后，仍须用户显式重新使用私人模型。浏览和编辑草稿不解除暂停。用户默认值、来源切换及显隐开关见 [客户端配置](CLIENT_CONFIG.md)。

## 握手与状态

客户端发送：
```json
{"protocol":3,"type":"hello","clientVersion":"0.6.0","capabilities":["local_render","server_push_models","incremental_state","server_model_catalog","disguise_results"]}
```
`capabilities` 必须含 `local_render`，还可声明 `server_push_models`、`resource_pack_models`、`incremental_state`／`server_timeline`、`server_model_catalog` 和 `disguise_results`；不允许重复，其他 capability 拒绝。客户端默认声明增量、模型目录与伪装结果能力，显式启用 `followServerTimeline` 时另声明 `server_timeline`。`clientVersion` 可省略（最多 64 字符）。hello 两次接受之间至少相隔一个单调时钟秒；这一时间戳属于玩家连接，未知协议结束会话或重新 hello 都不能重置。新 hello 清理已有绑定并恢复其 ME 可见性，不授予渲染权限。

返回 `hello_ack`：`mode:"local-render"`、`serverTick`、`heartbeatTicks:20`、`leaseTicks:100`、`maxPayload`、`requestCooldownTicks`。服务端优先选择 `server_push_models`，得到 `assetMode:"server-push"` 和对应能力；否则声明 `resource_pack_models` 的旧连接得到 `assetMode:"resource-pack"`，仅声明 `local_render` 的旧客户端得到 `assetMode:"legacy-download"`。ACK 只确认客户端已声明的增量／轨迹能力；`incremental_state` 必须双方同时确认才生效。当前客户端仍要求 ACK 确认 `server-push`、`local_render` 和 `server_push_models`；旧服务器以硬能力白名单拒绝扩展 hello 时，最多重试一次旧两项能力，不自动改用旧下载或资源包模式。模式在本次会话内固定，客户端不能在请求中传入 `assetMode` 改写模式。随后发送 `snapshot_begin {snapshotId,serverTick}`、可见状态、`snapshot_end {snapshotId}`；手动 `snapshot_request` 也会生成此快照边界。未协商增量时每 20 tick 发送完整 state；增量模式发送受管状态变化，静态 `animations` 可省略复用，精确绑定 heartbeat 续期。协商 `server_timeline` 保留每 2 tick 的轨迹 state。动作变化仍可即时发送；相同 sequence 的 transform 仍可能变化，客户端不能按 sequence 丢弃同序号位置包。

切换 `followServerTimeline` 会释放已有显示租约并重新 hello，保留已知本人服务器伪装直到新快照确认，私人外观继续暂停。

完整快照流程（含 `snapshot_request` 恢复）强制重发全部当前授权 state 和完整 `animations`，无需 sequence／目录变化，并保留既有 binding／lease；预算延后的完整目录与后续最新状态合并，直到成功发送都不会被 delta 省略。

客户端经整片校验的增量 heartbeat 出现未知 owner 或 instance／hash 不匹配时，按与本人绑定丢失共用的 2 秒节流请求完整快照；不创建绑定、不指定模型或请求资产，仍由服务端判断资格并主动下发。合法分片未列出的绑定不构成缺失，旧式 heartbeat 不触发此恢复。

```json
{"protocol":3,"type":"state","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","modelId":"ysm_01_jk","assetHash":"小写SHA256","sequence":12,"serverTick":12345,"world":"00000000-0000-0000-0000-000000000003","x":1.0,"y":64.0,"z":2.0,"bodyYaw":90.0,"headYaw":100.0,"headPitch":10.0,"scale":1.5,"hidePlayer":true,"showSelf":true,"foodLevel":20,"layers":[{"layer":"posture","animation":"crawl_idle","startedAtTick":12340,"speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"animations":[{"id":"wave","label":"挥手"}],"motion":{"features":["movement","crawl"],"clips":[{"state":"crawl-idle","animation":"crawl_idle","speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"jumpMinTicks":17,"landingGraceTicks":6,"movementThreshold":0.025,"interruptMove":true,"interruptPosture":true,"flying":false,"interaction":"","forcedPose":"","specialPose":"","anchorX":0,"anchorY":0,"anchorZ":0,"anchorYaw":0}}
```

`instance` 是伪装生命周期 UUID，模型更换生成新值。UUID 必须是标准 36 字符小写形式。`sequence` 是动画变化序号；`serverTick` 是名义 20 TPS、unsigned 32 位的服务器 tick，当前适配器取 Paper 当前 tick。x/y/z 与 body/head yaw/pitch 保留为服务器展示轨迹及诊断数据。客户端根据 owner UUID 复用原版当帧 PlayerEntityRenderState 的坐标、实际 renderer 偏移、角度、tickDelta 和冻结相机；私人外观 offset 与明确的服务器姿态 anchor 是独立静态偏移。第一人称本人仅为手臂／脚本补建原版状态；不沿用旧帧远端坐标。查询与物理使用不含绘制偏移的原生位置。所有渲染接管模式均不叠加服务器 delay 或客户端位置缓冲，`followServerTimeline` 仅选择服务器动画层／动画时间线。未跟踪到原版实体时不绘制幽灵模型。动画使用客户端本地 tick 时钟，普通姿态在每个游戏 tick 采样；服务器手动层的开始时刻只转换一次，同实例回包不重启动画。

state 必须额外包含 `motion` 对象：`features` 为服务器及玩家允许的同步项，`clips` 为已解析且模型存在的 `{state,animation,speed,loop,inTicks,outTicks}` 集合；缺失映射不硬编码回退。另有 `jumpMinTicks`、`landingGraceTicks`、`movementThreshold`、`interruptMove`、`interruptPosture`、`flying`（远端能力不一定由原版同步）、`interaction`（空/mining/swing-mainhand/swing-offhand）。`forcedPose` 为空/crawl/sneak，仅传递 Paper 固定姿态或 GSit 强制爬行；本机原版重新计算为 STANDING 时仍必须遵守，不拿延迟的自动层猜测姿态。原生陆地爬行与游泳、床睡眠与 GSit 躺下分别判断；禁用姿态不落入其他状态。本人挖掘读取本地交互管理器，远端挖掘采用服务器的明确 interaction 状态，不能根据同名攻击动画猜测。

`motion.specialPose` 为空或 sit/sleep/crawl；`anchorX/Y/Z` 是**当前**姿态接触面相对真实玩家脚底的偏移，`anchorYaw` 为姿态朝向。当前 GSit 适配器取公共 Seat.location + SitService.baseOffset 与 Seat.yaw。这些字段不经过 visual-follow 历史，特殊姿态优先于其隐藏坐骑，睡姿头向保持 bodyYaw、headPitch=0；原生床则从客户端真实 Bed 两半读取几何中心、Y+0.5625 和朝向。不能把 GSit 假床包当作真实床，也不能使用下移的 rawPlayer Y 直接绘制。yaw/pitch 以度表示，scale 是正数均匀缩放；卧倒 Root 由模型动画负责，禁止再施加原生 SWIMMING 或睡姿整模型旋转。

`layers` 是完整集合：缺席的层应退出；名称 posture / interaction / manual，优先级依次 100 / 150 / 200。posture/manual 覆盖有关键帧的骨骼，interaction 叠加；in/outTicks 是 20 TPS 的过渡时长。loop 为 ONCE / LOOP / HOLD，startedAtTick 用于从服务器时间同步动画进度。animations 是可发送 play 请求的动作中文菜单目录：有模型目标动画的 custom-actions 使用动作别名 ID 和 label；仅 manual.allow-raw-animation=true 时补充符合服务器命令 ID（1–64 位小写英文、数字、_、-）的原始动画。同 ID 的自定义动作优先，目标缺失的别名也不会回退为不可执行的原始动作，目录去重并按 ID 排序。label 去除 ISOControl 后最多 64 个 Unicode code point。若 state 超出 maxPayload，先去掉目录并发送 animations:[]；位置与动作层仍超限时解绑并保持 ME，日志按观众/owner 去重。hidePlayer 控制真实人物渲染；showSelf 控制本人可见性。服务器尊重同世界、Player.canSee、disguise.view-distance 的严格三维距离限制、max-viewers 最近观众上限和 show-self，客户端不能据状态突破可见性。

增量模式允许省略静态 `animations`：缺失时只保留相同 owner／instance／assetHash／modelId 的既有目录，显式 `[]` 清空；不同身份不能继承目录。未协商时缺失表示清空。`layers` 与 `motion` 等当前状态仍完整发送，不把缺失动画层解释为保留上一层。

非空 `assetHash` 表示服务器允许客户端准备这份实例的资产，空值表示保持后端显示，不允许 render_ready。当前 ModelEngine 适配器只有本插件创建、实体没有外来模型且有可分发资产的实例提供非空值；接管原生 ME、外来模型共存或资产失败时 hash 为空。state 还提供 `assetStatus`（pending/ready/missing/invalid，或不允许接管时的 server-only）、`assetReason` 和 `assetSource` 诊断；它们不授予渲染权限，原因不包含服务器私有文件路径。

`state.accessories` 是原模型持久附件状态，仅可包含有限的 `a`、`b` 数字，范围 0–1，对应 `variable.roaming.a/b`。服务器按当前伪装实例同步，模型切换或解除后重置。客户端在时间脚本执行后、几何采样前应用它，避免新观众或离开可视范围后回来时重放动作造成重复切换。物理变量仍由各观看者按本地实体运动计算。该字段缺省为空对象，本地预览自行执行附件脚本。

## 服务器主动推送与资产身份

### 可选服务端模型目录

hello／hello_ack 双方确认 `server_model_catalog` 后，服务器主动发送 `server_model_catalog {revision,index,count,canDisguise,truncated,models:[{id,label}]}`。`revision` 为本会话递增正整数，`index` 从 0 开始，`count` 最多 4096；一片最多 512 条，总目录最多 4096 条。全部分片完成后才发布新目录；更高版本开始接收时暂停旧选择权限，重复片必须内容一致。无权限时发送单片空目录、`canDisguise:false`；`truncated` 表示目录达到上限。目录 ID 与下文模型 ID 规范相同，label 最多 256 字符。

本适配器从已加载且符合 `models.allowed` 的模型生成共享目录，每 100 tick 刷新源缓存；会话沿既有分散发现周期检查更新和权限，默认在 20 TPS 下最坏约 7 秒。每会话每 tick 最多成功发送一片，受原字节预算限制；延后保留原片，不挤入关键控制队列。正在发送时先检查权限撤回，丢弃旧待发片。旧客户端未声明能力就不发送目录。

目录仅授权选择固定命令 `/meplayeractions disguise <ID> [已验证的可选参数]`，参数来自本机按 ID 保存的 `scale`、`hide-self`、`show-self`、`view-distance`、`max-viewers`、`delay`、`effect`，不接受任意命令文本。客户端共享 500 ms 发送间隔，服务器仍检查真实命令权限与参数范围。目录不包含资源 token/hash，也不授权 `asset_request`；浏览仅复用已收到的本次会话资产。服务器确认伪装后沿下文 offer／ready／ACK 分发。目录接收、断线、重新握手与超时均不能继承旧会话的选择权限。

### 可选伪装命令结果

双方确认 `disguise_results` 后，客户端在伪装命令末尾添加 `request-id=<标准小写 UUID>`，命令总长度最多 256 字符。服务器剥离该关联字段后仍按原命令权限、参数校验与幂等规则执行；关联 ID 不是权限。未协商时客户端不添加它。

服务端 `disguise_result {requestId,modelId,success,instance,options}` 的成功结果包含当前真实伪装实例和解析后的七项参数；`options` 使用 `scale`、`hideSelf`、`showSelf`、`viewDistance`、`maxViewers`、`delay`、`effect`，无药水效果为空字符串。失败结果提供简短 `code`／`message`。相同参数可复用既有实例，不能为等待确认而强制重建模型。

客户端只接受当次请求 ID／模型匹配的结果，并与本人真实绑定实例核对；结果先于 state 时继续等待该实例，不凭本机发出命令或旧实例更新认定成功。显式参数也与服务器解析结果比对。本人 `showSelf:false` 仍接收自有伪装状态与动作目录用于确认、互斥和轮盘，但不能因此取得本人绘制租约。断线、重新握手、失败和 10 秒超时清理等待状态，超时提示允许重试。未协商时沿用新实例确认，不能保证识别旧服务器的同参数幂等请求。

结果绑定当次连接，出站预算延后时保留重试。服务器保留最近 32 条请求结果记录；记录内同请求 ID 重发不重复执行药水等副作用。Bukkit 在进入插件前拒绝 `mact.use` 的请求仍使用原版命令拒绝提示，客户端等待有界超时；该入口不保证产生 `disguise_result`。

### 资产授权

所有模式保持相同的 `state.modelId`、`state.assetHash` 和 ready/ack/heartbeat 语义。hash 是**原始完整 .bbmodel JSON 字节** SHA-256 小写 64 位十六进制，不是 ZIP、PNG 或 GZIP hash。协议模型 ID 为 1–64 位小写英文、数字、`_`、`-`。

当前资产查找顺序为 `plugins/MEPlayerActions/models/<id>.bbmodel` → 服务端 JAR 的 `models/<id>.bbmodel` → ME `blueprints/` 内匹配文件名，再匹配 `model_identifier`。安装 ZIP 的示例不自动部署为最高优先级覆盖。ME 数值蓝图不能恢复已烘焙掉的表达式和物理；模型放置与升级覆盖检查见 [模型部署](MODEL_DELIVERY.md)。

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

每片最多 9000 GZIP 字节，小 maxPayload 进一步缩小（1024 字节负载时为 384 字节）；客户端分片上限 16384。每观众每 tick 最多 2 个 chunk；默认每连接最多 2 个活动传输和 2 个等待项，全服 32 个活动传输、128 个等待项。主动推送、旧下载与私人资源共享配置化传输及带宽预算。

| 主动推送限制 | 当前值 |
| --- | --- |
| 服务器未完成 offer / offer 记录 | 每连接 2 / 64 |
| offer 发送间隔 / 同 hash 重试冷却 | 10 tick / 100 tick，跨新 hello 保留 |
| 同 hash 当前授权实例集合的重试额度 | 最多 3 次；仍有相同实例时，换别名或初始 owner 不能绕过 |
| 等待缓存反馈 | 100 tick（名义 5 秒） |
| missing 排队 / 传输空闲 | 默认 600 / 300 tick（名义 30 / 15 秒），成功 begin/chunk 刷新进度 |
| 未完成 offer 总时限 | 默认自签发起 2400 tick（名义 120 秒） |
| cached/delivered 等待 ready | 默认 300 tick（名义 15 秒） |
| 客户端活动 offer / 空闲 / 总时限 | 4 / 15 秒 / 60 秒 |

重试计数以同 hash 当前授权服务器实例集合的交集维护；全部实例实际更换、实际断开或成功 render_ack 才可重置对应失败计数，新 hello 不重置。已有相同 hash 的有效渲染租约不重复签发 offer。

客户端 `config/meplayeractions/cache/<hash>.bbmodel` 缓存完整原始 JSON，读取时重新检查文件、8 MiB 和 SHA；模型解析由加载流程完成。缓存总量 128 MiB、最多 512 个有效条目，按最近使用时间裁剪，离服保留，与私人 `config/meplayeractions/models/` 分开。缓存命中仍需当前 offer、绑定和 ready/ACK；文件存在不授予使用权。存储不可用时不信任缓存，也不允许绕过资产校验。

## 兼容资产模式

未声明 `server_push_models` 而声明 `resource_pack_models` 时得到 `assetMode:"resource-pack"`，该会话的 `asset_request` 返回 `asset_resource_pack_mode`，不发送文件。模型来自 ResourceManager 的索引、元数据和 PNG，按原始 hash 恢复校验；缺失时保持 ME。格式见 [资源包兼容格式](CLIENT_RESOURCE_PACK.md)，主动推送部署不需要此索引。

仅声明 `local_render` 时得到 `assetMode:"legacy-download"`：发送 `asset_request {modelId,hash}`，两者须匹配当前可见绑定。返回不带 offerId 的 `asset_begin/chunk/end`，受原始/GZIP 上限、并发、连接及全局预算、100 tick hash 冷却限制。每次分片检查授权，无人再需要时取消，失败保持 ME。

当前客户端要求主动推送协商成功，不自动发送 legacy 请求或切入资源包模式。同为 v3 不表示所有 capability 扩展互通，部署时须检查双方确认的能力。

## 连接预算

服务端在主线程按玩家连接保存预算；普通会话结束、未知协议、新 hello、同步配置禁用/恢复都不会重置流量、hello 时间、动作冷却或 hash 冷却。只有明确 quit 或实际离线清理才移除该连接预算。插件实例销毁和服务器重启不属于持久化限流。

字节数按两个频道实际 UTF-8 JSON 计算，包含 Base64、字段名和消息封装。单连接使用单调时钟的一秒固定窗口；全局使用令牌桶控制速率与突发，tick 使用服务器 unsigned 32 位 tick。下表为 `resource-protection.network` 默认值：

| 预算 | 单连接 | 服务端合计 |
| --- | --- | --- |
| 入站解析流量 | 48 包/秒且 256 KiB/秒 | 4096 包/秒且 4 MiB/秒 |
| 全部出站消息 | 48 包/秒且 2 MiB/秒 | 4096 包/秒且 8 MiB/秒、512 KiB/tick |
| 其中资产传输消息（server/private/legacy） | 36 包/秒且 512 KiB/秒 | 3072 包/秒、全部出站字节速率一半（默认 4 MiB/秒）、256 KiB/tick |
| 活动 transfer（共享） | 2 | 32 |
| 等待 transfer（共享） | 2 | 128 |
| 延后出站字节（共享） | 计入全服额度 | 16 MiB |

入站与出站包数独立计量；资产包数由出站总包数减去向上取整的 25% 得出，为 ACK、撤权和清理消息保留余量。全局字节突发默认 8 MiB；突发仍受每 tick、单连接和子预算限制。外部线程入站回调也受数量与字节界限，不能无限追加 Bukkit 主线程任务。资源预算预检在分片拷贝／编码前执行，延后片保留当前内容和下标。支持真实通道探针的 ME 后端还会暂停向不可写连接追加资产；私人中继独立模式没有 Paper 公共 API 的通道可写性保证，仍由预算、等待字节、进展和总超时控制。各上限及保护倍率见 [资源保护](SERVER_RESOURCE_PROTECTION.md)。

超出入站预算的包直接忽略，不解析或执行动作。出站包在扣减前同时核对所有适用预算，超限不消耗其他预算。发送结果分为 `SENT / DEFERRED / FAILED`：正常限流为 DEFERRED，保留绑定与显示接管，状态按 owner 合并最新待发包；unbind 等可延后的控制消息进入每会话最多 128 项有界队列，每次维护最多尝试 4 项，正常预算不足继续等待，身份失效、100 tick 到期、队列满或实际发送失败时清理，会话轮转。待发 unbind 若相同 instance 已重绑则跳过，成功发送 state 也取消相同 owner／instance 的待发解绑，不清理新绑定。render_ack 只走先预留预算的 ready 路径，不进入该队列。资产 begin/chunk/end 超限保留阶段和分片下标，后续 tick 继续；未发送的分片不会跳号，正常完成只在 end 成功发送后释放传输。每个资产包实时检查绑定、当前快照及可见性。实际发送失败或失去授权才取消传输、释放槽并按后端回退处理；持续拥堵仍受租约超时约束。

主动推送只接收已签发 token 的状态反馈，资源包模式关闭本频道资产下载，legacy 只接受当前可见绑定的 modelId/hash；都不接受任意文件、URL 或路径。兼容能力不是可信客户端身份，恶意客户端仍可只声明 `local_render`，但受同一授权和预算约束。已接收的资产不能因解绑收回；ready/heartbeat 也是客户端声明，服务器能验证绑定和可见性，不能证明 GPU 实际绘制。完整 raw 包含内嵌 PNG，可能与 CE 包重复；跨包贴图复用尚未实现，ME／CE 的资源包保护不加密本频道资产。

## 就绪、确认与恢复

客户端就绪：
```json
{"protocol":3,"type":"render_ready","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}
```
服务器再次验证当前 owner/instance/hash、可见性、owned 且无外来模型，先构造并预留 `render_ack {owner,instance,hash}` 的出站预算。首次预算不足只保存 pendingReady，保持 ME，不安装通道、不启用本地接管；预留成功才尝试通道安装／启用。真正启用后建立精确租约、只对该观众抑制 ME，直接发送已预留 ACK，不二次扣预算、不把 render_ack 排入延后控制队列；实际发送失败退租并恢复 ME。重复 ready 遇到预算忙保留已有 ACK 租约，pending 最多等待 100 tick，到期不撤销仍在正常续期的旧租约。客户端只有匹配本地待确认实例的 ACK 到达后才开启本地绘制。hello 和 state 本身均不切换 ME。就绪客户端仍占用原来的观众名额，不额外增加 max-viewers。

资源重载先撤销服务器渲染租约并发送 `render_failed`，让后端恢复显示；保留服务器绑定身份、完整推送模型及仍有效的在途 offer/下载，重新准备 GPU 后必须再次 ready/ack。私人与旧资源包预览的后台结果失效，旧资源包来源按当前 ResourceManager 重新加载；服务器磁盘缓存保留。默认推送不依赖 MPA 资源包索引，不能仅凭文件继续旧租约。私人外观设置保留，但有已知服务器本人伪装时仍等待该绑定重新就绪，避免与 ME 回退同时显示。断开、世界切换或无人再授权需要相同 hash 时，才清理对应旧推送授权和结果。

对于其他玩家，ME 隐藏基础实体时原版追踪包也会被过滤。服务器在 ACK 前仅为该就绪观众及 owner 实体 ID 安装原版追踪通道，用 ME 公共 `ProtectedPacket` 保留出生、位移、朝向、姿态、装备、挥臂及乘客数据，并通过公共 forceSpawn 初始化原版实体。混合 bundle 保留顺序，其他实体包继续经过 ME。安装 pending 时按精确 ready 身份等待维护，最多 100 tick，不因临时 pending 发送 `render_unavailable`；取消／关闭撤回等待项。通道无法建立时拒绝接管并保持 ME；退租时移除隐藏基础实体的客户端副本及对应例外。被观看者无需握手或安装模组。

解除伪装时，退租删除副本发生在 ME 解除基础实体隐藏之前，Paper 不会因为显示标记恢复而重新配对已追踪的玩家。服务器因此记住本实例接管过的远端观众，恢复原观众过滤器与 forcedInvisible 后补发原版配对数据；仅发送给仍在线、同世界、canSee 且当前追踪集合允许的观众。原版自己、消失的玩家、离开原版追踪距离的观众及其他插件的隐藏关系不在补发范围内。

客户端每 20 tick 发送：
```json
{"protocol":3,"type":"render_heartbeat","bindings":[{"owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}]}
```
bindings 最多 64 项，owner 唯一。只续租已有且 instance/hash 完全相符的就绪项；未列出的项不立即移除，但到 100 tick 无续租时恢复 ME。旧实例心跳不能续新实例。GPU/绘制失败立即发送 `render_failed {owner,instance,hash}`，停止本地绘制，服务器恢复该观众的 ME。失败 hash 应使用客户端退避，避免无限重试。

服务端 `heartbeat {serverTick,leaseTicks:100}` 每 20 tick 刷新客户端连接租约。协商增量后还带 `bindings:[{owner,instance,hash}]`，按最多 64 项及 maxPayload 分片，空数组仍表示连接存活。服务端发送前逐项实时复查 canObserve、当前 snapshot 的 owner／instance／model 和 assetHash，过滤已失效关系。客户端先校验整片，只给已存在且精确 owner／instance／hash 相符的绑定刷新状态期限；hash 可以为空以续期 pending 绑定，不创建新绑定、不续其他身份、不按单片缺项删除绑定。旧式 heartbeat 只更新时间／连接租约，完整 state 仍负责旧式绑定续期；客户端 `render_heartbeat` 则继续只续服务器已有显示接管租约，两个方向不可混用。

客户端使用单调时钟按名义 20 TPS（5 秒）检查服务租约，世界暂停或卡顿不能无限保留绑定；断开、世界切换立即清理。`unbind {owner,instance,reason}` 只清理匹配实例；服务器解绑、观众离开、超时、断开、禁用、重载、新 hello 都恢复 ME。Bukkit onDisable 之后可能拒发 unbind，客户端超时清理是必要的。

## 动作请求与错误

```json
{"protocol":3,"type":"request","action":"play","argument":"wave"}
{"protocol":3,"type":"request","action":"reset"}
```
支持 play / stop / sit / crawl / reset。play 需要 1–128 位英文字母、数字、_ . : / - 的 argument，其他动作无参数。原始模型/动作命令校验与权限仍以服务器为准；不能携带 owner/target/command 或改写他人状态。play/sit/crawl 与手动快照共享请求冷却；stop/reset 即刻执行且不占用冷却，仍受每秒流量限制。资产/ready/续租独立于动作冷却。

`error {code}` 向已握手观众反馈协议、权限、动作或资源失败。资源保护类还提供 `stage`、`retryable`、`retryAfter`（秒），能关联时携带请求／transfer／offer 身份；旧客户端可忽略新增字段。`asset_queue_full` 可覆盖连接、全局传输或后台队列容量，`tps_protection` 表示暂停新增视觉负载；上传、下载、ready 与后台校验超时分别归因，不返回私有文件路径。渲染拒绝会携带 owner/instance/hash，客户端据精确绑定退避。未知协议结束该观众会话并恢复 ME，连接预算保留；超流量包不保证收到 error。代码与阶段仅用于诊断，不改变原授权检查；实现枚举以两端错误处理为准。

`state.foodLevel` 范围 0–20，缺省 20，用于远端 YSM 条件动画。脚本物理只影响模型绘制，不修改真实玩家碰撞或坐标。

## 私人模型同步 v1

私人状态协议不增加原版未下发给普通观看者的食物、经验、完整效果等级或移动输入字段；远端使用既有缺省／原版实体字段。本人本地读取真实原版状态；XYZ 始终复用原版同步。授权资产、作者配置及动作事件仍按以下协议传送。

独立频道 `meplayeractions:private`，每包严格 UTF-8 JSON `{protocol:1,type:...}`。该频道由 `PrivateModelSyncService` 实现，服务类不引用 ModelEngine，不读取 ME 或管理员服务器模型路径，也不建立 HTTP/云存储连接。它只经 `PrivateModelStore` 读取已验证的 owner/hash 私人资源缓存。它与 v3 服务器主动推送共享连接和全局流量、并发预算，两个握手各自保留一秒冷却。现有 v3 的能力、授权和服务器 BBModel 主动推送继续有效，私人频道不能索取任意服务器模型，也不创建 ME 蓝图或伪装。

服务器须开启 `client-sync.enabled` 和 `client-sync.private-models.enabled`，并授予发布者 `mact.private.upload`、观看者 `mact.private.view`；私人开关与两个权限默认关闭，OP 也须显式授权。客户端须显式选择分享，选择模型本身不上传；关闭分享发送 `clear`，本机外观继续独立使用。

服务器伪装拥有显示优先级。发布者存在服务器伪装时拒绝新上传，并取消私人观看者 offer／租约、停止分发资产、配置和同步事件。当前客户端同时暂停私人外观、发送清理并停止发布；服务器伪装解除后不会自动重启分享，须用户显式重新使用／上传。服务端仍为旧客户端保留独立租约机制，缓存目录可在服务器伪装期间同步或管理，但不授予发布权限。私人远端渲染只对应观看者已跟踪的真实 PlayerEntity，不为私人模型调用 v3 `render_ready`。

### 独立协商与上传

客户端发送 `hello {capabilities:["private_models_v1","private_upload_catalog_v1","private_upload_credit_v1"]}`；服务端 `hello_ack` 返回 `capabilities`、`allowedUpload`、`allowedView`、`maxPayload`、`maxBundleBytes`、`heartbeatTicks:20`、`leaseTicks:100`。目录与上传额度各需双方确认；严格旧服务器拒绝扩展时，依次去掉额度能力、再去掉目录能力，每级最多一次，不循环切换。`maxBundleBytes` 仍按归档／分片容量约束协商，默认 8192 字节分片允许完整 8 MiB，maxPayload=1024 时有效容量为 422400 字节；客户端必须按 ACK 预检，超过容量继续仅在本机使用。新服务器可提供 `transferTotalTicks`、`transferIdleTicks`、`validationTimeoutTicks`，客户端按 50 ms/tick 折算总上传、分片无进展与已发送 end 后的校验等待，避免用接收空闲误判后台校验；缺省总等待为 90 秒。能力握手成功不等于取得发布/观看权限，须检查两个 allowed 字段。未知字段、重复 JSON 键、类型强制转换、非有限数、深度超过 64、尾随内容和非规范 UUID/hash 均拒绝。

客户端明确选择同步后发送：

```json
{"protocol":1,"type":"upload_offer","generation":"00000000-0000-0000-0000-000000000001","hash":"0000000000000000000000000000000000000000000000000000000000000000","bytes":123456,"kind":"ysm","appearance":{"scale":1,"offsetX":0,"offsetY":0,"offsetZ":0,"textureId":"default","variables":{"variable.example":1},"radioSelections":{"衣服":0}}}
```

generation 为客户端新生成的标准小写 UUID，每次新模型发布使用新代次；hash 是整个完整 ZIP 原始字节的 SHA-256 小写 64 位 hex。server 先在同一 owner 的私人磁盘缓存中查验 hash、kind、长度与完整资源；合法命中可直接返回 `upload_committed {generation,hash}`，不要求再次发送字节。未命中或缓存无效时签发 `upload_accept {uploadId,generation,hash,chunkBytes}`，客户端按连续 index 从 0 发送 `upload_chunk {uploadId,index,data}`，data 为标准 Base64 编码的 ZIP 字节，最后发送 `upload_end {uploadId}`。不叠加 GZIP。每片 `min(8192,floor((maxPayload-512)*3/4))`，1024 字节 maxPayload 时为 384；建议客户端每 tick 至多发送一个片段，避免触及两个频道共享的入站预算。

确认 `private_upload_credit_v1` 时，`upload_accept` 不授予分片发送额度。服务器先预扣单连接及全局入站包／字节预算，再发送 `upload_credit {uploadId,nextIndex}`；`nextIndex` 是可发送连续下标的**排他上界**，最多领先接收进度 2 片。客户端只能发送 `index < nextIndex` 的片段，重复或旧 grant 不回退进度、不重复发送。额度保守按 maxPayload 预留，实际收包消费对应一次信用；普通控制包仍独立扣预算。授予时预算不足延后发送，保护状态停止新 grant；unused credit 清理不会返还流量额度或重置窗口。未协商的旧客户端仍每 tick 至多一片，超出共享入站预算可能被忽略并最终超时，不能绕过全局上限。

publisher 未收到 accept／committed 时按一秒间隔重试同一 upload_offer；发完分片后保留 uploadId，按一秒间隔重试 upload_end 直到 committed。相同 upload_accept 的 uploadId／chunkBytes 不重置已发送下标。server 对在途同 generation/hash/bytes/kind offer 幂等处理，可重发 accept，但不重置接收下标、不另启缓存／资源校验作业、不延长原始 deadline。客户端采用 ACK 协商的上传时限；可重试的失败按 retryAfter 退避，同一来源自动失败最多 3 次、总自动重试窗口最多 5 分钟，随后须用户调整模型或重新开启分享。不可重试失败直接暂停该来源的自动发布。

服务端要求实际长度与声明完全一致，验证 SHA-256，并异步进行 ZIP/资源有界检查；成功返回 `upload_committed {generation,hash}`。收到 accept、发完分片或本地缓存命中均不等于发布成功。缓存查验与上传保留全服最多两份私人接收／验证作业、单连接一份和私人内存预留，并另外计入共享后台队列与字节预算；同连接新上传至少相隔 200 tick，冷却不因重新 hello 重置。接收总时限默认 2400 tick、空闲 300 tick，仅在接收分片阶段核对上传空闲；后台排队、无进展和总时限独立配置并按单调时钟执行。校验中的作业即使被新握手取消，也继续占用预留直到实际工作线程退出，不能通过换代次绕过预算。连接断开、owner clear、发布者失去权限或租约到期撤销当前发布，撤权错误不可自动重试，已验证磁盘缓存按独立预算保留。

`upload_committed` 的出站预算不足时保留待发确认；重发当前已发布的同 generation/hash/bytes/kind offer 可重新确认，不重复发布或重置代次。同 generation 改写资源身份返回 `private_generation_reused`。任何确认仍须当前连接、发布身份及上传权限合法。

### 本人上传存档目录与删除

双方确认 `private_upload_catalog_v1` 后，`upload_offer` 增加本机来源 `modelId`，用于存档关联；它不是服务器模型 ID 或文件路径。服务端验证完整资源后写入同 owner/hash 的资源和有界元数据，离线不恢复 publication。`upload_committed` 表示当次分享成功；“已上传”还须服务器存档目录确认，不能从本机发送成功或历史会话状态推断。

S2C `upload_catalog {revision,index,count,available,models:[{modelId,hash,kind,bytes}]}` 主动同步本人存档。目录最多 64 个来源、按当前 owner 预算裁剪，同来源只展示最新有效版本；`count` 为分片数。整份一致分片接收完成后才替换目录，更高版本接收中暂停旧目录管理权限。`available` 需要当前上传权限和启用磁盘缓存，撤权时同步不可用的空目录。服务端共用后台目录扫描和每会话有界分片发送；不为每个玩家反复读 ZIP。

客户端保留目录中的全部条目，包括本机源文件缺失或变化的条目。使用／再次分享还需本机完整 bundle 的 hash、kind、长度与记录一致；校验在既有有界后台工作线程执行，不能因完成的 future 而在 UI 线程解包。原文件缺失仍可查看和删除，目录本身不授予下载或渲染权。

ACK 在协商目录扩展时还提供本次握手 UUID `uploadCatalogToken`。C2S `upload_delete {requestId,catalogToken,requestSequence,modelId,hash,kind,bytes}` 仅能删除本人当前精确目录身份；`requestSequence` 为本握手内递增整数 `1–9007199254740991`，重试保留全部字段。服务端检查当次 token、权限、存储开关、当前条目和在途作业，异步删除该来源的已验证存档及旧版本，保留其他模型、其他 owner 和本机文件。相同来源上传与删除互斥，防止后台回写复活已删条目。

成功 S2C `upload_deleted {requestId,modelId,hash}`，失败 `upload_delete_failed {requestId,code}`。确认弹窗捕获打开时的完整目录条目，点击确认前身份变化须重新确认。客户端等待精确结果，不能在请求发送时显示成功；成功更新目录并结束该来源的相关私人分享。`private_delete_busy` 可表示该来源仍在上传、删除或后台存档校验中，完成后可重试。

首次发送前撤权取消未发出的删除；已发送请求最多等待 90 秒，超时显示“未确认”并释放按钮，保留最多 16 条晚结果关联。晚 ACK 只处理原请求与原发布代次，不能关闭后来重新分享的实例或删除较新目录记录。服务端结果使用有界幂等历史及预算重试；历史被逐出的旧序号仍不能再次删除重建条目，重新握手换 token 拒绝旧连接重放。删除不转换为任意服务器资产请求，也不执行服务器伪装命令。

### 完整原生资源 bundle

ZIP 根必须含 `manifest.json`，最大 4096 字节，且只有 `{format:1,kind:"bbmodel"|"ysm",entry:"model.bbmodel"|"ysm.json"}` 三个字段。BBModel 内保留原完整 `model.bbmodel`、内嵌 PNG 和声明的包内 PNG 伴随资源；观看端与发布者走同一固定 Sparkle 导入／装配链。YSM 保留 `ysm.json`、原始 geometry、controller、animations、functions、lang、sounds、纹理及必要引用资源，不能只上传转换后的主 BBModel 丢失 profile/components/作者函数。公开原格式 `.ysm` 由客户端读入后导出完整原生资源；wire 不传未验证的 opaque 二进制。`ysm.json.mpa_native_format` 保留原格式代次，`ysm_baked_faces` 保存公开原文件的面几何，服务器只验证资源，不解释动画脚本。

ZIP 原始上限与所有条目展开总量各 8 MiB，扫描条目（含目录）最多 256；可由服务器进一步缩小 ZIP 原始上限及 `resource-protection.complexity` 的各项预算。只允许 json/bbmodel/png/bmp/jpg/jpeg/webp/ogg/molang 文件。拒绝 absolute/path traversal/点段/空段/反斜杠/冒号/控制符、casefold 重名、符号链接、加密、分卷和 ZIP64，逐条目检查 CRC，目录不得携带数据，不落盘解压。JSON 节点最多 200000、深度最多 64，重复字段和非有限数拒绝；图片以格式头检查维度（最多 8192）与全部外部纹理总像素（最多 16777216），服务器不执行图片解码器；BBModel 最大 4096 elements、16 项纹理声明；内嵌 PNG 与包内伴随纹理的像素按联合预算校验。整体默认预算还有骨骼 4096、动画 1024、关键帧 65536、表达式 32768 条／2097152 个字符；多个文档与 molang 文件合计核对，不能靠拆文件绕过。伴随 PNG 按固定来源的 name／name+.png／relative_path 文件名匹配，不访问编辑器记录的绝对 path，重名歧义或缺失资源拒绝。ogg 检查 OggS 头，molang 检查 UTF-8。模型资源字段中的 HTTP/file URL 拒绝。客户端还必须独立完成 bundle、模型解析、图片解码和 GPU 校验，服务器接收不能替代本地验证。原始传输与展开仍受 8 MiB 约束；BBModel mesh／曲线转换派生资产独立限 64 MiB，不扩大网络容量。

默认全服务器内存资源预算 32 MiB，包含已发布 ZIP、接收中的 ZIP及每个校验作业预留的最多 8 MiB 展开资源。私人磁盘缓存默认启用，目录 `plugins/MEPlayerActions/private-models/`，扁平文件名 `<ownerUUID>_<hash>.zip`；默认最多 128 MiB、512 条、同 owner 4 条，后台访问时按最近使用裁剪。设置为 `client-sync.private-models.cache-enabled`、`max-cache-bytes`、`max-cache-models`、`max-cache-models-per-player`。设 cache-enabled=false 不读写既有缓存。异步写入只接收整份校验成功的 bundle，cache 命中重新验证后才发布；缓存不可作为跨 owner 模型查询入口。文件保留不恢复此前的 publication、generation、offer 或租约。服务端不把客户传来的字符串作为服务器模型 ID、任意文件路径或 URL。

### 观看者主动 offer 与租约

服务器只向同世界、`canSee(owner)`、真实实体 `owner.getTrackedBy()`、权限、严格距离和最近观看者名额均满足的已握手模组玩家分发。不向非模组玩家发资源或隐藏其原版人物。默认距离 64 格、最近其他观看者 10 人，本人不占名额也不重复下载自己的模型。

服务端按 publisher UUID 与当前 generation 缓存最近观看者集合，首次发布立即发现，之后默认每 40 tick 错峰刷新；已有关系每 20 tick 轻检。相关追踪／可见性事件立即撤销对应 offer 并使 owner 缓存失效，publication 更换／移除、策略变更、停用或发现间隔变化也会清理。活跃资产队列每 tick 推进，每片发送前实时检查授权；配置和事件直接转发缓存中仍合法的观看者。禁用或无 publication 时跳过重维护，不读取伪装 supplier。该缓存只复用观众身份，不授予额外观看权限或共享动画状态。

S2C `private_offer {owner,generation,hash,kind,offerId,bytes,sequence,appearance,extra}` 是当次观看授权。客户端只针对该 token 返回 `private_status {offerId,hash,status:"cached"|"missing"|"rejected"}`，不能指定其他 owner/模型。missing 后服务器发送 `asset_begin {offerId,hash,bytes,chunks}`、连续 `asset_chunk {offerId,hash,index,data}`、`asset_end {offerId,hash}`。每连接每次至多两个未完成 offer；它们继续使用 v3 的并发/总带宽上限和同 hash/代次最多三次的尝试预算。每个流每 tick 最多两个片段。

`cached`/`missing` 只接受初始 OFFERED 阶段；仍获授权且 offerId/hash 精确匹配的 `rejected` 可在缓存、下载中、下载完成或 READY 后撤销，服务器释放传输槽并发送精确 `private_remove`。同一观看连接不再推送被拒绝的 owner/generation，直到该 owner 发布新 generation 或客户端重新握手；活动 offer 与拒绝记录合计最多 64 项，不驱逐仍有效的拒绝记录来重试失败模型。

客户端保留当次 offerId/hash/status，不能把本机 send=true 当作服务器收到确认。missing 反馈按一秒间隔重试直到精确 asset_begin；cached 反馈与 private_ready 按一秒间隔重试直到精确 private_ack。完成资源准备后先发送反馈，再发送 ready；不提前绘制、不创建新绑定或重放动作。

private_status、private_ready、upload_offer、upload_end 共用客户端控制预算：滚动一秒最多 16 包，令牌桶初始／突发 8 包、每秒恢复 16 个；各控制身份的重试间隔仍至少一秒。预算延后不扣重试时间、不撤身份，pending 观看者按 tick 轮转。这只限制上述控制包，upload_chunk 仍每 tick 最多一片；服务端两频道合计 48 包／秒入站上限不变。

客户端完成缓存 hash 验证、bundle/model/GPU准备后发送 `private_ready {owner,generation,hash}`，收到精确 `private_ack` 后才允许远端私人绘制。此 ACK 仅是私人显示租约，不调用 ME 可见性接管。每 20 tick 发送 `private_heartbeat {bindings:[{owner,generation,hash}]}`；自己的 upload_committed 身份也必须放进 bindings 续期发布，即使没有其他观众。服务端只确认实际被续期的精确合法身份：`heartbeat {bindings:[...]}`，空数组仍表示频道存活。heartbeat 可额外带布尔 `allowedUpload`、`allowedView`，实时更新当前连接权限；缺省保持原协商权限，收到撤权后撤掉对应发布或远端显示。非法、过期、失去跟踪/权限、被服务器伪装遮盖的观看身份不会被 ACK。

publisher 与 ready viewer 租约均 100 tick。模型改变、owner清理、离线、许可变化、失去跟踪、服务器伪装优先或租约到期会发送 `private_remove {owner,generation,hash,reason}`；客户端须按精确代次撤销绘制，资源缓存本身不授予显示权。私人渲染不维护幽灵实体，也不改变非模组玩家的显示。

### 配置、轮盘动作与作者同步事件

`appearance` 是完整状态：scale 默认 1、范围 0.05–8；offsetX/Y/Z 默认 0、范围 ±32；textureId 默认空、最长 128 字符；variables/radioSelections 各最多 128 条。variables 键必须已为 Locale.ROOT lowercase 的 canonical `variable.*`，全名最长 128，每个点分段以 Unicode 字母或下划线开头，后续只允许 Unicode 字母、数字或下划线，值有限且绝对值 ≤1000000。radio 键允许安全 Unicode，最长 128、无控制符，wire 值为整数 0–255；本地表单最多 64 个选项。radio 键统一 Locale.ROOT lowercase，casefold 重名拒绝。作者配置中明确关联的 `variable.roaming.*` 可以同步，最多 64 条且该前缀后的名称为单个标识符、最长 32 字符，不允许继续点分段；客户端只发布持久模型配置，不发布逐帧物理等运行时变量，服务器对传入的数值配置统一执行上述边界校验。appearance+extra 的 UTF-8 JSON 总长度最多 maxPayload−768，为身份封装保留空间。

owner 可以发送 `private_state {generation,hash,appearance,extra:{id,loop,locked,sequence}}`。extra.id 为空或 1–128 字符作者动画名，loop 为 ONCE/LOOP/HOLD，locked 为 boolean，extra.sequence 为 0–9007199254740991 的本机单调动作代次；同一 id 的新 sequence 表示重新播放。客户端状态改变后发送，并每两秒重发当前完整 state，恢复被入站预算丢弃的变更；重发保留 extra.sequence。server 拒绝 extra.sequence 回退，对规范化后相同 appearance+extra 只续发布者租约，不递增 sequence、不重复广播或重启动作。状态改变时保留完整配置并向当前 ready 合法观看者发送 `private_state {owner,generation,hash,sequence,appearance,extra}`，顶层 sequence 为服务器递增的配置序号。出站拥堵时按 owner 合并最新完整配置并维护重试，发送前重新确认当次 generation、hash、租约与资格；不堆积旧动作状态或重放作者事件。本人仍立即使用本机 UI 选择，不等待远端回包来应用按钮。

成熟作者 `ysm.sync` 使用独立 `private_event {generation,hash,args:[...]}`，最多 16 个有限数。server 必须匹配本连接的已接收私人发布，随后返回带 owner/generation/hash/独立事件 sequence 的同名 private_event 给 publisher 和当前 ready 合法观看者。联网 listener 生效时作者 sync 是 relay-only，本机也只在服务器回显时执行一次 @sync，收包执行不会再次发送。服务器不执行函数、动画或脚本，不允许为其他 owner 发送事件。owner被服务器伪装遮盖时不转发事件。

每连接配置更新与作者事件分别最多 8 次/秒，窗口不因重新 hello 重置；仍计入两个频道共用的连接及全局网络预算。上传、私人下载、v3 下载共享活动／等待传输槽，当前默认值与配置化额度见上面的[连接预算](#连接预算)。超出预算的控制状态可能延后或触发租约恢复，接收到的资源无法因解绑撤回，权限检查授予观看使用权而不构成 DRM。

### 服务器确认的飞行状态

S2C `private_offer` 与 `private_state` 可包含 `state:{flying:boolean}`。服务器从当前发布者的 `Player.isFlying()` 读取，只在变化时递增状态 sequence 并通知获准 READY 观看者，遍历活动 publication，不额外扫描全部在线玩家。同包的 appearance 和 extra 保持原语义，extra.sequence 不变也必须更新 flying。受预算延后的状态合并为最新值，资源磁盘缓存不保存运动状态。

该状态只影响远端 `ctrl.fly`／`query.is_jumping` 及动作选择，不携带或修改玩家坐标。客户端不能在上传或续租中声明 state／flying，服务器拒绝这些额外字段。服务端未提供该字段时，客户端默认 false。原版玩家位置由 Minecraft 同步。
