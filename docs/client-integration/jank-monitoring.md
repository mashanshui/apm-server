# Android 卡顿监控上传接入

本文说明客户端如何分别上传卡顿个例和卡顿指标。服务端查询字段与统计口径见[卡顿监控服务端 API](../api/jank-server-api.md)。

客户端必须在安装、启动和进程创建时维护 `anonymousDeviceId`、`sessionId` 和 `processId`：设备 ID 为安装级 UUID v4，启动 ID 为每次启动 UUID v4，主进程进程 ID 等于启动 ID，子进程每次创建使用新的 UUID v4。所有事件和 ZIP 入队后固定身份，不能使用 Android 数值 PID 或在重试时重写。

## 1. 两条接收链路

| 数据 | 接口 | 请求体 |
|---|---|---|
| 单个卡顿个例 | `POST /ingest/v1/stack-artifacts:parse` | 原始 `.rheajank.zip` |
| 场景 FPS、前台挂起汇总 | `POST /ingest/v1/batches` | JSON 或 JSON+gzip 批次 |

批量入口不再接受 `eventType=jank`。如果继续发送旧结构化 jank JSON，服务端会在 HTTP 200 的事件错误中返回不可重试的 `JANK_ARTIFACT_REQUIRED`。

## 2. 卡顿个例

卡顿个例的事件事实写入 ZIP 内 `manifest.json`，采样证据写入 `sampling.bin` 和 `sampling-mapping.bin`。客户端不展开 samples、stackDictionary 或调用树，也不计算服务端质量字段。

当前 processor 契约要求 manifest 携带 `attemptedSampleCount`。该字段会被 processor 读取和校验，但服务端业务映射、统计、指纹和存储全部忽略。正式质量字段由服务端派生：

```text
expectedSampleCount = ceil((messageEndNs - messageStartNs) / minSampleIntervalNs)
parsedSampleCount = processId 对应主线程的有效 segments 数量
missingSampleCount = max(0, expectedSampleCount - parsedSampleCount)
```

请求、队列、响应和重试规则见[Android 卡顿压缩包上传接入](stack-artifact-upload.md)，manifest 全部字段见[manifest v3 契约](jank-artifact-manifest.md)。

## 3. 指标批次公共信封

