# 客户端模型协议 v2

频道 `meplayeractions:main`，严格 UTF-8 JSON，每包带整数 `protocol:2`、字符串 `type`。不兼容 v1。所有消息保持分包顺序；不允许重复键、额外字段、类型强制转换、非法 UTF-8、尾随内容。入包受 maxPayload（默认 16000 字节）和每观众每秒 48 包限制。客户端请求只操作自己，仍经过插件命令权限校验。

## 握手与状态

客户端发送：
```json
{"protocol":2,"type":"hello","clientVersion":"0.1.0","capabilities":["local_render"]}
```
`capabilities` 必须含唯一的 `local_render`；`clientVersion` 可省略（最多 64 字符）。hello 每秒最多处理一次。新 hello 清理旧绑定并恢复旧 ME 可见性，不授予渲染权限。

返回 `hello_ack`：`mode:"local-render"`、`serverTick`、`heartbeatTicks:20`、`leaseTicks:100`、`maxPayload`、`requestCooldownTicks`、`capabilities:["local_render"]`。随后发送 `snapshot_begin {snapshotId,serverTick}`、可见状态、`snapshot_end {snapshotId}`；手动 `snapshot_request` 也会生成此快照边界。后续每 2 tick 发送可见实例的完整 state，动作切换可即时发送。相同 sequence 的 transform 仍可能变化，客户端不能按 sequence 丢弃同序号位置包。

```json
{"protocol":2,"type":"state","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","modelId":"ysm_01_jk_player","assetHash":"小写SHA256","sequence":12,"serverTick":12345,"world":"00000000-0000-0000-0000-000000000003","x":1.0,"y":64.0,"z":2.0,"bodyYaw":90.0,"headYaw":100.0,"headPitch":10.0,"scale":1.5,"hidePlayer":true,"showSelf":true,"layers":[{"layer":"posture","animation":"crawl_idle","startedAtTick":12340,"speed":1.0,"loop":"LOOP","inTicks":2,"outTicks":2}],"animations":[{"id":"wave","label":"挥手"}]}
```

`instance` 是伪装生命周期 UUID，模型更换生成新值。UUID 必须是标准 36 字符小写形式。`sequence` 是动画变化序号；`serverTick` 使用 Paper 当前 tick 的 unsigned 32 位原点。位置和 body/head yaw/pitch 来自服务器同一份 visual-follow.delay 历史帧，客户端只做帧间平滑，不再次加相同延迟。通常位置为世界脚底；原生 BED_SLEEP 的锚点是实际 Bed 两半几何中心、床块 Y+0.5625，bodyYaw 朝床头，headYaw=bodyYaw、headPitch=0。GSit 姿态使用公开 Seat.location + SitService.baseOffset 的接触面和 Seat.yaw，包含 GSit 配置的方块高度/偏移；GSit SLEEP 的 headYaw=bodyYaw、headPitch=0，不能因虚拟睡姿或床包被当作原生 BED_SLEEP，也不能使用其被坐骑下移的 rawPlayer Y 作为地面。醒来后仍在延迟的床/GSit 帧持有原接触面与方向，不读取已消失的实时姿态。yaw/pitch 以度表示，scale 是正数均匀缩放。模型 crawl/bed_sleep 动画自身已含卧倒 Root，客户端不能再次套用原生 SWIMMING 或睡觉姿态的整模型旋转。

`layers` 是完整集合：缺席的层应退出；名称 posture / interaction / manual，优先级依次 100 / 150 / 200。posture/manual 覆盖有关键帧的骨骼，interaction 叠加；in/outTicks 是 20 TPS 的过渡时长。loop 为 ONCE / LOOP / HOLD，startedAtTick 用于从服务器时间同步动画进度。animations 是可发送 play 请求的动作中文菜单目录：有模型目标动画的 custom-actions 使用动作别名 ID 和 label；仅 manual.allow-raw-animation=true 时补充符合服务器命令 ID（1–64 位小写英文、数字、_、-）的原始动画。同 ID 的自定义动作优先，目标缺失的别名也不会回退为不可执行的原始动作，目录去重并按 ID 排序。label 去除 ISOControl 后最多 64 个 Unicode code point。若 state 超出 maxPayload，先去掉目录并发送 animations:[]；位置与动作层仍超限时解绑并保持 ME，日志按观众/owner 去重。hidePlayer 控制真实人物渲染；showSelf 控制本人可见性。服务器尊重同世界、Player.canSee、disguise.view-distance 的严格三维距离限制、max-viewers 最近观众上限和 show-self，客户端不能据状态突破可见性。

