# ANR 上报与查询 API

[返回 API 目录](README.md) · [Android 接入](../client-integration/anr-local-collection.md)

ANR 使用独立事件类型，不计入 Crash 事件。客户端可通过公共批次接收入口上传；网页查询使用登录会话和应用查看权限，不使用 App Key。平台当前的验证与部署边界见[能力状态](../knowledge-base/00-当前实现与验证边界.md)。

## 上报

`POST /ingest/v1/batches` 使用 `X-App-Key`、`application/json` 和 Schema v2 信封。服务端接受普通 JSON 或单层 gzip；编码、公共批次大小、响应和通用错误见[批次传输契约](crash-api.md#内容编码与大小限制)及[上报错误码](crash-error-codes.md)。下面的时间仅为示例，实际请求应使用事件发生时的 Unix 毫秒：

```json
{
  "requestId": "request-example",
  "events": [{
    "schemaVersion": 2,
    "eventId": "anr-example-1",
    "eventType": "anr",
    "occurredAt": 1795564800000,
    "sessionId": "11111111-1111-4111-8111-111111111111",
    "processId": "11111111-1111-4111-8111-111111111111",
    "anonymousDeviceId": "22222222-2222-4222-8222-222222222222",
    "packageName": "com.example.app",
    "appVersion": "1.0",
    "versionCode": 1,
    "buildId": "example-build",
    "environment": "test",
    "channel": "test",
    "osVersion": "35",
    "deviceModel": "example-device",
    "anr": {
      "source": "message_timeout",
      "mainThreadStack": "com.example.Main.run(Main.java:1)",
      "mainThreadStackTruncated": false,
      "anrInfo": {"status": "unavailable", "reason": "not_found", "truncated": false},
      "trace": {"status": "ok", "truncated": false, "source": "art_first_write", "text": "示例 trace 片段"}
    }
  }]
}
```

`eventId`、`sessionId`、`anonymousDeviceId`、`packageName`、`appVersion`、非负 `versionCode`、`buildId`、`environment`、`channel`、`osVersion` 和 `deviceModel` 为必填；`processId` 必须是 UUID v4。事件发生时间不得早于接收时 30 天，未来时间沿用公共容差。包名必须与凭据绑定的应用一致；`anr` 不能与其他事件专属载荷同时出现。

`anr.source` 为 `message_timeout` 或 `process_error_state`。`mainThreadStackTruncated` 必填；`mainThreadStack`、`message`、`messageWhenMillis`、`foreground`、`cgroup`、`anrInfo`、`trace` 和 `diagnostics` 可缺省。文本分区的 `status` 为 `ok`、`partial`、`unavailable` 或 `skipped`，并带 `truncated`；非 `ok` 分区须给出受控 `reason`，有内容的 trace 须标记 `source=art_first_write`。`capturedAt` 为可选的原进程采集时间（Unix 毫秒）。通用现场复用 [Crash 诊断字段](crash-api.md#批量上报)中的日志、内存、文件描述符、进程与设备分区，ANR 不接受 Crash 的线程分区。

服务端按实际 JSON 序列化的 UTF-8 字节校验：单事件最多 256 KiB，主线程栈 24 KiB、消息和 cgroup 各 4 KiB，`anrInfo` 对象 8 KiB、`trace` 对象 96 KiB、`diagnostics` 对象合计 96 KiB；各限制还受单事件总上限约束。状态或大小无效会按事件级错误拒绝，合法事件可按公共批次规则继续处理。写入前会执行已有可识别规则的脱敏；它不是对任意敏感信息的完全识别。

服务端以应用和事件 ID 为幂等身份，固定首次有效内容；持久化确认后返回 accepted，重复提交返回 duplicate，结果未知或存储不可用按公共可重试语义处理。详情和统计也按该身份去重。客户端应保留原身份和已冻结载荷直至得到有效确认。

## 查询

以下接口均使用网页登录会话及应用查看权限：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/apps/{appId}/anrs/overview` | 返回筛选窗口的 `eventCount` 和去重 `deviceCount` |
| `GET /api/v1/apps/{appId}/anrs/events` | 返回摘要 `events` 和可为空的 `nextCursor` |
| `GET /api/v1/apps/{appId}/anrs/events/{eventId}` | 返回脱敏原始 `metadata`、`anr` 及两份 Java 还原状态 |

概览和列表接受 `from`、`to`（ISO-8601 UTC 时间）、`appVersion` 和 `source`。时间左闭右开，默认以当前时间为结束、向前 24 小时，最长 30 天。列表额外接受 `limit`、`cursor` 和 `timeoutMs`；默认/最大页大小为 50/500，默认/最大超时为 2000/5000 毫秒，均受服务端配置约束。列表按发生时间、事件 ID 降序排列，游标绑定应用、时间窗口和筛选；改变条件后须从首页重新查询。列表摘要包含 `eventId`、`occurredAt`、`appVersion`、`versionCode`、`deviceModel` 和 `source`，不包含诊断正文。

详情的 `metadata` 包含原事件时间、版本、构建和身份，其中匿名设备 ID 为服务端保存的哈希值。`anr` 返回脱敏后的原文与分区状态。`mainThreadJava` 和 `traceJava` 分别包含 `status`、可选 `reason`、`text`、`symbolFileId`、`symbolFileRevision`、`symbolFileSha256`、`processedAt` 和 `retryAt`；状态为 `pending`、`failed`、`partial`、`symbolicated` 或 `not_applicable`。缺少或等待 mapping 时仍可查询原文。trace 的还原只针对可识别 Java 帧，其余线程、锁和 Native 记录保留原文；实际来源按应用、版本和构建选择，详见[符号表 API](symbol-api.md)。详情响应禁止缓存。

时间、来源、分页、超时或游标无效返回 400；事件不存在或无权访问应用时按权限边界返回错误，不泄露其他应用的诊断。查询结果是已接收事件和去重设备数，不是 ANR 率。
