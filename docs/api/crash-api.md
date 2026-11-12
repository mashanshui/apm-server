# Crash API

客户端接入请先阅读[Android JVM Crash 上传接入文档](../client-integration/crash-client-integration.md)；本文维护服务端 API 和统计口径。

## 批量上报

请求正文是 UTF-8 JSON 批次，包含 `requestId` 和 `events` 数组；每个 Crash 的补充现场位于该事件的 `crash.diagnostics` 对象。当前 Android SDK 默认发送普通 JSON；服务端也接受 gzip 压缩的 JSON，只有实际压缩时才设置 `Content-Encoding: gzip`。Native 客户端采集、重启恢复和服务端接收已实现；API 29/35 模拟器已完成隔离服务直传与查询，范围见[验证记录](../validation/native-crash/README.md)。服务端通过永久 appKey 识别应用，完整 Key 不写入日志。

```http
POST /ingest/v1/batches
Content-Type: application/json
X-App-Key: <appKey>
X-Schema-Version: 2
```

JVM Crash 事件最小结构如下：

```json
{
  "requestId": "req-001",
  "events": [{
    "schemaVersion": 2,
    "eventId": "crash-001",
    "eventType": "crash",
    "occurredAt": 1786788600000,
    "sessionId": "session-001",
    "processId": "11111111-1111-4111-8111-111111111111",
    "anonymousDeviceId": "device-001",
    "packageName": "com.example.app",
    "appVersion": "3.2.0",
    "versionCode": 320,
    "buildId": "build-320",
    "environment": "production",
    "channel": "official",
    "osVersion": "16",
    "deviceModel": "Pixel-8",
    "crash": {
      "kind": "jvm",
      "fatal": true,
      "throwableChain": [{
        "type": "java.lang.IllegalStateException",
        "message": "sanitized message",
        "frames": [{
          "className": "com.example.PaymentActivity",
          "methodName": "submit",
          "fileName": "PaymentActivity.kt",
          "lineNumber": 120,
          "applicationFrame": true
        }]
      }]
    }
  }]
}
```

七类分区的完整安全合成请求见 [Crash 诊断批次 JSON 样例](../examples/crash-diagnostics-batch.json)；实际联调时将 `occurredAt` 换成当前发生时间并使用目标应用的 Key、包名和构建身份。样例不包含真实设备或业务数据。

### Native 载荷

Native 使用相同事件信封，`crash.kind=native`、`fatal=true`，以 `nativeCrash` 取代 `throwableChain`；两种载荷互斥。可运行的合成请求见 [Native Crash 批次样例](../examples/native-crash-batch.json)，使用时同样需要更新发生时间与应用身份。`nativeCrash` 各字段如下：

| 字段 | 内容 |
|---|---|
| `abi`、`systemTid` | 进程实际 ABI 和崩溃线程系统 TID；ABI 支持 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |
| `threadName`、`mainThread` | 可取得时的崩溃线程名称和主线程标记 |
| `signal` | 必填信号 `number`、`name`、`code`，可选 `codeName`、`faultAddress`、脱敏 `abortMessage` |
| `registers` | 采集状态及 `values` 寄存器名称到数值的映射；不可用时使用空对象和真实失败状态 |
| `modules` | 主辅帧涉及的有限模块字典，每项包含事件内 `id`、SO `name`、`abi`，可带脱敏 `path` 和 `elfBuildId`；未知 Build ID 省略 |
| `backtrace` | 崩溃线程的采集状态和有序 `frames`，每帧有 `index`，可带 `moduleId`、`relativePc`、`functionName`、`functionOffset`；无法回溯时允许带失败状态的空帧 |
| `javaStack` | 可靠关联崩溃 TID 时的 Java 帧及采集状态；带非空帧时，`diagnostics.crashedThread` 必须包含一致的 `systemTid` 与 `javaId` |

地址、寄存器、相对 PC 和偏移使用小写规范十六进制字符串，例如 `0x0`、`0xffffffffffffffff`，按 ABI 的 32 或 64 位范围校验；不转换为 JavaScript 数字。所有 `moduleId` 必须引用本事件模块字典；应用 `buildId` 和模块 `elfBuildId` 是不同身份。现场函数名不代表服务端已完成符号还原。

Native 载荷、诊断和通用属性采用严格字段边界；不接受原始栈内存 `stack`、寄存器附近内存 `memory near` 或完整 `memory map` 转储。服务端还按模式检查自由文本，拒绝可识别的转储标题、映射行及含日志标签的两列栈内存行；模式无法保证识别所有伪装形式。未知结构字段按批次返回 `INVALID_BATCH`；合法结构中的地址、引用、互斥或诊断语义错误按事件拒绝。