只有本插件创建、实体没有外来模型且有可分发资产的实例提供非空 assetHash。接管原生 ME、外来模型共存、资产失败时 hash 为空，保持 ME 渲染，不允许 render_ready。

## 资产传输

资产是原始 .bbmodel JSON（含 elements/outliner/animations 与内嵌 PNG），以 GZIP 压缩传输，hash 为**原始 JSON 字节** SHA-256 小写 64 位十六进制，不是 gzip hash。原始上限 8 MiB，压缩上限 4 MiB。客户端依据 hash 缓存；无需每次重新下载。

```json
{"protocol":2,"type":"asset_request","modelId":"ysm_01_jk_player","hash":"小写SHA256"}
```

modelId 仅允许 1–64 位小写英文字母/数字/_/-；hash 必须匹配当前可见绑定。服务器资产查找：`plugins/MEPlayerActions/models/<id>.bbmodel` → `plugins/ModelEngine/blueprints/` 内文件名或 model_identifier → JAR `models/<id>.bbmodel`。资产在本服务生命周期缓存，更新后 reload。

服务端顺序发送：
- `asset_begin {modelId,hash,rawBytes,compressedBytes,chunks}`
- `asset_chunk {hash,index,data}`：index 从 0 连续递增，data 是 gzip 分片标准 Base64。
- `asset_end {hash}`

每片最多 9000 gzip 字节（小 maxPayload 会进一步缩小，1024 字节负载时每片 384 gzip 字节），客户端分片上限 16384；每观众每 tick 最多 2 个 chunk，最多 2 个排队传输。重复在途请求忽略，同 hash 完成后 100 tick 内请求受冷却。离开范围/实例解绑且无人需要该 hash 时取消传输。客户端检查声明长度、分片连续性、压缩与解压上限、完整 SHA256，模型解析及纹理 GPU 注册成功后才可 ready；资产失败不隐藏 ME。

## 就绪、确认与恢复

客户端就绪：
```json
{"protocol":2,"type":"render_ready","owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}
```
服务器再次验证当前 owner/instance/hash、可见性、owned 且无外来模型，然后只对该观众抑制 ME 显示，返回 `render_ack {owner,instance,hash}`。客户端只有匹配本地待确认实例的 ACK 到达后才开启本地绘制。hello 和 state 本身均不切换 ME。就绪客户端仍占用原来的观众名额，不额外增加 max-viewers。

客户端每 20 tick 发送：
```json
{"protocol":2,"type":"render_heartbeat","bindings":[{"owner":"00000000-0000-0000-0000-000000000001","instance":"00000000-0000-0000-0000-000000000002","hash":"小写SHA256"}]}
```
bindings 最多 64 项，owner 唯一。只续租已有且 instance/hash 完全相符的就绪项；未列出的项不立即移除，但到 100 tick 无续租时恢复 ME。旧实例心跳不能续新实例。GPU/绘制失败立即发送 `render_failed {owner,instance,hash}`，停止本地绘制，服务器恢复该观众的 ME。失败 hash 应使用客户端退避，避免无限重试。

服务端 `heartbeat {serverTick,leaseTicks:100}` 每 20 tick 刷新客户端连接租约。客户端使用单调时钟按名义 20 TPS（5 秒）检查服务租约，世界暂停或卡顿不能无限保留绑定；断开、世界切换立即清理。`unbind {owner,instance,reason}` 只清理匹配实例；服务器解绑、观众离开、超时、断开、禁用、重载、新 hello 都恢复 ME。Bukkit onDisable 之后可能拒发 unbind，客户端超时清理是必要的。

## 动作请求与错误

```json
{"protocol":2,"type":"request","action":"play","argument":"wave"}
{"protocol":2,"type":"request","action":"reset"}
```
支持 play / stop / sit / crawl / reset。play 需要 1–128 位英文字母、数字、_ . : / - 的 argument，其他动作无参数。原始模型/动作命令校验与权限仍以服务器为准；不能携带 owner/target/command 或改写他人状态。play/sit/crawl 与手动快照共享请求冷却；stop/reset 即刻执行且不占用冷却，仍受每秒流量限制。资产/ready/续租独立于动作冷却。

`error {code}` 向已握手观众反馈：invalid_payload、unsupported_protocol、request_cooldown、action_failed、asset_not_authorized、asset_cooldown、asset_queue_full、asset_unavailable、render_not_authorized、render_unavailable。渲染拒绝的 error 会额外携带 owner/instance/hash，客户端据精确绑定退避。未知协议结束该观众会话并恢复 ME。