`frame_scene_summary` 和 `foreground_suspension_summary` 使用以下公共字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaVersion` | integer | 当前为 2 |
| `eventId` | string | 同一逻辑汇总重试时保持不变 |
| `eventType` | string | 两种指标类型之一 |
| `occurredAt` | integer，毫秒 | UTC Unix epoch 毫秒 |
| `sessionId` | string | 会话标识 |
| `processId` | string | 必填 UUID v4；主进程可等于 `sessionId`，子进程实例必须独立 |
| `anonymousDeviceId` | string | 安装级随机标识；服务端按应用盐哈希 |
| `packageName` | string | 必填，必须与 appKey 绑定的 Android application ID 完全一致 |
| `appVersion` / `versionCode` | string / integer | 发布版本 |
| `buildId` | string | 构建标识 |
| `environment` / `channel` | string | 环境和渠道 |
| `osVersion` / `deviceModel` | string | Android 与设备维度 |
| `networkType` | string，可选 | 低基数网络类型 |

事件只能携带与 `eventType` 对应的一个专用载荷。客户端不要上传用户输入、URL 查询参数、账号、手机号、邮箱或明文设备硬件标识。

## 4. 场景 FPS 汇总

`eventType=frame_scene_summary`，载荷字段：

| 字段 | 类型/单位 | 规则 |
|---|---|---|
| `scene` | string | 稳定、低基数场景名 |
| `algorithmVersion` | string | 当前白名单默认 `fps-v1` |
| `activeDurationMs` | integer，毫秒 | 大于 0 |
| `uiRefreshFrameCount` | integer | 大于 0 |
| `refreshRateHz` | number | 大于 0 |
| `normalizedFps60` | number | 非负；统一折算到 60 Hz 口径 |
| `frameDurationHistogram` | object，可选 | 最多 64 个白名单键，值为非负整数 |

## 5. 前台挂起汇总

`eventType=foreground_suspension_summary`，载荷字段：

| 字段 | 类型/单位 | 规则 |
|---|---|---|
| `algorithmVersion` | string | 当前白名单默认 `suspension-v1` |
| `foregroundDurationMs` | integer，毫秒 | 大于 0 |
| `suspensionDurationMs` | integer，毫秒 | 非负且不大于前台时长 |
| `suspensionCount` | integer | 非负 |
| `thresholdMs` | integer，毫秒 | 与服务端部署阈值一致；当前默认 200 |

服务端按应用、匿名设备、UTC 日期和稳定维度合并设备日，再计算秒/小时前台时长。客户端不要先计算应用级平均值。

## 6. 指标批次示例

```json
{
  "requestId": "metrics-20260830-0001",
  "events": [
    {
      "schemaVersion": 2,
      "eventId": "fps-0001",
      "eventType": "frame_scene_summary",
      "occurredAt": 1788080400000,
      "sessionId": "session-0001",
      "processId": "11111111-1111-4111-8111-111111111111",
      "anonymousDeviceId": "install-0001",
      "packageName": "com.example.app",
      "appVersion": "3.2.0",
      "versionCode": 320,
      "buildId": "build-320",
      "environment": "production",
      "channel": "official",
      "osVersion": "16",
      "deviceModel": "Pixel-8",
      "frameSceneSummary": {
        "scene": "checkout",
        "algorithmVersion": "fps-v1",
        "activeDurationMs": 10000,
        "uiRefreshFrameCount": 540,
        "refreshRateHz": 60.0,
        "normalizedFps60": 54.0
      }
    },
    {
      "schemaVersion": 2,
      "eventId": "suspension-0001",
      "eventType": "foreground_suspension_summary",
      "occurredAt": 1788080400000,
      "sessionId": "session-0001",
      "processId": "11111111-1111-4111-8111-111111111111",
      "anonymousDeviceId": "install-0001",
      "packageName": "com.example.app",
      "appVersion": "3.2.0",
      "versionCode": 320,
      "buildId": "build-320",
      "environment": "production",
      "channel": "official",
      "osVersion": "16",
      "deviceModel": "Pixel-8",
      "foregroundSuspensionSummary": {
        "algorithmVersion": "suspension-v1",
        "foregroundDurationMs": 3600000,
        "suspensionDurationMs": 2000,
        "suspensionCount": 4,
        "thresholdMs": 200
      }
    }
  ]
}
```

```http
POST /ingest/v1/batches
Content-Type: application/json
Content-Encoding: gzip
X-App-Key: <应用上报Key>
X-Schema-Version: 2
```

## 7. 批次响应与重试

```json
{
  "requestId": "metrics-20260830-0001",
  "accepted": 1,
  "rejected": 1,
  "duplicate": 0,
  "retryable": false,
  "retryAfterSeconds": null,
  "errors": [
    {"index": 1, "eventId": "bad-event", "code": "INVALID_SUSPENSION_DURATION", "message": "字段无效", "retryable": false}
  ]
}
```

- `accepted` 和 `duplicate` 对应事件可以从本地队列删除。
- `errors[].retryable=false` 是事件级永久错误，只隔离对应事件，不阻塞同批合法指标。
- 网络中断或 503 时保留原 eventId 重试；`APP_AUTH_UNAVAILABLE` 和 `EVENT_STORE_UNAVAILABLE` 当前带 `Retry-After: 30`。
- 401、403 `PACKAGE_NAME_MISMATCH`、400、413、415 属于批次级永久错误，应修复 Key、包名、协议或批次大小；403 会整批零写入。

## 8. 联调验收

- 卡顿 ZIP 首次上传返回 `accepted`，重复上传返回 `duplicate`，并能从事件详情读回 expected/parsed/missing。
- 向批量入口发送 `eventType=jank`，确认返回 `JANK_ARTIFACT_REQUIRED` 且不落库。
- 同一指标批次放入一条合法和一条非法事件，确认部分接受不受影响。
- 同一指标 eventId 重复发送，确认返回 duplicate 且统计不增加。
- 验证损坏 gzip、超限批次、无效应用 Key 和存储 503 的处理。
- 检查日志中不存在应用 Key、原始设备标识、ZIP、完整堆栈或业务正文。
- 检查三个身份字段在事件入队、跨启动补传和重试中保持不变；数值 PID、缺失和非 v4 `processId` 必须作为永久错误处理。

## 9. 协议资源

- [卡顿压缩包解析与落库 API](../api/stack-artifact-api.md)
- [卡顿监控服务端 API](../api/jank-server-api.md)
- [manifest v3 契约](jank-artifact-manifest.md)
- [frame_scene_summary JSON Schema](../../backend/src/main/resources/schema/frame-scene-summary-v2.schema.json)
- [foreground_suspension_summary JSON Schema](../../backend/src/main/resources/schema/foreground-suspension-summary-v2.schema.json)
