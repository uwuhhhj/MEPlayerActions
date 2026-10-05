# 客户端 0.4.6 私人 YSM 动画与诊断

[安装和使用](../../README.md) · [客户端配置](../CLIENT_CONFIG.md) · [兼容范围](../YSM_COMPATIBILITY.md) · [0.4.5 轮盘解除](CLIENT_0_4_5_WHEEL.md)

本次为 Minecraft 1.21.11／Fabric／Java 21 的客户端更新。退出游戏，移除该实例旧版 MEPlayerActions 客户端 JAR，放入 `MEPlayerActions-Client-0.4.6.jar`；保留私人模型和现有配置。服务器继续使用已发布的 0.4.3 插件，服务器配置、模型推送协议和资源包流程无需调整。

0.4.6 包含 0.4.5 的 J 轮盘左侧解除入口：SERVER 请求原 `/meplayeractions undisguise` 并等待服务器确认，CLIENT 仅停用私人外观并保留参数；本人服务器伪装仍存在时切回 SERVER。轮盘中心仍用于停止动作／本地动作锁定。

## 时间字段的来源与含义

固定 [OpenYSM-Updated 0306e1f](https://github.com/IzumiiKonata/OpenYSM-Updated/tree/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85) 和早期 [ModernYSM a515d44](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658) 均未读取或执行以下时间字段。本次按用户指定的后备来源，采用 [Minecraft 官方动画定义](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/actor_animation.v1.8.0?view=minecraft-bedrock-stable)，不将这部分称为上游代码迁移。

| 私人 YSM 动画字段 | 本次含义 |
| --- | --- |
| `anim_time_update` | 表达式返回新的绝对动画时间，单位为秒；默认按 `query.anim_time + query.delta_time` 推进。不是速度倍率字段。 |
| `animation_time_update` | 前述字段的兼容别名；同时定义两种名称会明确拒绝，避免歧义。 |
| `start_delay` | 激活或重新开始时求值一次，以秒表示起播等待；普通循环不重新计算。 |
| `loop_delay` | 仅 LOOP 在结束一圈后求值，等待后开始下一圈；ONCE／HOLD 不使用循环间隔。 |

时钟属于每个动画槽，身体、GUI、第一人称组件各自独立。同一时间重复采样不重复求值或派发事件；世界时间重置、取消和重新选择模型清理运行状态。表达式长度、计算预算、有限数和资源上限继续有效。普通服务器 BBModel 的时间字段解析与原服务器显示路线不在此次扩展范围。

每次采样最多处理一次循环边界，不补放已跳过的多圈事件；跨过循环等待后从 0 起播，不补算等待后的多余时间。负时间／负延迟归零。绝对时间可以倒退，但不倒放事件；之后向前重新经过事件位置时可再次触发。没有时间字段的原生动画保留上游 ONCE `>= length`、LOOP／HOLD `> length` 的边界；ONCE 从首次观察到结束的采样开始固定 3 tick 过渡。显式 STOP 对扩展绝对时钟冻结最后一次求值，对普通原生时钟冻结当时推进的时间。

## `ysm.defer` 的上游执行边界

`defer` 按固定 OpenYSM 的实际规则执行，不在每帧结束自动刷新。它在动画槽中捕获第二个参数起的实际值；第一个非空字符串 ID 只作上游兼容检查，不选择函数。ONCE 结束、LOOP 回卷或动画槽切换时，按捕获的逆序依次调用整个作者 `events.defer` 列表，并清除该槽的 `context/c` 存储；实体 `variable/v` 保留。HOLD 保持末帧期间不会自动刷新。

显式 STOP 保留捕获经过固定 3 tick 结束过渡，首次到达 IDLE 才执行。作者 `ctrl.reset` 取消动画但保留捕获／context；随后 CONTINUE／PAUSE 进入 IDLE 才执行，STOP 在 IDLE 时继续保留。世界／模型实例 reset 或 dispose 直接丢弃捕获，不触发旧世界特效。

队列与参数数量有界，回调共享现有表达式预算。当前骨骼查询仍返回向量快照：传整个 `ysm.bone_rot(...)` 等对象不会获得上游动态 IBone 引用；取 `.x` 等标量后捕获不受这一区别影响。不能据此宣称所有作者脚本与上游完全等价。

## 查询降级诊断

本地加载的 YSM profile 在身体、组件及 GUI 预览路径启用诊断；普通 BBModel 和默认服务器表达式上下文保留原行为。缺失查询／明确不可用查询的普通读取仍返回 0；私人 YSM 的 `??` 可使用作者实际默认值，且不可用信息在函数／事件作用域恢复后保留。非法函数、宿主操作或预算超限仍会明确拒绝。

`/mpaclient status` 在启用私人 YSM 时列出已发生的降级名称、原因、累计次数和最近实际默认值。世界／组件与 GUI 的只读诊断也包含 `queryFallbacks`。每个表达式实例最多记录 128 个不同名字，不逐帧刷聊天；没有记录表示尚未触发对应读取，不代表所有查询均已实现。重置实例时清空记录。

## 私人 YSM 与多人同步

当前客户端没有实现 OpenYSM 的私人模型／作者状态多人分发协议，也没有协商到该协议的处理能力。MPA 的授权 BBModel 推送和动画绑定是另一条已存在的服务器伪装路线；本地 `ysm.sync` 仍只分发当前实例的 `@sync` 事件。

远程多人世界成功应用私人 YSM 后，聊天和模型设置提示完整显示：

> 当前服务器没有相关插件分发同步，请自行下载
>
> 当前客户端暂不支持私人 YSM 多人同步，模型与动作仅本机显示；不影响 MPA 服务器伪装同步。

判断依据为实际加载的 `profile.isYsm()` 和远程世界，不按模型 ID 猜测，也不把 MPA 握手或资产推送成功当作私人同步能力。单人／集成服务器（包括本机 LAN 主机）和普通 BBModel 不显示该提示。同一连接／模型去重，最多保留 32 个模型通知记录。下载模型不代表当前模组已能向其他观看者同步私人选择或作者状态。

加密 `.ysm`、网格绑定、完整动态骨骼对象、其他模组联动与私人多人协议仍见 [兼容范围](../YSM_COMPATIBILITY.md)，本次不宣称补齐这些能力。

## Sparkle-Morpher 参考核对

按用户补充，核对 [Sparkle-Morpher 固定 main b1230a4](https://github.com/sdf123098/Sparkle-Morpher/tree/b1230a431900a286d2cca198072df7fb43c490b4)。该版本配置为 Minecraft 1.21.1／Fabric／Java 21，代码采用 MIT；当前客户端为 1.21.11，绘制 API 不能原样套用。

该源码的原生 YSM 路径也没有执行三个时间字段；[BBModelParser](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/bbmodel/BBModelParser.java#L394-L427) 的 `anim_time_update` 仅作布尔元数据读取，没有运行时表达式执行，因此继续使用上述官方定义。其 [AnimationControllerInstance](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/geckolib3/core/controller/AnimationControllerInstance.java#L125-L189) 的 3 tick 结束和 defer 边界与本次采用的 OpenYSM 基线一致。

[统一轮盘](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/core/gui/UnifiedRouletteScreen.java) 和 [模型决策](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/core/model/selection/EntityModelResolver.java) 可继续作为界面、异步切换和缓存管理的参考。本次仅核对源码，未移植其云端体系。

其 README 的资源同步描述与当前 main 有差异：[C2SModelSyncPayload](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/network/message/C2SModelSyncPayload.java#L27-L30) handler 已为空，[NetworkHandler](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/network/NetworkHandler.java#L66-L71) 也未注册模型字节分发。不能据 README 宣称已获得可迁移的服务器主动推送实现；本项目保留 MPA 授权后主动推送模型的设计。

## 本次验证范围

本次运行新增语义及受影响路径的定向单元测试、客户端编译和安装包内容／完整性校验，具体选择和结果随交付 `build.json` 保存。按本对话要求，不启动 Minecraft、服务器或 A/B 实机场景。0.4.4、0.4.5 的界面与画面仍由用户实机验证；历史报告和截图不作为本版实机通过证明。
