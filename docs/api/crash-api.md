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

ClickHouse 查询将应用、时间窗和维度下推；Issue/事件列表不读取完整堆栈，只有详情读取异常链。单次查询预算由服务端配置，默认 2 秒、最大可请求 5 秒；初始扫描上限 500 万行/512 MiB、数据库内存 256 MiB、HTTP 响应 8 MiB。超时返回 `408 QUERY_TIMEOUT`，扫描、内存或结果字节超限返回 `422 QUERY_RESOURCE_LIMIT`，存储暂不可用返回 503；失败不以空数据或部分统计替代。大范围内即使列表 `limit` 较小也可能超过扫描预算。

上述 `QueryBudget` 当前应用于概览、趋势、Issue 和事件摘要查询；单事件详情仍使用普通 ClickHouse HTTP 调用，不应用同一组扫描/响应字节预算。请求可以把默认 2 秒延长至服务端最大 5 秒，不能调整扫描、内存或结果字节配置；实现与性能证据见[Crash 查询基线](../../backend/docs/knowledge-base/crash-query-performance-baseline.md)。

Crash 事件详情响应保留设备 ID、启动 ID和新增的进程实例 `processId`；存量记录没有该列时返回缺失值，不把 `sessionId` 自动填入。详情字段不提供身份关联跳转。详情还返回本次请求的 `symbolicationStatus`、`symbolicatedStackText`、`symbolFileId`、`symbolFileRevision` 和 `symbolicationReason`；这些字段来自实时 Retrace，不改变事件存储。

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
- 指纹由服务端生成，首期版本为 v1，忽略堆栈行号并归一化动态消息。
- `buildId` 是符号表精确匹配键，按结构化标识清理和限长，不套用面向自然语言的电话号码脱敏，避免合法构建标识被改写后无法匹配 mapping。
- 详情保留脱敏后的原始异常链，按事件 `buildId` 每次请求查找当前 mapping 并尝试 R8 Retrace；还原文本不保存。`mapping_missing`、`mapping_unavailable`、`parser_busy`、`output_limit` 和 `retrace_failed` 均降级为原始详情，上传或替换 mapping 后旧事件下次查询即可使用新版本。上传接口见[Android 符号表管理 API](symbol-api.md)。

服务端只接受 `schemaVersion=2`、`eventType=crash`、`crash.kind=jvm`、`crash.fatal=true`。旧 schema v1、正文旧字段 `appId`、native、ANR、非致命异常、Protobuf 和完整 Issue 生命周期均不兼容。