Android 客户端仅在 SIGABRT 消息完整可读、长度不超过 255 字节、内容为可打印 ASCII 且未命中敏感信息规则时填充 `abortMessage`；其他情况省略。该字段是可选诊断，不保证所有系统或崩溃均可取得。

`diagnostics` 可缺失或为 null；存在时各分区可以独立缺失或为 null。`app_start` 和其他事件类型不得携带 `crash`。上报与详情都使用下列语义，其他线程在上报时携带 `frames`，查询时则返回当前唯一 `stack`：

| 字段 | 上报内容与单位 |
|---|---|
| `crashedThread` | 崩溃线程的 `javaId`、名称、状态、优先级、是否守护/主线程，以及可取得时的数值 `systemTid`；`javaId` 是十进制字符串，不等于系统 TID |
| `otherThreads` | 其他线程列表、可知的 `observedCount`/`omittedCount`，每线程最多 32 个 Java `frames`；空数组表示没有取得帧 |
| `nativeThreads` | Native 崩溃线程以外的线程和各自 `backtrace`，以 `systemTid` 区分，同名线程不合并 |
| `logcat` | `main`、`system`、`events` 三个独立缓冲区；每个 `lines` 是已脱敏字符串数组 |
| `memory` | Runtime Java 堆、Debug PSS、Native 堆和进程 RSS/VSS/Swap，数值均为字节；可包含脱敏且受限的 proc 文本 |
| `openFiles` | 可知的 FD 总数与受限 `entries`；目标只保留 `[path]`、`[socket]` 等安全类别 |
| `process` | 数值 `pid`、进程名、可知前后台、可靠的 `startedAt` 和 SDK 的 `sdkInitializedAt`，时间均为 Unix 毫秒；未知值不猜测 |
| `device` | API level、ABI、厂商、品牌和系统构建标识 |
| `coreTruncation` | 主异常因源端大小限制裁剪时为 `{ "truncated": true, "reason": "size_limit" }` |

各采集分区携带 `status=ok|partial|unavailable|skipped`、`truncated`，可带安全 `reason` 和 Unix 毫秒 `capturedAt`。`ok` 且空数组表示采集成功但结果为空；`unavailable`、`skipped` 或分区缺失不能解释为成功空结果。原因类别包括 `permission_denied`、`unsupported`、`timeout`、`io_error`、`memory_pressure`、`interrupted`、`size_limit`、`count_limit`。数值零是实际值，缺失或 null 表示未知。

Native 事件的 `otherThreads` 是 Java 快照，每条必须有 `relationToCrash=other|unknown`，可附可靠的 `systemTid`；不能根据同名线程推断关联，也不能把已知崩溃 TID 放入辅助线程。`nativeThreads` 以 TID 去重并排除崩溃线程。不同线程的快照不表示同一瞬间采集。

默认服务端限制单事件 JSON 为 256 KiB，JVM 主异常独立限制为 16 层、总计 200 帧；Native 主栈和模块各最多 128 项。诊断对象最多 128 KiB；Native 与 Java 其他线程合计最多 64 条、每条 32 帧且合计最多 48 KiB；logcat 的 main/system/events 分别最多 200/50/50 行、合计最多 48 KiB、每行 JSON 编码最多 2 KiB；内存最多 16 KiB，FD 最多 128 条及 12 KiB，崩溃线程/进程/设备合计最多 4 KiB。字节限额按 JSON 转义后的 UTF-8 计算，服务端拒绝超限内容；Android 客户端把单事件控制在 240 KiB 内并核对实际批次上限。完整字段类型与数量约束见 [JSON Schema](../../backend/src/main/resources/schema/crash-event-v2.schema.json)，跨字段状态与字节数仍由服务端校验。

`X-App-Key` 必须是应用设置页获取的完整 Key。服务端先按 SHA-256 摘要从 PostgreSQL 得到唯一 `appId + packageName`，再读取正文。每个事件必须显式提供 `packageName`，且全部与 Key 绑定包名逐字符一致；任一不一致时整批返回 `403 PACKAGE_NAME_MISMATCH`，不进行部分接受或写入。服务端按 `appId + eventId` 去重，客户端临时失败重试时必须保留原 `eventId`。

批次响应包含 `accepted`、`rejected`、`duplicate`、`retryable`、`retryAfterSeconds` 和逐事件 `errors`。HTTP 401/503 发生在正文读取前；事件级校验错误使用 HTTP 200 的部分接受响应。

