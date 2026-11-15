# 项目协作指南

本文件适用于平台仓库 `android/`，与[平台根规则](../AGENTS.md)共同生效。Android Studio 打开本目录，以下未特别说明的工程路径相对本目录；平台根执行命令使用 `bash android/gradlew -p android ...`。OpenSpec 和技能统一由平台根目录维护，不在 Android 内创建第二套入口。

Android 崩溃分析沿用根当前技能：从本目录启动仍以平台 Git 根为项目根，报告源码引用带 `android/` 前缀；宿主只在用户指定的 Android 范围内修改，验证命令明确选择本工程目录。参考[当前分析边界](../backend/docs/knowledge-base/local-analysis.md)。

## 知识库入口

- [知识库导航与阅读路径](docs/knowledge-base/README.md)
- [项目概览与能力状态](docs/knowledge-base/00-项目概览与能力状态.md)
- [模块架构与源码导航](docs/knowledge-base/01-模块架构与源码导航.md)
- [服务端对接与数据协议](docs/knowledge-base/09-服务端对接与数据协议.md)
- [常见问题与待验证事项](docs/knowledge-base/12-常见问题与待验证事项.md)
- [SDK 配置指南](docs/knowledge-base/16-SDK配置指南.md)与[FPS 算法说明](docs/knowledge-base/17-FPS算法说明.md)

根目录以本文件作为项目规范和知识库入口，不另设根目录 README.md。

## 项目结构与模块组织

- `app/` 是可运行的 Android 应用，界面、演示页面和资源分别位于 `src/main/java` 与 `src/main/res`。
- `performance-core/`、`performance-transport/`、`performance-crash/`、`performance-jank/`、`performance-metrics/`、`performance-leak/`、`performance-sdk/` 是按能力拆分的 Android Library；`performance-native-tools/` 单独承载 Kotlin/Java 工具及 JNI/CMake 原生代码，当前原生构建限定 `arm64-v8a`。
- 旧单体 `nativelib/` 已删除；配置与算法说明位于 `docs/knowledge-base/`，原生 SO 名仍为 `nativelib`。
- 单元测试放在各模块的 `src/test`，设备测试放在 `src/androidTest`。根目录的 `settings.gradle.kts`、`build.gradle.kts` 和 `gradle/libs.versions.toml` 管理模块与依赖。

## 构建、测试和开发命令

在 Windows 上优先使用 Gradle Wrapper，并准备可运行当前 Gradle/AGP 的 JDK、Android SDK、NDK 与 CMake 3.22.1。当前 AGP 8.13.2 的最低运行 JDK 为 17，模块 Java/Kotlin 字节码目标仍为 11；版本依据和依赖准备见[构建测试与本地发布](docs/knowledge-base/11-构建测试与本地发布.md)。

- `.\gradlew.bat assembleDebug`：编译 Debug APK 和库产物。
- `.\gradlew.bat test`：运行所有 JVM 单元测试。
- `.\gradlew.bat :app:testDebugUnitTest`：只运行应用模块的 Debug 单元测试；各功能库使用对应的 `:performance-*:testDebugUnitTest` 任务。
- `.\gradlew.bat connectedAndroidTest`：在已连接的设备或模拟器上运行仪器测试。
- `.\gradlew.bat check`：执行项目配置的校验任务，适合提交前快速检查。

## 代码风格与命名约定

Kotlin、Java、C++ 均使用 4 个空格缩进，沿用 Android Studio 默认格式；仓库未配置独立格式化或 lint 规则。包名全部小写，类和 Activity 使用 `PascalCase`，方法、变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。资源文件使用小写下划线命名，例如 `activity_main.xml`。性能监控、线程池和 Hook 代码应保持生命周期清晰，并在修改原生接口时同步检查 Kotlin/JNI 两端。

生成代码加入必要注释，生成文档全部使用中文。项目处于初次开发阶段，修改功能时可直接调整当前设计，不需要维护旧代码兼容层。

## 测试指南

测试类以 `*Test.kt` 命名；纯逻辑测试放在 `src/test`，需要 Android 环境、UI 或真实设备的测试放在 `src/androidTest`。项目目前没有声明覆盖率门槛；新增功能应至少覆盖核心逻辑和异常路径，涉及 UI 时补充关键交互验证。

## 提交与拉取请求

现有提交使用简短、面向结果的中文主题，例如“帧率检测以及anr消息回朔”“线程池优化”；保持单行、聚焦单一改动，避免无意义的 `update`。拉取请求应说明动机、影响模块、验证命令及设备/Android 版本；UI 变更附截图，JNI、线程或性能变更说明 ABI、基准结果或潜在回归，并关联对应 Issue（如有）。

## 配置与安全提示

`local.properties` 仅保存本机 SDK 路径，不要提交个人路径、密钥或其他凭据。提交前确认构建产物、IDE 文件和临时性能数据未被加入版本控制。

## 知识库维护规则

- 知识库及配套文档遵循平台根规则，通过 [knowledge-sync skill](../.agents/skills/knowledge-sync/SKILL.md) 手动更新；仅在用户明确要求或 OpenSpec 归档流程显式调用时执行。普通开发不自动同步文档，必要实现测试仍按本工程规则执行。以下归属和校验要求在执行 skill 更新文档时适用。
- `docs/knowledge-base/` 维护功能机制、源码调用链、生命周期、设计取舍和验证边界；总导航由该目录 README.md 维护。
- `docs/knowledge-base/16-SDK配置指南.md` 是配置字段、默认值与校验限制的详细参考；`docs/knowledge-base/17-FPS算法说明.md` 是 FPS 公式与统计口径的详细参考。知识库引用这些文档，避免重复维护完整参数表。
- 初始化与公共 API 对应知识库 02 和配置指南；Crash/Jank/FPS 对应 03/04/05；Looper 与线程/JNI 对应 06/07。
- 网络、队列、响应处理与数据模型对应 08/09；服务端契约核对平台根的 [API 文档](../docs/api/README.md)与[客户端接入文档](../docs/client-integration/README.md)，客户端与服务端差异必须明确记录。
- 示例、构建、依赖与测试入口对应 10/11；能力状态集中维护于 00，待处理差异和验证缺口集中维护于 12。
- 文档中的“已实现”“存在测试”“测试通过”“真机通过”“联调通过”分别需要对应证据。未运行的验证标注为未执行，不沿用历史成功记录替代当前结果。
- 新增专题必须加入总导航，使用仓库相对源码链接和函数名定位；平台内文档使用仓库相对链接，真正的跨仓库引用标明外部检出依赖。迁移文档时同步所有引用。
- 文档修改完成后检查本地链接、导航覆盖、代码/配置一致性并运行 `git diff --check`。纯文档修改不要求运行 Android 构建；涉及实现时按原测试指南验证。
- 文档示例使用占位凭据，不复制实际 App Key、设备隐私数据或机器专属运行产物。
