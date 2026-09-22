# 卡顿压缩包解析与落库 API

客户端流程见[Android 卡顿压缩包上传接入](../client-integration/stack-artifact-upload.md)，manifest 字段见[卡顿压缩包 manifest v3](../client-integration/jank-artifact-manifest.md)。本文是服务端 HTTP 契约的事实来源。

## 1. 能力边界

- 接口同步接收一个 `schemaVersion=3 / artifactType=RHEA_JANK` 的 `.rheajank.zip`。
- 服务端校验并解析 ZIP，将归一化后的卡顿事实、采样证据和详情按 `appId + eventId` 幂等写入事件仓库。
- 成功时只返回 `accepted` 或 `duplicate`，不返回完整 `RHEA_STACK_REPORT`。
- 首版不保存原始 ZIP，不引入对象存储、消息队列或异步任务。
- v1 `.rheatrace.zip`、v2 卡顿 manifest、旧媒体类型、数值 PID manifest 和旧报告响应不兼容。

服务端当前固定使用 `io.github.mashanshui:rhea-trace-processor:1.0.2`。该版本已支持客户端主线程-only v3 manifest 的 UUID v4 `processId` 与 `threadScope=main`，并已完成真实设备 ZIP 的本机解析和云端 HTTP/ClickHouse 闭环。生产制品仓库、容量、持久重试和 Android 长期运行仍需单独验收。

本次存档的真实验证输入为 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip`：11,189 字节，SHA-256 为 `1445628B2FA6D4ED055FDEDE218F950B1B44D20FFE6AE990F05869ACEB6C16FE`；manifest 为 `schemaVersion=3`、`artifactType=RHEA_JANK`、`packageName=rhea.sample.android`、`eventId=demo-jank-2308233515248871`，且 `processId` 是数值 PID。该记录只证明旧 processor 输入可解析，不证明当前 UUID v4 契约可用；processor fat JAR SHA-256 为 `e31cc2b2bf9a4015419fa8d0cd07f89978f9000a8cc8bcf3952c1933e7a568f3`。

此前 2026-09-01 的本地真实 HTTP 验收针对数值 PID 的旧 fixture，不能作为本变更 UUID v4 契约的通过证据。2026-09-15 使用客户端设备 ZIP `F:/AndroidStudioProjects/btrace/btrace-android/build/device-zip-demo-jank-3539183284831898/demo-jank-3539183284831898.rheajank.zip` 完成首次落库、同字节重复和 Jank 详情 HTTP 验收；manifest `processId` 原值为 `ec8237be-8ac8-4592-82ad-993b69ac3732`，详情和 ClickHouse `process_id` 均一致。

## 2. 请求

```http
POST /ingest/v1/stack-artifacts:parse
Content-Type: application/vnd.shanshui.rheajank+zip
X-App-Key: <appKey>

