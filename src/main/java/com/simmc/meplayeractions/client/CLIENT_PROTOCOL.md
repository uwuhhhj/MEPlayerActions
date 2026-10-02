# 客户端动作同步协议预留 v1

频道 `mact:main`；UTF-8 严格 JSON；每条消息必须有整数 `protocol: 1` 与字符串 `type`。本阶段只同步状态，模式始终为 **state-sync-only**：不提供 geometry / animation clip 导出、不接受 render-ready，也不隐藏 ModelEngine。普通客户端继续正常看到 ME 模型。

## 握手与请求

客户端先发送以下包；`clientVersion` 和 `capabilities` 可省略，当前唯一能力为 `state_sync`：

```json
{"protocol":1,"type":"hello","clientVersion":"0.1.0","capabilities":["state_sync"]}
```

服务器返回 `hello_ack`，含 `mode`、`serverTick`、`heartbeatTicks:100`、`leaseTicks:400`、实际 `maxPayload`、`requestCooldownTicks`、`capabilities:["state_sync"]`；随后发送全量快照。重复 hello 每秒最多处理一次。仅注册频道不构成握手，未握手玩家不会收到本服务任何包。未知协议不会建立会话；已握手后发现未知协议将结束会话。

```json
{"protocol":1,"type":"request","action":"play","argument":"wave"}
{"protocol":1,"type":"request","action":"sit"}
{"protocol":1,"type":"snapshot_request"}
```

`action` 只允许 `play`、`stop`、`sit`、`crawl`、`reset`。`play` 必须带 1–128 字符的动画名，允许英文字母、数字、`_ . : / -`；其余动作的 argument 必须省略或为空字符串。不能带目标玩家、命令、额外字段、重复字段、null、嵌套对象或非法 UTF-8。输入类型必须匹配，不将数字、布尔值转换为字符串。

上述为频道载荷的语法边界；本插件的统一动作入口进一步将模型与动作 ID 限制为 1–64 位小写英文、数字、`_`、`-`，并执行模型白名单与权限检查。

动作请求始终作用于发包玩家本人，再交给插件统一执行权限、模型动作存在性及真实玩法校验。协议握手不授予权限。请求与手动快照请求共享 tick 冷却；另限制每玩家每秒最多 32 个可处理包。过大包直接丢弃，本插件 maxPayload 配置范围为 1024–30000 字节。

## 服务端状态

`snapshot_begin` 与 `snapshot_end` 含相同 `snapshotId`；中间是当前可观察模型的 `state`，以及旧绑定的 `unbind`。一个 state 代表该实例的**完整动作层集合**：未列出的层应停止，`layers:[]` 表示所有手动 / 自动同步层停止。

```json
{"protocol":1,"type":"state","owner":"玩家UUID","instance":"实例UUID","modelId":"ysm_01_jk_player","sequence":12,"serverTick":12345,"layers":[{"layer":"manual","animation":"wave","startedAtTick":12340,"speed":1.35,"loop":"ONCE","inTicks":2,"outTicks":2}]}
```

UUID、modelId、sequence、serverTick 与动作层全部来自服务器。当前层名为 `posture`、`interaction`（0.2.0 新增的挥臂/挖掘层）、`manual`；这些是本插件控制的覆盖动画，ME 自身基础层未导出。默认优先级依次为 100、150、200；interaction 使用关键帧叠加，manual 优先。未来客户端应容忍未知层名，并按完整集合处理停止，避免丢失新增层。instance 在模型实例更换时变化；sequence 是同一实例动作状态的序号。动作开始 tick 和消息当前 tick 供未来客户端建立时间基准；服务器控制器使用 `Bukkit.getCurrentTick()` 的原点（转换为 unsigned long）。`loop` 由服务器控制器输出，例如 `ONCE` / `LOOP` / `HOLD`，客户端本阶段仅记录，不自行启动本地渲染。

0.2.1 的视觉位置拖后由 ME 渲染变换处理；当前协议不导出历史位置或视觉偏移。动作层记录的是服务器实际应用动画的 tick，包含配置造成的切换延迟。未来实现本地渲染时须增加位置时间线信息，不能仅靠现有动作包复现视觉拖后。

同步只发给已经握手、与模型所有者同世界且位于协议配置范围内的玩家，并尊重 `Player.canSee`。0.2.5 本插件创建的模型还须满足本次伪装的 `show-self`、`view-distance`、`max-viewers` 观众筛选；本人状态也受 `show-self` 控制。观众名单变化会立即广播新状态或解绑，原生接管保留原有可见性。不会全服广播全部模型目录或远处玩家状态。

服务每 10 tick 核对范围和实例；只有新进入范围、实例变化或 sequence 变化时补发状态。动作改变可即时 broadcast。显式全量快照、握手会强制重发可见状态，不会每 tick 全量同步。

```json
{"protocol":1,"type":"unbind","owner":"玩家UUID","instance":"实例UUID","reason":"instance_changed"}
{"protocol":1,"type":"heartbeat","serverTick":12400,"leaseTicks":400}
{"protocol":1,"type":"error","code":"request_cooldown"}
```

unbind 只清除对应 owner / instance，防止延迟包清除较新的实例。离开范围、模型移除、会话结束和插件关闭会清理绑定。error 只向已握手玩家发送，常见 code 为 invalid_payload、unsupported_protocol、request_cooldown、action_failed；权限拒绝由插件的动作入口处理。

## 心跳与关闭

每 100 tick 发送一次 heartbeat，lease 为 400 tick。未来客户端应以名义 20 TPS 换算的本地经过时间检查租约：连续约 20 秒收不到有效服务端包时清空此频道绑定；断线时立即清空。不要用已暂停的客户端世界 tick 作为唯一超时计时器。

close 在插件仍 enabled 时尝试发送 unbind；Bukkit 不允许已经 disabled 的插件发送 plugin message，因此 onDisable 阶段不能保证 unbind 到达。发送失败会捕获，不影响取消任务、注销频道及清空会话。客户端租约和断线清理是后续本地渲染功能的必要条件。本轮 ME 始终可见，不存在协议握手造成的隐藏残留。

后续渲染桥接必须增加模型数据、版本与加载完成确认，以及按观察者控制 ME 显示；这些需要单独设计，不可把 hello 或 state_sync 等同于 render-ready。
