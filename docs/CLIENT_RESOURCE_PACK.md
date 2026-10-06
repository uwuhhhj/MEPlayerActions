# 辅助资源包格式 v1

[文档索引](README.md) · [模型部署](MODEL_DELIVERY.md) · [客户端协议](CLIENT_PROTOCOL.md)

**默认部署使用 MPA 主动推送完整原模型，不需要本页的索引或构建工具。** ModelEngine／CraftEngine 的资源包继续用于原版观看者。当前客户端的服务器接管要求协商 `server-push`，资源包索引不能代替；本页用于维护 `resource_pack_models` 兼容资产和 `/mpaclient preview` 的资源包预览。

辅助包包含完整 BBModel 与 PNG。构建工具只处理已有资源，不调用 ModelEngine，也不修改输入文件。用于客户端的模型应保留原始骨架、动画、表达式和内嵌 PNG；烘焙蓝图不能恢复这些内容。

## 构建工具

使用 Python 3 与 Pillow。Pillow 用于完整 PNG 解码和像素等价验证；缺少时运行 `python -m pip install Pillow`。

在项目根目录运行，将路径替换为实际文件：

```powershell
python tools/build_client_resource_pack.py `
  --engine-pack "plugins/ModelEngine/resource pack.zip" `
  --model "ysm_01_jk=examples/models/ysm_01_jk.bbmodel" `
  --model "ysm_02_jk=examples/models/ysm_02_jk.bbmodel" `
  --output "resourcepacks/mpa-unified.zip"
```

| 参数 | 含义 |
| --- | --- |
| `--engine-pack` | 可选；已有完整引擎／合并资源包 ZIP，根目录须有 `pack.mcmeta` |
| `--model ID=路径` | 每个完整原始 `.bbmodel` 一项，可重复 |
| `--output` | 新输出 ZIP；已有文件不会覆盖 |
| `--pack-format` | 不提供引擎包时必填；使用目标 Minecraft 版本的格式整数 |
| `--description` | 独立包说明 |

合并时使用生产服务器的完整最终包，保留其他模型与额外资源；仅含两套示例的包不能替代生产包。工具输出最终路径、SHA-256、大小、条目数和模型索引。相同输入在相同 Python／zlib 环境中生成相同字节。

## 索引与模型

索引位于 `assets/meplayeractions/models/index.json`：

```json
{
  "version": 1,
  "models": [
    {
      "modelId": "ysm_01_jk",
      "hashSha256": "原始完整bbmodel字节的64位小写SHA-256",
      "modelResource": "meplayeractions:models/ysm_01_jk.bbmodel"
    }
  ]
}
```

每包包含 1–128 个模型，ID 唯一且符合 `[a-z0-9_-]{1,64}`。资源 ID 按 `namespace:path` 解析为 `assets/namespace/path`，不会自动添加目录或后缀。

`hashSha256` 标识原始完整 BBModel 字节。构建时只将 `textures[i].source` 的内嵌 PNG 字符串替换为资源 ID，其余 JSON 文本、空白、行尾和字段顺序保持：

```json
{"textures":[{"source":"modelengine:textures/entity/models/skin.png"}]}
```

引擎包已有相同 PNG 时优先复用。编码不同但尺寸和 RGBA 像素完全一致时，工具可在输出副本中统一 PNG 编码；同一路径的覆盖层也须像素一致。否则存入 `assets/meplayeractions/textures/sha256/<PNG字节SHA-256>.png`，相同字节只存一份。冲突或会破坏另一模型原始哈希的替换会拒绝。

`assets/meplayeractions/models/normalization.json` 记录输入包哈希和 PNG 编码改写的路径、原始／源哈希、尺寸与 RGBA 哈希。独立包的输入包哈希为 `null`；未列入改写清单的引擎条目内容保持。

## 读取与校验

客户端从当前 ResourceManager 读取索引、模型和引用 PNG，将 PNG 原始字节编码为标准 Base64，再恢复 `textures[i].source` 的 `data:image/png;base64,...` 字符串。恢复后的 UTF-8 字节必须匹配索引哈希，随后解析并准备纹理。资源缺失、哈希不符或解析失败时不可用；资源重载后重新读取并准备绘制。

为保证精确恢复，输入须为严格 UTF-8 JSON，无重复键、非有限数字或超过 64 层的结构。内嵌 PNG 使用规范 Base64；贴图源键和前缀须直接写为 `"source":"data:image/png;base64,..."`，不能在该字符串中加入额外 JSON 转义。其他字段的合法 JSON 转义不受影响。

## 资源限制

| 项目 | 上限 |
| --- | --- |
| 输入 ZIP、输出 ZIP、展开条目总量 | 各 256 MiB |
| ZIP 单条目／条目数 | 32 MiB／65,536 |
| 原始模型 | 每个 8 MiB |
| 模型贴图 | 每模型 1–16 张；单张 6 MiB、宽高各 4,096，合计 16,777,216 像素 |

工具校验 PNG 完整性并用 Pillow 解码。ZIP 拒绝路径逃逸、重复或大小写冲突条目、文件／目录冲突、符号链接、加密及非 STORED／DEFLATED 压缩；只读取条目，不解压到文件系统。输出后重新核对 ZIP 完整性和条目字节。几何与表达式限制由客户端解析器检查，见 [YSM 支持范围](YSM_COMPATIBILITY.md)。

实现见 [build_client_resource_pack.py](../tools/build_client_resource_pack.py)。
