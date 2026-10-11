# 模型同步与部署

[文档索引](README.md) · [快速安装](../README.md#快速安装) · [客户端配置](CLIENT_CONFIG.md) · [客户端协议](CLIENT_PROTOCOL.md)

本地私人外观、私人模型多人分享、服务器伪装分别管理：前者不上传；私人分享由有权限的玩家上传、服务器授权分发、各模组客户端渲染；服务器伪装才使用 ModelEngine 后端与客户端接管，未安装模组或尚未接管的观看者由 ME 显示，被伪装者本人无需安装模组。

## 职责与部署方式

| 组件 | 职责 |
| --- | --- |
| ModelEngine | 导入蓝图、生成原版资源、向未接管的观看者显示模型 |
| CraftEngine 等现有资源包流程 | 合并、托管、保护与下发服务器资源包 |
| MEPlayerActions 服务端 | 管理服务器伪装、动作、观看许可与绑定；独立校验、缓存和中继玩家上传的私人模型 |
| MEPlayerActions 客户端 | 校验资产、计算本地动画并接管授权模型；可独立使用私人外观 |

服务器伪装与 `/meplayeractions disguise` 补全使用 ModelEngine 已注册的模型，不依赖 MPA 配置中的模型列表或 MPA `models/` 文件。MPA 配置管理动作、观看和同步策略；MPA `models/` 是客户端原始资源的可选覆盖目录，不向 ModelEngine 注册蓝图。缺失或无效客户端资源只影响接管，服务器继续显示 ME 伪装。

MPA 模型 ID 沿既有命令与协议格式：1–64 位小写英文字母、数字、`_` 或 `-`。补全取 ME 注册表中符合此格式的 ID；ME 自身支持的其他名称格式不在本协议范围内。

MPA 不生成或重打包 ME／CE 资源包。完整原模型含内嵌贴图，可能与服务器资源包中的贴图重复；当前推送路径尚未跨包复用材质。ME／CE 的资源包保护也不加密 MPA 推送的原模型，被授权接收者可以取得这份资产。

仅中继私人模型分享时，同一个服务端 JAR 可运行在 Paper 1.21.11／Java 21 上，无需 ModelEngine 或服务器资源包。客户端本地私人外观无需服务端；多人分享由发布者主动选择、服务器启用并授权，获准观看者被动接收。私人上传不创建 ME 蓝图、伪装实体或原版资源包，原版玩家仍看见原版人物。

## 服务端配置

配置位于 `plugins/MEPlayerActions/config.yml`。首次安装可使用安装包默认配置；升级保留已有文件，缺少的新字段采用默认值。修改后执行 `/meplayeractions reload` 并重新伪装；ME 蓝图变化还需加载新 ME 模型并按原流程更新服务器资源包。

| 配置 | 默认值与作用 |
| --- | --- |
| `disguise.scale` | `1.0`，在原始尺寸上缩放 |
| `disguise.hide-self` / `show-self` | 均 `true`；隐藏原版本人并显示伪装，第一人称由客户端跳过完整身体 |
| `disguise.view-distance` / `max-viewers` | `8` 格 / `10` 名其他观看者，不包含本人；距离须严格小于设置值 |
| `disguise.adopt-original` | `true`，识别可接管的原生 `/meg` 伪装；对应模型须使用 `state_machine` |
| `client-sync.enabled` | `true`，开启客户端同步；服务端伪装仍可供原版观看者使用 |
| `client-sync.view-distance-blocks` | `64`，客户端同步的距离上限；仍须满足服务器伪装许可与跟踪条件 |
| `client-sync.private-models.enabled` | `false`，私人多人分享开关，与服务器模型推送分开 |
| `client-sync.private-models.max-bundle-bytes` | `8388608`（8 MiB），私人完整归档及展开资源各自的上限 |
| `client-sync.private-models.max-stored-bytes` | `33554432`（32 MiB），含已发布、接收和验证预留的内存预算 |
| `client-sync.private-models.cache-enabled` | `true`，为合法上传保留独立磁盘缓存；仍须开启私人分享与权限 |
| `client-sync.private-models.max-cache-bytes` | `134217728`（128 MiB），服务器私人磁盘缓存总预算 |
| `client-sync.private-models.max-cache-models` / `max-cache-models-per-player` | `512` / `4`，总缓存条目与单个上传者上限 |
| `client-sync.private-models.view-distance-blocks` / `max-viewers` | `64` 格 / `10` 名其他观看者，仍检查权限、同世界、实体跟踪与可见性 |

伪装命令可按本次覆盖配置，例如：

```text
/meplayeractions disguise ysm_02_jk scale=0.8 show-self=true view-distance=8 max-viewers=10 delay=2 effect=slowness:1
```

`delay` 默认 2 tick，只影响 ME 视觉轨迹，客户端默认即时跟随；`effect` 默认无，只允许缓慢效果并需 `mact.disguise.effects`。手动动作使用 `play <动作> [速度] [ONCE|LOOP|HOLD]`，`stop` 停动作，`undisguise` 解除本插件伪装；`pose sit`／`pose crawl` 需要 GSit，`reset` 清理本插件姿态与效果。`animations`／`menu` 查看动作，`sync` 调整同步项目。管理员 `status` 查看全服健康状态，`status player <玩家名>` 查看个人诊断。完整帮助用 `/meplayeractions help`。

私人分享另需授予发布者 `mact.private.upload`、观看者 `mact.private.view`，两个权限默认均为 `false`；发布者在客户端图库明确点击云朵“上传分享”，使用所选模型并上传；“使用模型”仅在本机显示。观看者无需开启自己的分享开关。本人已有服务器伪装时，须先解除服务器伪装，才能使用或分享私人模型。

后台发现与动画更新使用独立频率，配置、默认值与边界集中在[性能配置](PERFORMANCE.md)。两端协商增量同步；未协商时发送完整状态。

两个资源频道共享 `resource-protection` 的上下行、任务、内存与传输预算；服务器负载保护可暂停新增上传、下载或伪装。新字段缺失时采用默认值，无需覆盖已有配置。各项默认预算、超时、自动恢复及调优见 [服务器资源保护](SERVER_RESOURCE_PROTECTION.md)。

## 私人玩家上传与资源缓存

玩家把 `.ysm`、YSM 目录／ZIP 或自包含 `.bbmodel`（可带模型目录内声明的 PNG）放入自己实例的 `config/meplayeractions/models/`。图库中“使用模型”仅在本机应用，云朵“上传分享”使用所选模型并发布给获准的模组观看者。客户端保留原生资源与作者能力，服务器校验完整 bundle 后确认发布；上传成功不绕过观看权限，也不变为服务器伪装。服务器保存的本人上传列表在入服后主动同步，本机内容一致时才可复用；断线只清理当前连接授权，不删除服务器持久条目。

服务器将已校验上传缓存于 `plugins/MEPlayerActions/private-models/`，文件为 `<上传者UUID>_<完整资源SHA256>.zip`，同名 `.meta.json` 保存模型 ID、类型和长度。缓存只可由同一上传者重新发布，需重新核对长度、类型、hash、资源有效性以及当次权限；命中时可免重传，失败则按正常上传流程处理。目录查看、查验、写入和删除使用有界后台作业，展示资产仍占用独立内存预算。按磁盘容量、总条目和每玩家上限裁剪，并同步本人列表；不要把缓存复制到 `models/` 或 ME `blueprints/` 来公开它。

玩家可从已保存条目的云朵入口确认删除该模型及其已验证旧版本的服务器存档；服务器成功删除后更新列表，若该来源正在分享则同时结束分享。正在上传或后台校验时提示稍后重试。删除不影响本地原文件、其他玩家资源或另一份正在分享的模型。目录记录不自动恢复发布，也不赋予下载别人模型的权限。

关闭分享、离线、撤权或租约失效撤销展示和观众授权，磁盘缓存按预算保留，服务器重启也不自动恢复发布。重连后玩家仍要满足开关、权限和当次协商。`cache-enabled: false` 不读取或写入既有缓存，保留普通上传流程；该目录与自定义服务器原模型和历史发布包独立。

资源分发只回应服务器对当前关系签发的 offer。观看客户端不能指定其他玩家、模型 ID、磁盘路径或 URL 请求资产；发布者也不能凭别人知道的 hash 使用他人的缓存。已接收的资源无法远程撤回，上传和分享需遵守模型许可。

## 服务器伪装原模型的正确放置

MPA 依次查找：

1. `plugins/MEPlayerActions/models/<模型ID>.bbmodel`：管理员可选的显式覆盖。
2. 服务端 JAR 的 `models/<模型ID>.bbmodel`：内置示例原模型。
3. `plugins/ModelEngine/blueprints/`：递归匹配文件名，再匹配 `model_identifier`。

**服务器伪装的自定义模型放在 ModelEngine 的 `blueprints/` 中并由 ME 加载**，不必再复制进 MPA。客户端接管另需完整骨架、动画和内嵌 PNG；只有 ME 蓝图已经烘焙、缺少原始表达式或资源时，才需第一项目录提供完整原模型覆盖。下发文件名或原模型 ID 应与 ME 注册 ID 对应；仅放入 MPA `models/` 不会创建服务器伪装。

服务端 JAR 内置 `ysm_01_jk`、`ysm_02_jk` 完整原模型；客户端 JAR 不内置这两套服务器示例。安装包的 `plugins/ModelEngine/blueprints/meplayeractions/` 是 ME 数值蓝图，`examples/models/` 仅供参考。示例完整原模型有 60 个动作及脚本，ME 数值蓝图有 57 个动作；烘焙蓝图不能恢复已移除的表达式、物理脚本或原始文件 hash，不应用它覆盖完整原模型。

MPA 不自动把内置原模型复制到自己的 `models/` 目录。该目录中的同名文件会遮蔽 JAR 内置模型；使用内置模型时，应移走对应覆盖文件，自定义文件继续保留。优先来源无效会报告失败，不会改用下一来源。

完整传输表示所选文件的字节和 hash 完整，不保证选源文件仍含作者脚本。服务器若选中 ME 数值蓝图，客户端也只会收到数值蓝图；不能从已烘焙的关键帧恢复头发弹簧等公式。内置示例出现细节缺失时，先用 `status player <被伪装玩家>` 核对资产来源，比较同名覆盖文件与 `examples/models/` 的原文件。确认覆盖文件是误放的数值蓝图后，备份并移走它，或换成完整原文件，再 reload 并重新伪装。模型内容变化会产生新的 hash，客户端不会因模型 ID 相同而误用旧 hash 缓存，无需先清空客户端目录。

服务器原模型最多 8 MiB，压缩后最多 4 MiB。缺失、过大、解析失败或尚在后台准备时保留 ME 显示。生产资源包应继续包含服务器自身其他模型和素材，示例资源不能替代完整生产包。

## 推送、权限与缓存

服务器根据当前伪装、观看许可和可见绑定选择资产。客户端仅反馈该 offer 对应的缓存状态；缓存缺失时由服务器分片推送，不能提交任意模型 ID、路径或 URL 申请下载。播放服务器动作也必须使用当前授权目录，不因关闭客户端渲染而失效。

资产按原始 JSON 字节的 SHA-256 标识。客户端完成 hash 校验、解析及纹理准备，服务器确认当前绑定后才接管显示；单纯收到文件或命中缓存不会先隐藏 ME。校验、解析、纹理或租约失败时，服务器保持或恢复该观看者的 ME 显示。消息字段、传输预算和限流集中见 [客户端协议](CLIENT_PROTOCOL.md)。

客户端接收资源缓存位于当前 Minecraft 实例的 `config/meplayeractions/cache/`，总量最多 128 MiB、最多 512 个有效条目，按最近使用时间裁剪，**离服后保留**。它与服务器的私人上传缓存是两个目录。跨服或重连仍需当次授权和确认；缓存不会自动加入私人图库。私人导入目录为 `config/meplayeractions/models/`，私人选择保存在 `config/meplayeractions-client.json`。

资源重载撤销显示租约并重建纹理，保留完整推送模型和有效在途下载，确认后恢复当前来源。断线、世界切换或授权消失清理当前绑定与下载授权，磁盘缓存继续保留。更完整的显示优先级见 [客户端配置](CLIENT_CONFIG.md)。

## 部署排查与兼容

1. 确认两端版本、ME 模型注册与命令／观众权限；客户端检查 `/mpaclient status`，管理员检查 `/meplayeractions status` 与 `status player <玩家名>`。
2. 接管数为 0、模型“加载中”时，查看服务器资产来源与原因，核对上面的完整原模型来源。默认推送路线无需排查 MPA 资源包索引。
3. 服务端日志搜索“客户端模型资产”，关注 `missing`／`invalid`、模型 ID、来源与原因。缺失／无效覆盖文件、哈希不符和纹理解析失败分别按资产原因处理。
4. 渲染成功但本人不可见时，按 F5 查看第三人称，检查“伪装显示”和服务器本人观看许可；玩家／装备显隐是不同选项。

服务器保留有界的 `resource-pack` 与 `legacy-download` 兼容路线，见 [资源包兼容格式](CLIENT_RESOURCE_PACK.md)。当前客户端要求协商 `server-push`；服务端未支持时保留服务器后端显示，不能仅凭双方同为协议 v3 判断兼容。

客户端没有 ModelEngine 硬依赖，但未知服务器模型引擎需要适配相同的资产身份、授权、状态与接管／回退约定，不会自动识别。本项目的网络协议与 OpenYSM／Freesia 原协议不互通。资产分发须遵守模型与贴图许可，见 [第三方说明](../THIRD_PARTY_NOTICES.md)。
