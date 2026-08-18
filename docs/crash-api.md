# JVM Crash API

客户端接入请先阅读[Android JVM Crash 上传接入文档](crash-client-integration.md)；本文保留服务端 API 和统计口径说明。

## 批量上报

首期调试协议使用 JSON；Android SDK 可以将 JSON gzip 压缩后发送。生产上报使用项目 Key 识别项目，Key 不在日志中打印。

~~~http
POST /ingest/v1/batches
Content-Type: application/json
Content-Encoding: gzip
X-Project-Key: <project-key>
X-Schema-Version: 1
~~~

Crash 事件最小结构如下：

~~~json
{
  "requestId": "req-001",
  "events": [{
    "schemaVersion": 1,
    "eventId": "crash-001",
    "eventType": "crash",
    "occurredAt": 1786788600000,
    "sessionId": "session-001",
    "anonymousDeviceId": "device-001",
    "appId": "demo-app",
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
~~~

批次响应包含 accepted、rejected、duplicate、retryable 和逐事件 errors。服务端按 projectId + eventId 去重；客户端临时失败重试时必须保留原 eventId。

## 查询接口

~~~http
GET /api/v1/projects/{projectId}/crashes/overview
GET /api/v1/projects/{projectId}/crashes/trend?interval=hour
GET /api/v1/projects/{projectId}/crashes/issues
GET /api/v1/projects/{projectId}/crashes/issues/{fingerprint}/events
GET /api/v1/projects/{projectId}/crashes/events/{eventId}
~~~

查询支持 from、to（ISO-8601）、appVersion、channel、environment、osVersion、deviceModel、fingerprint、limit、cursor 和 timeoutMs。服务端默认要求 X-Project-Id，并可用 X-User-Project-Ids 进行开发期成员范围校验；无权限统一返回 404，避免泄露项目存在性。真实网页用户权限接入 Spring Security/OIDC 前，不应把这两个请求头当作最终身份认证。

## 统计公式

~~~text
startedSessions = distinct(sessionId where eventType = app_start)
crashedSessions = distinct(sessionId where eventType = crash)
crashEvents = distinct(eventId where eventType = crash)
affectedDevices = distinct(anonymousDeviceId where eventType = crash)
crashRatePer1000Sessions = crashedSessions / startedSessions * 1000
crashFreeSessionRate = 1 - crashedSessions / startedSessions
~~~

startedSessions=0 时两个比例字段返回 null，状态为 denominator_insufficient；没有任何事件时状态为 no_data。有有效启动分母但没有 Crash 时，比例为真实的零崩溃结果。

## 脱敏与堆栈

- anonymousDeviceId 使用项目盐的 SHA-256 保存；原始设备标识不进入分析存储。
- 异常消息替换邮箱、URL、用户路径、手机号、UUID，并按配置截断。
- 属性名只接受字母、数字、下划线、点和连字符；测量值只保存数字。
- 指纹由服务端生成，首期版本为 v1，忽略堆栈行号并归一化动态消息。
- 详情保留脱敏后的原始异常链，symbolicationStatus 首期为 raw_only；buildId 为后续 mapping 关联键。

## 兼容性

首期只接受 schemaVersion=1、eventType=crash、crash.kind=jvm、crash.fatal=true。native、ANR、非致命异常、Protobuf 和完整 Issue 生命周期暂不属于本变更范围。
