# 工具说明

当前版本为服务端与客户端 **0.6.2**。从项目根目录运行工具；构建、验证和交付流程见[开发说明](../CONTRIBUTING.md)，运行配置见[文档索引](../docs/README.md)，Java 代码规范见 [AGENTS.md](../AGENTS.md)。

| 工具 | 用途 |
| --- | --- |
| [package_current.py](package_current.py) | 当前 0.6.2 两端交付入口。读取已完成的构建和单元检查证据，校验资源、许可、版本与 ZIP 内容，输出工作区 `dist/`。 |
| [package_release.py](package_release.py) | 资源、许可和 ZIP 校验辅助函数；其 `full`、`interactions` 流程不作为当前交付入口。 |
| [prepare_models.py](prepare_models.py) | 根据研究源模型生成 `examples/models/` 原模型及 `examples/blueprints/` 的 ME 数值动画版本，并更新清单；日常安装无需运行。 |
| [build_client_resource_pack.py](build_client_resource_pack.py) | 构建兼容资源包；客户端主动推送部署无需运行。 |
| [java-formatter.xml](java-formatter.xml) | 可导入 Eclipse 或 IntelliJ Eclipse 插件的 Java 格式配置：四空格缩进、大括号独占一行、方法之间留空行。 |

## 当前打包入口

```powershell
python -B tools/package_current.py --help
python -B tools/package_current.py
```

该入口固定验证 **0.6.2**，不会替你构建、运行测试、启动 Minecraft 或服务器。它要求两端最新 JAR、保存的当轮单元报告、各阶段 `stage.json` 与 `build/validation-0.6.2/build-commands.json`，以及固定版本的 OpenYSM 参考源码和 Gradle 依赖缓存。可用 `--reference`、`--gradle-cache` 指定本机位置。

默认读取 `build/validation-0.6.2/` 的全部阶段，可用 `--stage-order` 明确顺序；该参数必须恰好列出全部阶段。也可用 `--evidence-manifest` 明确列出本轮阶段，二者互斥。本版只接受 0.6.2 的当轮证据，不复用历史发布验收。

每个阶段的 `stage.json` 保存实际命令和整数退出码：

```json
{"version":"0.6.2","commands":[{"side":"server","mode":"full-unit-tests","command":["mvn","test"],"exit_code":0}]}
```

定向检查使用 `mode: "targeted-tests"`；完整单元套件使用 `mode: "full-unit-tests"`。两端均有成功的完整单元阶段时，摘要记为 `full_suite_run: true`。保留实际开始／结束时间、执行文件、参数、退出码和报告；不能根据后续通过结果改写早期失败。

`summary.json` 保留各阶段命令、原始版本、退出码与报告，并记录 `failed_command_attempts`、`earlier_report_failures`、`earlier_report_errors`。纠正的结果放入新阶段，不覆盖原 XML；不能把最终通过表述为每次尝试都通过。参数化显示名可能重复，`distinct_targeted_cases` 作保守去重，`raw_reported_testcases` 保留实际执行项数。

最终 `build-commands.json` 必须标明 **0.6.2**，至少包含 Maven `package -DskipTests` 与 Gradle `build -x test`，所有最终构建命令须退出 0。两端 JAR 的版本、构建新鲜度、当前源码资源和打包内容独立验证。

源码包包含 `AGENTS.md` 与格式配置，排除服务端 `private-models/`、任意 `private-fixture*` 目录和其他私人输入；正常源码、内置资源和公开示例保留。单元、编译、资源和包校验不代替 Minecraft／服务器实机验收或性能测量。

同名交付文件已存在时会拒绝输出，不覆盖发布包。可用 `--artifact-suffix fresh-20261011-01` 为当次 JAR、安装包、源码、证据和报告追加文件名后缀；JAR 内版本和安装目录中的标准 JAR 名称保持 0.6.2。

后续发布须同步修改版本合同、构建版本和当轮验证证据。