<原始 .rheajank.zip 字节>
```

请求体必须是原始 ZIP 字节，不能使用 JSON、Base64、multipart 或外层 gzip。控制器在读取正文前通过 PostgreSQL 应用凭据摘要校验 Key，再校验媒体类型，并通过 `Content-Length` 与受限输入流执行默认 64 MiB 上限。Key 映射同时得到 UUID `appId` 和绑定 `packageName`。

客户端不发送 `X-Mapping-Id`。服务端从通过校验的 manifest 读取 `buildId`，按符号表注册表的 `appId + buildId` 精确取得当前 mapping；文件不存在时继续保存未解混淆证据，非法 buildId 返回 422。网页上传和替换见[Android 符号表管理 API](symbol-api.md)。

## 3. manifest 与服务端派生字段

processor 报告中的 `sourceManifest` 必须是 v3 `RHEA_JANK`，并包含：

`schemaVersion`、`artifactType`、`eventId`、`occurredAt`、`sessionId`、`anonymousDeviceId`、`packageName`、`appVersion`、`versionCode`、`buildId`、`environment`、`channel`、`osVersion`、`deviceModel`、`scene`、`messageStartNs`、`messageEndNs`、`thresholdNs`、`minSampleIntervalNs`、`attemptedSampleCount`、`processId`、`files`。

其中 `packageName` 必须非空，并与 appKey 绑定的包名完全一致。缺失属于不可重试的 manifest 契约错误；不一致返回 `403 PACKAGE_NAME_MISMATCH`，且不生成指纹、不读取业务 mapping、不写入原始事实、卡顿事实或详情。manifest 不得包含旧 `appId` 字段。

`attemptedSampleCount` 仅用于满足 processor 的严格 manifest 契约。服务端业务映射、统计、指纹和存储不读取该客户端计数，而是根据报告中的目标主线程证据计算：

```text
messageDurationNs = messageEndNs - messageStartNs
parsedSampleCount = 目标主线程有效 segments 数量
expectedSampleCount = ceil(messageDurationNs / minSampleIntervalNs)
missingSampleCount = max(0, expectedSampleCount - parsedSampleCount)
```

所有 processor 保留的有效事件类型都计入 `parsedSampleCount`；其他线程记录和 `attemptedSampleCount` 均不参与统计。`missingSampleCount` 只表示理论槽位与已解析证据的差值，不是客户端确认的丢弃次数。

## 4. 成功响应与幂等

首次完整写入：

```json
{"success": true, "status": "accepted"}
```

相同 `appId + eventId` 再次上传，或重试补齐上次留下的事实/详情：

```json
{"success": true, "status": "duplicate"}
```

两种结果均为 HTTP 200。响应不会包含 appKey、包名、mapping 状态、设备信息、堆栈或解析报告。客户端收到任一结果后可以删除对应本地队列项；网络中断或 503 时必须保留原 ZIP 和原 `eventId` 重试。

## 5. 错误语义

| HTTP | code | 是否重试 | 说明 |
|---:|---|:---:|---|
| 401 | `INVALID_APP_KEY` | 否 | appKey 缺失、格式错误或不存在；鉴权先于正文读取 |
| 403 | `PACKAGE_NAME_MISMATCH` | 否 | manifest `packageName` 与 appKey 绑定包名不同；整请求零写入 |
| 503 | `APP_AUTH_UNAVAILABLE` | 是 | PostgreSQL 鉴权暂时不可用；`Retry-After: 30` |
| 400 | `INVALID_STACK_ARTIFACT_REQUEST` | 否 | 请求体为空等请求级错误 |
| 413 | `PAYLOAD_TOO_LARGE` | 否 | 声明长度或实际读取超过上限 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 否 | 不是新的 `.rheajank.zip` 媒体类型 |
| 422 | `INVALID_STACK_ARTIFACT` | 否 | ZIP、manifest、哈希或 Sampling 无法解析 |
| 422 | `UNSUPPORTED_JANK_ARTIFACT` | 否 | 不是 v3 `RHEA_JANK` |
| 422 | `INVALID_JANK_MANIFEST`、`INVALID_JANK_TIME_RANGE` | 否 | 必填事实或时间区间非法 |
| 422 | `INVALID_JANK_EVIDENCE`、`JANK_EVIDENCE_LIMIT_EXCEEDED` | 否 | 主线程证据非法或规模超限 |
| 422 | `INVALID_BUILD_ID`、`INVALID_MAPPING_PATH` | 否 | mapping 选择不安全 |
| 503 | `STACK_PARSER_BUSY` | 是 | 同步解析许可已满，`Retry-After: 1` |
| 503 | `EVENT_STORE_UNAVAILABLE` | 是 | 存储失败，`Retry-After: 30` |

错误响应和应用日志不会回显 appKey、ZIP 内容、完整堆栈、processor 内部异常或服务端文件路径。

## 6. 配置与示例

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `apm.stack-parser.max-artifact-bytes` | `67108864` | 压缩请求体最大字节数 |
| `apm.stack-parser.max-concurrent-parses` | `2` | 同步解析并发许可数 |
| `apm.symbol.directory` | `build/symbols` | 网页上传 mapping 的受控文件目录；生产按 `appId + buildId` 从符号表注册表选择，不读取客户端路径 |

`apm.stack-parser.mapping-root` 仅保留给旧的 resolver 单元测试构造器，生产卡顿入口不再按该目录查找 mapping。文件卷、大小和并发配置见[符号表管理 API](symbol-api.md)。

```bash
curl -X POST "http://localhost:8080/ingest/v1/stack-artifacts:parse" \
  -H "X-App-Key: <从应用设置页获取的 appKey>" \
  -H "Content-Type: application/vnd.shanshui.rheajank+zip" \
  --data-binary @demo.rheajank.zip
```
