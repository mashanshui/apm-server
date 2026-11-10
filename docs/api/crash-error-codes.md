# Crash 上报错误码

首期上报接口为 `POST /ingest/v1/batches`，客户端需要根据错误是否可重试处理本地队列。永久错误丢弃当前事件；临时错误保留事件，并使用原始 `eventId` 重试。

| 错误码 | HTTP 状态 | 单条/批次 | 可重试 | 说明 |
|---|---:|---|---|---|
| `INVALID_APP_KEY` | 401 | 批次 | 否 | `X-App-Key` 缺失、格式错误或不存在；鉴权先于正文读取 |
| `PACKAGE_NAME_MISMATCH` | 403 | 批次 | 否 | 任一事件 `packageName` 与 appKey 绑定包名不同，整个批次零写入 |
| `APP_AUTH_UNAVAILABLE` | 503 | 批次 | 是 | PostgreSQL 应用鉴权暂时不可用，返回 `Retry-After: 30` |
| `INVALID_BATCH` | 400 | 批次 | 否 | JSON、gzip 或 `events` 结构非法 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 批次 | 否 | 首期只接受 JSON，可带 gzip |
| `PAYLOAD_TOO_LARGE` | 413 | 批次 | 否 | 压缩前请求、解压后请求或单事件超过上限 |
| `UNSUPPORTED_SCHEMA_VERSION` | 200/400 | 单条 | 否 | 服务端不支持该事件 Schema 版本 |
| `MISSING_PROCESS_ID` / `INVALID_PROCESS_ID` | 200 | 单条 | 否 | `processId` 缺失、为空、不是标准连字符 UUID v4 或使用数值 PID |
| `MISSING_*` | 200 | 单条 | 否 | 其他缺少统计必需字段 |
| `UNSUPPORTED_CRASH_KIND` | 200 | 单条 | 否 | 仅接受 `jvm` 和 `native` Crash 类型 |
| `NON_FATAL_CRASH` | 200 | 单条 | 否 | 仅接受致命 Crash |
| `INVALID_CRASH_PAYLOAD` | 200 | 单条 | 否 | JVM 与 Native 专属载荷混用或事件载荷不合法 |
| `INVALID_NATIVE_CRASH` | 200 | 单条 | 否 | Native 信号、架构位宽、模块引用、采集状态或禁止转储不合法 |
| `EVENT_TIME_TOO_OLD` / `EVENT_TIME_IN_FUTURE` | 200 | 单条 | 否 | 事件时间超出窗口 |
| `TOO_MANY_STACK_FRAMES` / `MESSAGE_TOO_LONG` / `EVENT_TOO_LARGE` | 200 | 单条 | 否 | 超出字段或事件大小限制 |
| `INVALID_CRASH_DIAGNOSTICS` | 200 | 单条 | 否 | 诊断状态、字段关系、数量或实际 JSON 字节超出限制 |
| `CRASH_CONTENT_CONFLICT` | 200 | 单条 | 否 | 同一应用及 eventId 的已保存或部分保存内容与重试不一致，不能用于追加诊断 |
| `EVENT_STORE_UNAVAILABLE` | 503 | 批次 | 是 | 返回 `Retry-After: 30` |
| `INGEST_RATE_LIMITED` | 429 | 批次 | 是 | 预留给应用配额和限流器 |

## 批次部分接受响应

```json
{
  "requestId": "req-001",
  "accepted": 1,
  "rejected": 1,
  "duplicate": 0,
  "retryable": false,
  "retryAfterSeconds": null,
  "errors": [
    {
      "index": 1,
      "eventId": "bad-event",
      "code": "UNSUPPORTED_CRASH_KIND",
      "message": "仅支持 JVM 或 Native 致命 Crash",
      "retryable": false
    }
  ]
}
```

`duplicate` 表示同一应用和 `eventId` 的事件材料已完整确认；相同内容重试不会放大统计。未知嵌套字段属于批次结构错误 `INVALID_BATCH`，合法结构中的诊断语义错误按事件索引返回；历史缺少诊断的事件不能通过重试补齐新现场。
