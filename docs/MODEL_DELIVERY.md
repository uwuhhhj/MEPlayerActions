# 模型同步与部署

[文档索引](README.md) · [快速安装](../README.md#快速安装) · [客户端配置](CLIENT_CONFIG.md) · [客户端协议](CLIENT_PROTOCOL.md)

当前服务端与客户端均为 **0.4.9**。服务器主动推送授权伪装的完整原模型；安装客户端模组的观看者完成校验与确认后接管渲染。未安装模组或尚未接管的观看者使用 ModelEngine 显示，被伪装者本人无需安装模组。

## 职责与部署方式

| 组件 | 职责 |
| --- | --- |
| ModelEngine | 导入蓝图、生成原版资源、向未接管的观看者显示模型 |
| CraftEngine 等现有资源包流程 | 合并、托管、保护与下发服务器资源包 |
| MEPlayerActions 服务端 | 管理伪装、动作、观看许可与绑定；主动推送已有完整原模型 |
| MEPlayerActions 客户端 | 校验资产、计算本地动画并接管授权模型；可独立使用私人外观 |

MPA 不生成或重打包 ME／CE 资源包。完整原模型含内嵌贴图，可能与服务器资源包中的贴图重复；当前推送路径尚未跨包复用材质。ME／CE 的资源包保护也不加密 MPA 推送的原模型，被授权接收者可以取得这份资产。

仅中继私人模型分享时，同一个服务端 JAR 可运行在 Paper 1.21.11／Java 21 上，无需 ModelEngine 或服务器资源包。客户端本地私人外观无需服务端；多人分享需双方主动启用并通过权限检查，与服务器伪装推送分开。

## 服务端配置

配置位于 `plugins/MEPlayerActions/config.yml`。首次安装可使用安装包默认配置；升级保留已有文件，缺少的新字段采用默认值。修改后执行 `/meplayeractions reload` 并重新伪装；ME 蓝图变化还需加载新 ME 模型并按原流程更新服务器资源包。

| 配置 | 默认值与作用 |
| --- | --- |
| `models.default` / `models.allowed` | `ysm_01_jk` / 两套示例；默认模型与可用模型白名单 |
| `disguise.scale` | `1.0`，在原始尺寸上缩放 |
| `disguise.hide-self` / `show-self` | 均 `true`；隐藏原版本人并显示伪装，第一人称由客户端跳过完整身体 |
| `disguise.view-distance` / `max-viewers` | `8` 格 / `10` 名其他观看者，不包含本人；距离须严格小于设置值 |
| `disguise.adopt-original` | `true`，识别可接管的原生 `/meg` 伪装；对应模型须使用 `state_machine` |
| `client-sync.enabled` | `true`，开启客户端同步；服务端伪装仍可供原版观看者使用 |
| `client-sync.view-distance-blocks` | `64`，客户端同步的距离上限；仍须满足服务器伪装许可与跟踪条件 |
| `client-sync.private-models.enabled` | `false`，私人多人分享开关，与服务器模型推送分开 |
| `client-sync.private-models.max-bundle-bytes` | `8388608`（8 MiB），私人完整归档及展开资源各自的上限 |
| `client-sync.private-models.max-stored-bytes` | `33554432`（32 MiB），含已发布、接收和验证预留的内存预算 |
| `client-sync.private-models.view-distance-blocks` / `max-viewers` | `64` 格 / `10` 名其他观看者，仍检查权限、同世界、实体跟踪与可见性 |

伪装命令可按本次覆盖配置，例如：

```text
/meplayeractions disguise ysm_02_jk scale=0.8 show-self=true view-distance=8 max-viewers=10 delay=2 effect=slowness:1
```

`delay` 默认 2 tick，只影响 ME 视觉轨迹，客户端默认即时跟随；`effect` 默认无，只允许缓慢效果并需 `mact.disguise.effects`。手动动作使用 `play <动作> [速度] [ONCE|LOOP|HOLD]`，`stop` 停动作，`undisguise` 解除本插件伪装；`pose sit`／`pose crawl` 需要 GSit，`reset` 清理本插件姿态与效果。`animations`／`menu` 查看动作，`sync` 调整同步项目，`status` 查看诊断。完整帮助用 `/meplayeractions help`。

私人分享另需授予发布者 `mact.private.upload`、观看者 `mact.private.view`，两个权限默认均为 `false`；发布者在客户端主页齿轮中主动开启分享。本人已有服务器伪装时，手动 CLIENT 私人覆盖只供本人，不分享该覆盖。

后台发现与动画更新使用独立频率，配置、默认值与边界集中在[性能配置](PERFORMANCE.md)。两端均升级后协商增量同步，旧客户端继续收到完整状态。

## 原模型的正确放置

MPA 依次查找：

1. `plugins/MEPlayerActions/models/<模型ID>.bbmodel`：管理员可选的显式覆盖。
2. 服务端 JAR 的 `models/<模型ID>.bbmodel`：内置示例原模型。
3. `plugins/ModelEngine/blueprints/`：递归匹配文件名，再匹配 `model_identifier`。

**通常自定义模型只需放在 ModelEngine 的 `blueprints/` 中**，不必再复制进 MPA。该文件须保留客户端需要的完整骨架、动画和内嵌 PNG；只有 ME 蓝图已经烘焙、缺少原始表达式或资源时，才需第一项目录提供完整原模型覆盖。ME 模型 ID、MPA 白名单与原模型 ID 应一致。

服务端 JAR 内置 `ysm_01_jk`、`ysm_02_jk` 完整原模型；客户端 JAR 不内置这两套服务器示例。安装包的 `plugins/ModelEngine/blueprints/meplayeractions/` 是 ME 数值蓝图，`examples/models/` 仅供参考。示例完整原模型有 60 个动作及脚本，ME 数值蓝图有 57 个动作；烘焙蓝图不能恢复已移除的表达式、物理脚本或原始文件 hash，不应用它覆盖完整原模型。

MPA 不自动把内置原模型复制到自己的 `models/` 目录。升级时检查该目录中的旧覆盖，它们会遮蔽新版 JAR：需要使用新内置模型时移走对应旧覆盖，自定义文件继续保留。优先来源无效会报告失败，不会悄悄改用下一来源。

服务器原模型最多 8 MiB，压缩后最多 4 MiB。缺失、过大、解析失败或尚在后台准备时保留 ME 显示。生产资源包应继续包含服务器自身其他模型和素材，示例资源不能替代完整生产包。

## 推送、权限与缓存

服务器根据当前伪装、观看许可和可见绑定选择资产。客户端仅反馈该 offer 对应的缓存状态；缓存缺失时由服务器分片推送，不能提交任意模型 ID、路径或 URL 申请下载。播放服务器动作也必须使用当前授权目录，不因关闭客户端渲染而失效。

资产按原始 JSON 字节的 SHA-256 标识。客户端完成 hash 校验、解析及纹理准备，服务器确认当前绑定后才接管显示；单纯收到文件或命中缓存不会先隐藏 ME。校验、解析、纹理或租约失败时，服务器保持或恢复该观看者的 ME 显示。消息字段、传输预算和限流集中见 [客户端协议](CLIENT_PROTOCOL.md)。

服务器模型缓存位于当前 Minecraft 实例的 `config/meplayeractions/cache/`，总量最多 128 MiB、最多 512 个有效条目，按最近使用时间裁剪，**离服后保留**。跨服或重连仍需当次授权和确认；缓存不会自动加入私人图库。私人导入目录为 `config/meplayeractions/models/`，私人选择保存在 `config/meplayeractions-client.json`。

资源重载撤销显示租约并重建纹理，保留完整推送模型和有效在途下载，确认后恢复当前来源。断线、世界切换或授权消失清理当前绑定与下载授权，磁盘缓存继续保留。更完整的显示优先级见 [客户端配置](CLIENT_CONFIG.md)。

## 部署排查与兼容

1. 确认两端版本、ME 模型加载、白名单和观众许可；客户端检查 `/mpaclient status`，服务器检查 `/meplayeractions status`。
2. 接管数为 0、模型“加载中”时，查看服务器资产来源与原因，核对上面的完整原模型来源。默认推送路线无需排查 MPA 资源包索引。
3. 服务端日志搜索“客户端模型资产”，关注 `missing`／`invalid`、模型 ID、来源与原因。缺失／无效覆盖文件、哈希不符和纹理解析失败分别按资产原因处理。
4. 渲染成功但本人不可见时，按 F5 查看第三人称，检查“伪装显示”和服务器本人观看许可；玩家／装备显隐是不同选项。

服务器保留有界的旧资源包与旧下载兼容路线，见 [旧资源包格式](CLIENT_RESOURCE_PACK.md)，它们不是当前安装步骤。当前客户端要求协商 `server-push`；旧服务端未支持时保留服务器后端显示，不能仅凭双方同为协议 v3 判断兼容。

客户端没有 ModelEngine 硬依赖，但未知服务器模型引擎需要适配相同的资产身份、授权、状态与接管／回退约定，不会自动识别。本项目的网络协议与 OpenYSM／Freesia 原协议不互通。资产分发须遵守模型与贴图许可，见 [第三方说明](../THIRD_PARTY_NOTICES.md)。
