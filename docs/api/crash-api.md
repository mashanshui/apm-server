# JVM Crash API

客户端接入请先阅读[Android JVM Crash 上传接入文档](../client-integration/crash-client-integration.md)；本文维护服务端 API 和统计口径。

## 批量上报

首期调试协议使用 JSON；Android SDK 可以将 JSON gzip 压缩后发送。服务端通过永久 appKey 识别应用，完整 Key 不写入日志。

```http
POST /ingest/v1/batches
Content-Type: application/json
Content-Encoding: gzip
X-App-Key: <appKey>
X-Schema-Version: 2
```

Crash 事件最小结构如下：

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

`X-App-Key` 必须是应用设置页获取的完整 Key。服务端先按 SHA-256 摘要从 PostgreSQL 得到唯一 `appId + packageName`，再读取正文。每个事件必须显式提供 `packageName`，且全部与 Key 绑定包名逐字符一致；任一不一致时整批返回 `403 PACKAGE_NAME_MISMATCH`，不进行部分接受或写入。服务端按 `appId + eventId` 去重，客户端临时失败重试时必须保留原 `eventId`。

批次响应包含 `accepted`、`rejected`、`duplicate`、`retryable`、`retryAfterSeconds` 和逐事件 `errors`。HTTP 401/503 发生在正文读取前；事件级校验错误使用 HTTP 200 的部分接受响应。

accepted 只表示当前原始链 pending 状态与不可变事实均已持久化，不表示还原完成。即使 mapping 已存在，上报请求也不执行 R8。原 eventId、构建和发生时间重试会修复缺失的一侧，不能把已成功事件重置为 pending。接收后的任务通知失败由持久化轮转对账补偿，不撤销已确认事实。v2 请求字段不增加。

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
| `stack` | `{ "format": "raw_chain", "content": CrashPayload }` 或 `{ "format": "retrace_text", "content": "完整还原文本" }` |
| `fingerprint`、`fingerprintVersion`、`groupingKind` | 同一已发布状态中的当前归组 |
| `symbolicationReason` | 安全等待/失败类别；初始 awaiting_processing 只表示等待后台处理 |
| `symbolFileId`、`symbolFileRevision`、`symbolFileSha256`、`processedAt` | 实际成功或最后尝试的来源与处理时间，不能用当前注册表代替 |

pending/failed 保留完整脱敏原始链，格式为 raw_chain；symbolicated 仅返回完整 retrace_text，来源字段和处理时间必须存在。详情和分析任务创建不调用 R8；成功后删除、替换或重新上传 mapping 都不改变该事件。完整原因链、内联和多候选不截断，处理完成不保证每帧都准确映射。响应不再包含 rawCrash、symbolicatedStackText 或其他旧字段别名。

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
- 属性名只接受字母、数字、下划线、点和连字符；测量值只保存数字。
- 指纹由服务端生成：`tmp-v1` 绑定应用和构建，未成功事件不跨构建合并；`resolved-v1` 使用还原后的异常链、规范化动态消息和关键调用身份，排除行号、应用版本、构建及 mapping 身份，候选集合按稳定规则表示。没有可靠应用帧时使用完整候选/帧回退，不能任取候选。
- `buildId` 是符号表精确匹配键，按结构化标识清理和限长，不套用面向自然语言的电话号码脱敏，避免合法构建标识被改写后无法匹配 mapping。
- 后台成功仅改变原发生日期的问题归属，不增加事件、设备、崩溃会话或启动分母。格式合法但不匹配 APK 的 mapping 可能造成不可纠正的首次成功结果；业务当前材料只有一份，不承诺旧物理版本和备份立即擦除。上传、删除及后台进度见[Android 符号表管理 API](symbol-api.md)。

服务端只接受 `schemaVersion=2`、`eventType=crash`、`crash.kind=jvm`、`crash.fatal=true`。旧 schema v1、正文旧字段 `appId`、native、ANR、非致命异常、Protobuf 和完整 Issue 生命周期均不兼容。
