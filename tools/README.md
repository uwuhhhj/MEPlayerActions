# 开发工具

当前版本为服务端与客户端 **0.6.1**。从项目根目录运行工具；构建、定向检查和交付流程见[开发说明](../CONTRIBUTING.md)，运行配置见[文档索引](../docs/README.md)。

| 工具 | 职责 |
| --- | --- |
| [package_current.py](package_current.py) | 当前 0.6.1 两端交付入口。读取已完成的构建和定向检查证据，校验资源、许可、版本与 ZIP 内容，输出工作区 `dist/`。 |
| [package_release.py](package_release.py) | 资源、许可和 ZIP 校验辅助函数；其 `full`、`interactions` 流程不作为当前交付入口。 |
| [prepare_models.py](prepare_models.py) | 根据指定研究源模型生成 `examples/models/` 原模型及 `examples/blueprints/` 的 ME 数值动画版本，并更新清单。会写入示例资源；日常客户端或服务器安装无需运行。 |
| [build_client_resource_pack.py](build_client_resource_pack.py) | 已有的可选离线资源包工具，写入客户端模型索引。默认服务器主动推送路线无需运行；ModelEngine 导入蓝图、CraftEngine 合并与下发的职责见[模型分发](../docs/MODEL_DELIVERY.md)。 |
| [tests/](tests/) | 离线资源包与打包证据门槛的行为测试；合成报告只验证工具规则，不代表 Minecraft 画面、实际构建或性能验证。 |

## 当前打包入口

```powershell
python -B tools/package_current.py --help
python -B tools/package_current.py
```

该入口固定验证 **0.6.1**，不会替你构建、运行测试、启动 Minecraft 或服务器。它要求两端最新 JAR、保存的当轮定向报告、各阶段 `stage.json` 与 `build/validation-0.6.1/build-commands.json`，以及固定版本的 OpenYSM 参考源码和 Gradle 依赖缓存。可用 `--reference`、`--gradle-cache` 指定本机位置。

默认读取 `build/validation-0.6.1/` 的全部阶段，可用 `--stage-order` 明确顺序。若本轮已在提升版本前完成受影响检查，可用 `--evidence-manifest` 按实际执行顺序列出 `0.6.0` 与 `0.6.1` 的阶段，不改原版本、目录、报告或退出码，也不重复已通过且未再修改的检查。该选项和 `--stage-order` 互斥；清单只接受本轮合同中的这两个版本，不接受任意旧发布验收。

```json
{
  "release_version": "0.6.1",
  "stages": [
    {"directory": "build/validation-0.6.0/client-affected", "version": "0.6.0", "reason": "本轮受影响检查在版本提升前执行，原记录保留"},
    {"directory": "build/validation-0.6.1/server-affected", "version": "0.6.1"}
  ]
}
```

旧阶段必须说明保留原因，其父目录必须仍为 `validation-0.6.0`，`stage.json` 版本也必须一致。清单按版本／阶段分别保存原始证据，摘要明确标记是否包含升级前的本轮记录；这些检查不会被表述为 0.6.1 重新执行。清单应包括本轮验收范围内的失败尝试和对应修复验证。

原阶段若没有 `client`／`server` 前缀，可在清单条目加 `"side":"client"`、`"side":"server"` 或 `"side":"integration"`，保留原目录名；命令记录已有 `side` 时必须一致。integration 报告仍须分别位于 `server/`、`client/` 子目录。

阶段目录以 `server`、`client` 或 `integration` 开头，例如 `server-sharing`、`client-sharing`、`integration`。前两类目录中的 `TEST-*.xml` 按所属阶段确定侧别；跨两端的 integration 阶段须分别放在 `server/`、`client/` 子目录，不修改原始报告。每阶段保存以下元数据，命令与退出码必须来自实际执行：

```json
{"version":"0.6.1","commands":[{"command":"实际定向检查命令","exit_code":0}]}
```

测试阶段保存真实整数退出码，早期失败不得改写为 0。已有 `client-command.json`／`server-command.json` 时，可读取实际记录生成 `stage.json`：保留原 `side`、`mode`、开始／结束时间、`executable`、`arguments`、`exit_code` 等字段，增加 `command: [executable, ...arguments]`，版本按原执行阶段填写。不能根据后续通过结果改写早期命令。

首次包含多个测试类时，只重跑后来修改或失败的类；其余已通过的用例保留当时的证据。按侧别、测试套件和显示名作保守去重，每个最后结果须通过，且两端都有当轮报告；XML 的 tests／failures／errors／skipped 数量须与 outcome 节点一致。参数化显示名可能跨方法重复：原 XML 完整保留，阶段内同名结果必须全部通过，`raw_reported_testcases` 记录原报告数量（含重跑），去重数不冒充独立测试方法数。较少项的后续通过不能掩盖此前同名组中的不明失败，须核对该受影响组的完整结果。失败的编译尝试可没有 XML，但不能增加通过数；成功测试阶段须提供报告。

`summary.json` 保留各阶段的原始版本、命令、退出码与报告，并记录 `failed_command_attempts`、`earlier_report_failures`、`earlier_report_errors`，不能把最终通过表述为每次尝试都通过。需要纠正的结果放入新阶段，不覆盖原 XML；默认模式下 `--stage-order` 必须恰好列出全部阶段。

最终 `build-commands.json` 必须标明 **0.6.1**，至少包含 Maven `package -DskipTests` 与 Gradle `build -x test`，所有最终构建命令须退出 0。两端 JAR 的版本、构建新鲜度、当前源码资源和打包内容仍独立验证；阶段保留规则不会放宽这些检查。源码包排除服务端 `private-models/` 和任意 `private-fixture*` 目录；正常源码、内置资源和公开示例不受此排除影响。

证据门槛的合成回归可单独执行，不运行模型或完整旧测试矩阵：

```powershell
python -B -m unittest discover -s tools/tests -p test_package_current_evidence.py -v
```

已有任一同名交付文件时会拒绝输出，不覆盖发布包。可用 `--artifact-suffix fresh-20261011-01` 为本次 JAR、安装包、源码、证据和报告追加新文件名后缀；JAR 内版本及安装目录中的标准 JAR 名称保持 0.6.1。后续发布需明确更新版本合同和当轮验证证据；此脚本不是任意版本发布器。

```powershell
python -B tools/package_current.py --evidence-manifest build/validation-0.6.1/evidence-manifest.json --artifact-suffix fresh-20261011-01
```

源码包保留 `tools/` 下的入口与依赖；协议唯一来源是 [docs/CLIENT_PROTOCOL.md](../docs/CLIENT_PROTOCOL.md)。`build/`、`target/`、`client/build/` 是忽略的本机输出，包含日志、验收辅助脚本与临时探针；这些内容不会自动成为正式工具或新一轮验证证据。客户端 `src/test/` 下的实机辅助类同样不是安装步骤，按当轮需要选择，不重复执行已通过的旧场景。