accepted 表示主栈、原始辅助帧和不可变诊断上下文均已确认持久化，不表示还原完成。即使 mapping 已存在，上报请求也不执行 R8。相同 `appId + eventId` 的重试必须保留原事件身份与内容；相同内容可修复部分写入，已有完整事件返回 duplicate，不会追加新现场。内容不一致返回不可重试的 `CRASH_CONTENT_CONFLICT`。历史事件缺失诊断时不能用重试追加。JVM 接收后的任务通知失败由持久化轮转对账补偿；Native 不查询 mapping 或唤醒 JVM 后台工作。

## 查询接口

```http
GET /api/v1/apps/{appId}/crashes/overview
GET /api/v1/apps/{appId}/crashes/trend?interval=hour
GET /api/v1/apps/{appId}/crashes/issues
GET /api/v1/apps/{appId}/crashes/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/crashes/events/{eventId}
```

查询支持 `from`、`to`（ISO-8601）、`appVersion`、`channel`、`environment`、`osVersion`、`deviceModel`、`fingerprint`、`limit`、`cursor` 和 `timeoutMs`。网页查询必须携带登录 Session，并由服务端依据当前用户与 `app_member` 的成员关系授权；无成员关系统一返回 404，避免泄露应用存在性。`X-App-Id`、`X-User-App-Ids` 和 `X-App-Key` 不参与网页查询授权。

Issue 与事件列表的 `nextCursor` 是版本化不透明值，绑定当前应用、首次实际绝对时间窗、筛选和排序；继续请求可传同一 `from`/`to`，若首次省略，后续也可省略，服务端从游标恢复原窗口。旧版纯指纹/事件 ID 游标不再兼容，返回 `400 INVALID_CURSOR`；跨应用、跨指纹或更改筛选同样返回该错误。网页显示“重新查询”操作，不会悄悄回到第一页。事件按发生时间降序、事件 ID 升序；Issue 按事件数降序、最后出现时间降序、指纹升序。分页未持久化快照，新增或迟到数据可能改变后续页排行；需要一致对照时重新查询同一绝对时间窗。

ClickHouse 只查询新 `apm_crash_fact` 及最新 `apm_crash_state`，在数据库侧完成应用、时间和维度筛选、最新状态选择、精确统计与分页；Issue/事件摘要不读取堆栈。默认查询预算为 2 秒、最大可请求 5 秒，扫描上限 500 万行/512 MiB、数据库内存 256 MiB、HTTP 响应 8 MiB。超时返回 `408 QUERY_TIMEOUT`，扫描、内存或结果字节超限返回 `422 QUERY_RESOURCE_LIMIT`，存储暂不可用返回 503；失败不以空数据或部分统计替代。大范围内即使列表 `limit` 较小也可能超过扫描预算。

上述 `QueryBudget` 也应用于单事件详情。详情先定位不变事实，再按应用、构建、发生时间与 eventId 读取一份最新状态；不加载整个应用的堆栈。请求不能调整扫描、内存或结果字节配置；实现与性能证据见[Crash 查询基线](../../backend/docs/knowledge-base/crash-query-performance-baseline.md)。旧 Crash、旧启动会话及旧小时聚合不混入新数据集，缺少新结构直接失败，不回退旧表。

Issue 摘要增加 `groupingKind=temporary|resolved`，temporary 必须附 `buildId`，resolved 的 buildId 为空；事件摘要也包含 groupingKind 和持久化三态。临时指纹被移空后返回空事件列表，不伪造一对一跳转；已知 eventId 的详情仍可访问。后台归组变化与新增/迟到事件一样属于非快照分页边界。

详情保留设备 ID、启动 ID和进程实例 `processId`，不生成身份关联跳转，只返回一份 `stack`：

| 字段 | 当前语义 |
|---|---|
| `crashKind`、`fatal` | 不变事件资格，独立于原始载荷是否还存在 |
| `symbolicationStatus` | `pending`、`failed`、`symbolicated`，没有 raw_only 适配 |
| `stack` | JVM 为 `raw_chain` 原始异常链或 `retrace_text` 还原文本；Native 为 `{ "format": "raw_native", "content": nativeCrash }` |
| `fingerprint`、`fingerprintVersion`、`groupingKind` | 同一已发布状态中的当前归组 |
| `symbolicationReason` | 安全等待/失败类别；初始 awaiting_processing 只表示等待后台处理 |
| `symbolFileId`、`symbolFileRevision`、`symbolFileSha256`、`processedAt` | 实际成功或最后尝试的来源与处理时间，不能用当前注册表代替 |
| `diagnostics` | 独立的诊断对象；历史或未采集事件为 null，不嵌入主 `stack` |

