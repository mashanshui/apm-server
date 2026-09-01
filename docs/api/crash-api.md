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

- `anonymousDeviceId` 使用应用盐的 SHA-256 保存；原始设备标识不进入分析存储。
- 异常消息替换邮箱、URL、用户路径、手机号、UUID，并按配置截断。
- 属性名只接受字母、数字、下划线、点和连字符；测量值只保存数字。
- 指纹由服务端生成，首期版本为 v1，忽略堆栈行号并归一化动态消息。
- 详情保留脱敏后的原始异常链，`symbolicationStatus` 首期为 `raw_only`；`buildId` 为后续 mapping 关联键。

服务端只接受 `schemaVersion=2`、`eventType=crash`、`crash.kind=jvm`、`crash.fatal=true`。旧 schema v1、正文旧字段 `appId`、native、ANR、非致命异常、Protobuf 和完整 Issue 生命周期均不兼容。
