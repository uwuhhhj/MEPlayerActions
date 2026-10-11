# 开发与交付

本项目包含 Paper 服务端与独立 Fabric 客户端。先看 [架构](ARCHITECTURE.md) 定位模块；线格式和授权语义以 [协议](docs/CLIENT_PROTOCOL.md) 为准，用户部署见 [README](README.md)。

资源处理遵守“主线程快检／快照 → 有界后台工作 → 主线程复核提交”。新增后台作业使用共享资源运行时，不直接向 Bukkit 全局异步池提交无界资源任务；拒绝策略不能在调用线程执行重工作。取消后的运行任务仍保留字节预留，直到工作线程实际退出。Bukkit／ModelEngine 实体操作留在安全线程，解绑、撤权、ACK 和退出清理不得被负载保护关闭。运行限制与诊断见 [服务器资源保护](docs/SERVER_RESOURCE_PROTECTION.md)。

## 仓库入口

| 路径 | 用途 |
| --- | --- |
| `src/main/java/`、`src/main/resources/` | 服务端逻辑、插件描述与默认配置 |
| `client/src/main/` | 客户端逻辑、Mixin、UI 与内置资源 |
| `src/test/java/`、`client/src/test/java/` | 两端单元与组件测试 |
| `examples/` | 原模型、ME 数值蓝图及资产生成记录，不是用户配置目录 |
| `tools/` | 模型准备、兼容资源包工具与交付工具 |
| `docs/`、`docs/history/` | 当前专项说明及历史版本记录 |
| `target/`、`client/build/`、`build/` | 编译输出、依赖缓存与验证证据，不作为源码编辑入口 |

保留已有工作区修改、玩家模型和历史发布包。不要把生产配置、下载缓存、日志、密钥、运行时数据库或第三方依赖 JAR 提交到源码。调整引用或移动文档时同时检查链接和打包脚本。

## 构建环境

- 使用 JDK 21，将 `JAVA_HOME` 指向 JDK 根目录，并确认 Maven/Gradle 实际使用该 JDK。
- 服务端使用 PATH 中的 Maven；[pom.xml](pom.xml) 需要管理员合法提供的 ModelEngine R4.1.1 JAR。默认路径是仓库相邻的 `../ModelEngine-R4.1.1.jar`，也可用 `-Dmodelengine.jar=<JAR绝对路径>` 覆盖。编译需要它，与插件运行时可切入私人中继模式是两件事。
- 客户端使用已入库的 Gradle Wrapper，无需全局 Gradle。Minecraft、Fabric、Maven 与 Gradle 依赖首次构建需要网络；具体版本以 [build.gradle](client/build.gradle)、[wrapper 配置](client/gradle/wrapper/gradle-wrapper.properties) 和 [pom.xml](pom.xml) 为准。
- 打包与模型工具使用 Python 3。第三方源码、资源来源与许可见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)，不要移除来源文件。

在项目根目录使用 PowerShell：

```powershell
java -version
mvn -version
mvn -DskipTests package
Push-Location client
try { .\gradlew.bat build -x test --no-daemon }
finally { Pop-Location }
```

POSIX 环境的客户端入口为 `./gradlew build -x test --no-daemon`。上面的命令只构建；按下一节选择受影响检查。输出服务端 JAR 在 `target/`，客户端可安装的 remap JAR 在 `client/build/libs/`；不要安装 `-sources.jar` 或开发用未重映射 JAR。

Windows 若 Gradle 在 daemon 启动前报 `Could not create service of type OutgoingConnector` 或 Unix 域套接字创建错误，先检查短、可写的 ASCII 临时目录。可在当前终端为 JVM 设置 `-Djdk.net.unixdomain.tmpdir=<目录>`，无需修改系统配置；这是构建环境处理，不是代码测试结果。测试 classpath 包含非 ASCII 路径时，Gradle 会暂存对应条目；默认临时路径也含非 ASCII 时，显式添加 `-PmpaAsciiRuntimeDir=<可写ASCII目录>`。

## 验证策略

按修改选择新增或受影响的测试；已经通过且无新变化的场景不重复执行。保存实际报告，不以历史报告或残留 XML 代替当前验证，也不把单元通过表述为游戏、TPS 或千人压测通过。

例如，修改服务器状态同步与客户端增量协议时：

```powershell
mvn '-Dtest=ClientSyncPerformanceTest,ClientSyncLifecyclePerformanceTest' test
Push-Location client
try { .\gradlew.bat test --tests '*IncrementalStateProtocolTest' --no-daemon }
finally { Pop-Location }
```

服务端报告位于 `target/surefire-reports/`，客户端报告位于 `client/build/test-results/test/`。将本次实际执行的报告、命令、退出码和必要输入身份保存到独立验证目录；源码继续变更后，重跑受影响检查并重新构建。

定向验证后使用上面的构建命令生成最终 JAR，避免重复执行已完成的单元检查。

纯文档或仓库组织调整使用链接、路径、脚本语法与包内容校验，不必因此启动游戏。渲染、Minecraft/ME/GSit 版本适配或多人显示行为若要宣称实机通过，必须有该次实际 JAR 对应的记录；用户明确自行验收时，如实交付未做实机复验的范围。

## 打包与版本

当前交付入口为 [tools/package_current.py](tools/package_current.py)：

```powershell
python tools/package_current.py --help
```

它读取已构建的两端 0.6.1 JAR、阶段报告与实际命令记录，校验后输出工作区 `dist/` 的 JAR、安装 ZIP、源码 ZIP、证据 ZIP 和构建 JSON。同名文件已存在时拒绝输出。最终构建记录位于 `build/validation-0.6.1/`；本轮升版前的定向检查可用显式证据清单保留其实际版本、路径和范围，不冒充升版后重跑。工具不执行构建、测试或实机验收；参数、阶段顺序与证据结构见 [工具说明](tools/README.md)。

脚本的版本及最终构建合同固定为 0.6.1。行为发布须同步 `pom.xml`、`client/build.gradle`、脚本版本合同和相应证据。源码交付排除服务端 `private-models/`、`private-fixture*` 和其他私有输入，完整保留正常 `src` 资源与公开示例。文档整理本身不必递增软件版本。[package_release.py](tools/package_release.py) 提供资源、许可和 ZIP 辅助校验；其 `full` / `interactions` 流程不作为当前打包入口。

打包前核对插件/模组描述、安装说明和第三方声明；打包后检查 ZIP 完整性、结构、版本、许可、内容与当前源码/JAR 一致性，继续修改后重新打包。`tools/prepare_models.py` 准备示例原模型和 ME 蓝图，`tools/build_client_resource_pack.py` 用于资源包兼容模式；主动推送部署不需要运行它们，文档整理不重写模型资产。提交、推送和发布按用户授权执行。

## 文档维护

README 与使用、配置、协议、兼容文档描述当前行为，删除过时版本对照和逐日核对记录。版本变更集中在 `docs/history/`；命令、失败排查、测试数量和构建证据保存到对应验证目录或提交描述。固定参考源码、当前限制、配置契约和第三方署名保留在各自文档中。