JVM pending/failed 保留完整脱敏原始链，格式为 raw_chain；symbolicated 仅返回完整 retrace_text，来源字段和处理时间必须存在。Native 始终返回 `pending`、`raw_native`、`temporary` 和 `native_symbolication_deferred`，该状态表示本期未提供 Native 符号还原，不表示已经进入 JVM 队列；符号来源与处理时间为空。详情和分析任务创建不调用 R8；JVM 成功后删除、替换或重新上传 mapping 都不改变该事件。响应不再包含 rawCrash、symbolicatedStackText 或其他旧字段别名。

详情中的 `diagnostics.otherThreads.threads[]` 保留线程身份，并以 `stack: {format, content}` 返回唯一当前栈：`raw_frames` 对应原始帧数组，`retrace_text` 对应完整还原文本。每个线程独立携带 `symbolicationStatus=pending|failed|symbolicated|not_applicable`、安全原因和实际 mapping 来源；空帧为 `not_applicable`。主异常首次还原成功时，辅助线程在同一 mapping 下依次处理，成功或失败结果随主结果固定；辅助失败保留原始帧，不影响主异常归组和指纹。线程快照可能与抛出异常并非同一瞬间。

Native 事件的 Java 辅助线程始终保留原始帧，不进入 R8；非空帧的原因是 `native_symbolication_deferred`。`diagnostics.nativeThreads` 返回其他 Native 线程的系统 TID 与原始 `backtrace`，模块引用仍指向主 `stack.content.modules`。

## 统计公式

```text
startedSessions = distinct(sessionId where eventType = app_start)
crashedSessions = distinct(sessionId where eventType = crash)
crashEvents = distinct(eventId where eventType = crash)
affectedDevices = distinct(anonymousDeviceId where eventType = crash)
crashRatePer1000Sessions = crashedSessions / startedSessions * 1000
crashFreeSessionRate = 1 - crashedSessions / startedSessions
```

`startedSessions=0` 时两个比例字段返回 null，状态为 `denominator_insufficient`；没有任何事件时状态为 `no_data`。有有效启动分母但没有 Crash 时，比例为真实的零崩溃结果。

## 脱敏与兼容性

- `anonymousDeviceId` 使用部署级 `APM_DEVICE_HASH_SALT` 与去除首尾空白的设备标识拼接后计算 SHA-256，不包含 appId；原始设备标识不进入分析存储。
- `processId` 必须是标准连字符 UUID v4，服务端保留客户端原值；缺失、数值 PID 和非 v4 值按永久校验错误拒绝。
- 异常消息替换邮箱、URL、用户路径、手机号、UUID，并按配置截断。
- 诊断文本与私有路径在客户端持久化前和服务端存储前各做模式脱敏，覆盖 `X-App-Key`、独立的 `apm_ak_`/`apm_qt_` 凭据等已识别形式；日志和 FD 仍可能包含无法自动识别的业务敏感内容，业务方不应主动写入秘密。结构化 Java ID、PID、`processId`、`buildId` 和内存数值不套用自由文本替换。
- 属性名只接受字母、数字、下划线、点和连字符；测量值只保存数字。
- 指纹由服务端生成：`tmp-v1` 绑定应用和构建，未成功事件不跨构建合并；`resolved-v1` 使用还原后的异常链、规范化动态消息和关键调用身份，排除行号、应用版本、构建及 mapping 身份，候选集合按稳定规则表示。没有可靠应用帧时使用完整候选/帧回退，不能任取候选。
- Native 使用 `native-tmp-v1`：绑定应用、包名、构建、ABI、信号编号与 code，以及前八个主帧中可用的模块 ELF Build ID（缺失时为模块名）与相对 PC；故障地址、寄存器、函数显示名和辅助诊断不参与，无可用帧使用现场不足类别。
- `buildId` 是同应用版本构建专用 mapping 的精确匹配键，专用登记不存在时才使用该版本默认 mapping；事件构建仍必填。按结构化标识清理和限长，不套用面向自然语言的电话号码脱敏，避免合法构建标识被改写后无法匹配 mapping。
- 后台成功仅改变原发生日期的问题归属，不增加事件、设备、崩溃会话或启动分母。格式合法但不匹配 APK 的 mapping 可能造成不可纠正的首次成功结果；业务当前材料只有一份，不承诺旧物理版本和备份立即擦除。上传、删除及后台进度见[Android 符号表管理 API](symbol-api.md)。

服务端接收 `schemaVersion=2`、`eventType=crash`、`crash.kind=jvm|native`、`crash.fatal=true`。旧 schema v1、正文旧字段 `appId`、ANR、非致命异常、Protobuf 和完整 Issue 生命周期均不兼容；Native SO 文件管理已接入版本化符号表 API，Native 地址还原仍未实现。
