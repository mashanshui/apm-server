# Android 客户端接入

本目录维护客户端接入步骤、事件冻结、本地持久化、上传确认和联调方法。服务端完整字段、错误码和统计口径以 [API 文档](../api/README.md)为准；SDK 内部实现与测试由 [Android 知识库](../../android/docs/knowledge-base/README.md)维护。

## 使用现有 SDK

1. 在网页创建应用，填写实际 Android application ID，由应用 `OWNER`/`ADMIN` 在设置页取得 App Key。
2. 按 [SDK 模块化说明](../../android/docs/knowledge-base/15-SDK模块化拆分说明.md#接入方式)选择全量 SDK 或单项能力。当前模块 group 为 `com.shanshui.nativelib`；制品准备、Maven Local 和构建前提见 [Android 构建测试](../../android/docs/knowledge-base/11-构建测试与本地发布.md)。默认版本号不代表制品已发布远程仓库。
3. 按 [SDK 配置指南](../../android/docs/knowledge-base/16-SDK配置指南.md#1-最小初始化)在 `Application.onCreate()` 中初始化，配置可达的 `baseUrl`、App Key 和所需功能；同次发布保存 APK、`buildId` 与 mapping 的关联。
4. 按下表进入对应接入流程，结合[联调与验收](#联调与验收)确认本地队列和服务端结果。初始化成功、本地入队和服务端接收是不同状态。

## 按能力阅读

| 需要接入什么 | 客户端流程 | 正式契约 |
|---|---|---|
| JVM 致命 Crash 与启动事件 | [Crash 接入](crash-client-integration.md) | [Crash API](../api/crash-api.md)、[上报错误码](../api/crash-error-codes.md) |
| 卡顿个例、场景 FPS 与前台挂起 | [卡顿接入](jank-monitoring.md)、[ZIP manifest 构造](jank-artifact-manifest.md) | [卡顿压缩包 API](../api/stack-artifact-api.md)、[卡顿指标 API](../api/jank-server-api.md) |
| PSS、VSS 与 Java 堆运行峰值 | [内存峰值接入](memory-metrics.md) | [内存指标 API](../api/memory-metrics-api.md) |
| Activity 泄漏与 KOOM 异常报告 | [内存泄漏报告接入](memory-leak-reports.md) | [内存泄漏报告 API](../api/memory-leak-reports-api.md) |
| 发布构建与符号表 | [mapping 关联与生效边界](symbol-mapping.md) | [符号表 API](../api/symbol-api.md) |

直接对接 HTTP 的调用方也应遵守以下公共规则，具体请求体按各能力的正式契约构造。

## 事件身份生命周期

客户端首次安装生成并持久化安装级 UUID v4 `anonymousDeviceId`，各进程共用；SDK 在进程内缓存本次运行的 UUID v4 `sessionId`。主进程的 `processId` 等于本次 `sessionId`，子进程每次创建独立生成 UUID v4，不能使用 Android 数值 PID 或进程名称。

事件、ZIP 和报告进入本地队列时冻结身份、`eventId`、原发生时间、版本与 `buildId`。跨启动补传和升级后重试均沿用原事件，不能替换为上传时进程或新版本的信息。各信号当前恢复实现及缺口见 [Android 待验证事项](../../android/docs/knowledge-base/12-常见问题与待验证事项.md)。

服务端永久拒绝缺失、空值、数值 PID 和非 v4 `processId`；不会补造身份。内存泄漏报告在服务端的 `sessionId` 仍可选，其余入口按各自契约校验。身份创建实现见 [RuntimeIdentity](../../android/performance-core/src/main/java/com/shanshui/performance/identity/RuntimeIdentity.kt)。

## 应用 Key 与隐私

- 网页创建应用时绑定唯一的全小写 Android application ID；系统 UUID `appId` 是应用标识，`apm_ak_` 前缀的完整 App Key 才是 `X-App-Key` 请求头使用的凭据。事件和 manifest 的 `packageName` 必须与绑定包名完全一致。
- App Key 永久有效、不过期且不可轮换。不同包名变体分别创建应用；获取和授权规则见[应用管理 API](../api/app-api.md)。
- 示例应用通过本机 Gradle 属性将 Key 注入 `BuildConfig`，因此 APK 会包含上报 Key。它可被逆向取得，不能充当用户密码；真实值不得提交到源码、文档、版本库，也不得输出到日志、URL、异常正文、事件、ZIP 或持久诊断资料。
- `anonymousDeviceId` 使用匿名随机值，禁止使用 IMEI、广告 ID、账号或直接设备硬件标识。Crash、卡顿和内存指标的服务端哈希使用部署级盐，不包含 `appId`；内存泄漏报告不执行同一哈希及文本模式脱敏。处理边界见 [Crash API](../api/crash-api.md#脱敏与兼容性)和[内存泄漏报告 API](../api/memory-leak-reports-api.md#上报报告)。
- Android 上报 Key 仅用于接收入口；网页 Session、[应用查询 Token](../api/query-token-api.md)、[Agent HTTP](../api/agent-query-api.md)及 [MCP](../api/mcp-api.md)的授权各自独立。
- 当前部署协议和 HTTPS 验收状态见[平台状态](../knowledge-base/00-当前实现与验证边界.md#环境最近核验)。生产 HTTPS 由 Nginx 入口提供，客户端保持证书校验。

## 批量响应与队列确认

本节适用于 Crash、启动、场景 FPS、前台挂起和内存峰值的 `POST /ingest/v1/batches`。JSON 使用 v2 信封；服务端支持 JSON 与 JSON+gzip，当前 Android SDK 默认发送普通 JSON。ZIP 和 multipart 使用各自的确认规则。

1. 先可靠落盘，再发送；设置单批数量、字节、队列配额、保留期和重试上限。`requestId` 用于关联本次发送，`eventId` 用于事件幂等，重试不能重新生成事件。
2. HTTP 200 且正文可解析后，先核对响应的 `requestId`、非负的 `accepted`/`rejected`/`duplicate`、三者之和与批次事件数，以及拒绝计数与错误定位的对应关系。当前 Crash 和内存峰值要求 `requestId` 完全相同；FPS 在响应返回非空 `requestId` 时核对。
3. `errors[].index` 必须在本批范围内；使用 `eventId` 定位时必须能匹配本批事件，二者同时出现时必须一致。Crash 允许同一拒绝事件对应多条字段错误，按事件归并；FPS 与内存峰值当前要求错误定位唯一。不能用一套错误条数规则替代各信号的检查。
4. 只有完整校验通过后，才能确认未被拒绝的 accepted/duplicate 事件。事件级永久错误移入有数量限制的隔离区，可重试错误保留原事件；响应缺失、标识或计数不匹配、错误无法定位、非预期成功响应均不得确认删除，应保留整批进入有上限的重试。
5. 网络中断、超时和可重试服务端错误保留原内容，使用 `Retry-After` 与退避策略。批次级永久错误停止原样重试并隔离受影响批次，不能清空其他正常队列。具体状态和错误码按各 API 判断。

当前确认实现见 [CrashUploader](../../android/performance-crash/src/main/java/com/shanshui/performance/crash/CrashUploader.kt)、[FpsUploader](../../android/performance-metrics/src/main/java/com/shanshui/performance/fps/FpsUploader.kt)和 [MemoryUploader](../../android/performance-metrics/src/main/java/com/shanshui/performance/memory/MemoryUploader.kt)。各信号的历史过期规则见各接入页，不能统一套用同一保留天数。

## 联调与验收

| 检查 | 客户端和服务端应共同确认 |
|---|---|
| 首次与重复 | 首次 accepted，同一事件重传 duplicate，统计不重复增加 |
| 内容与身份 | JSON v2、ZIP v3、包名和 UUID 身份符合契约；错误 Key、错误包名、非法字段均按对应错误语义处理 |
| 中断与恢复 | 断网、响应丢失、服务重启和客户端重启后保留原事件；永久错误与暂时失败不误确认 |
| 产物一致性 | ZIP 三个固定条目、大小、SHA-256、消息边界与 processor 输入一致；APK、buildId 和 mapping 来自同次构建 |
| 隐私 | 日志和持久诊断资料无完整 Key、原始硬件标识、完整 ZIP 或敏感正文；APK 内的构建注入 Key 不作为“零 Key”验收项 |

已有测试与待验收范围分别见 [Android 构建测试](../../android/docs/knowledge-base/11-构建测试与本地发布.md)、[Android 待验证事项](../../android/docs/knowledge-base/12-常见问题与待验证事项.md#客户端接入待验收)和[平台待确认事项](../knowledge-base/13-待确认事项.md)。本机代码、主机自动化、真实设备、联合验收和生产部署分别记录，不能互相替代。

固定样本见[数据集](../datasets/README.md)，独立跨端证据见[验收资料](../validation/README.md)，内存峰值共同证据见[统一验收](../validation/memory-runtime-peaks/README.md)。当前环境与 processor 发布状态统一从[平台状态](../knowledge-base/00-当前实现与验证边界.md)及[二进制解析待确认事项](../knowledge-base/13-待确认事项.md#二进制堆栈解析)查阅。

## 历史制品

`1.0.0/` 保留旧单体的 [AAR](1.0.0/nativelib-1.0.0.aar)、[sources JAR](1.0.0/nativelib-1.0.0-sources.jar)、[POM](1.0.0/nativelib-1.0.0.pom)和 [Gradle Module Metadata](1.0.0/nativelib-1.0.0.module)。元数据坐标为 `com.example.nativelib:nativelib:1.0.0`；来源、用途与当前协议的对应关系尚未确认。当前 SDK 接入使用本页开头的模块化说明，这些历史文件不作为推荐依赖。
