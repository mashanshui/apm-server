# JVM Crash 上报错误码

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
| `MISSING_*` | 200 | 单条 | 否 | 缺少统计必需字段 |
| `UNSUPPORTED_CRASH_KIND` | 200 | 单条 | 否 | 首期不接受 native 等 Crash 类型 |
| `NON_FATAL_CRASH` | 200 | 单条 | 否 | 首期只接受 JVM 致命 Crash |
| `EVENT_TIME_TOO_OLD` / `EVENT_TIME_IN_FUTURE` | 200 | 单条 | 否 | 事件时间超出窗口 |
| `TOO_MANY_STACK_FRAMES` / `MESSAGE_TOO_LONG` / `EVENT_TOO_LARGE` | 200 | 单条 | 否 | 超出字段或事件大小限制 |
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
      "message": "首期仅支持 crash.kind=jvm",
      "retryable": false
    }
  ]
}
```

`duplicate` 表示服务端已经见过相同应用和 `eventId` 的逻辑事件。重复事件不会再次写入内存仓库，也不会再次放大统计；生产 ClickHouse 表使用 `ReplacingMergeTree` 配合查询去重。
