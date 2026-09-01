# 卡顿压缩包解析与落库 API

客户端流程见[Android 卡顿压缩包上传接入](../client-integration/stack-artifact-upload.md)，manifest 字段见[卡顿压缩包 manifest v3](../client-integration/jank-artifact-manifest.md)。本文是服务端 HTTP 契约的事实来源。

## 1. 能力边界

- 接口同步接收一个 `schemaVersion=3 / artifactType=RHEA_JANK` 的 `.rheajank.zip`。
- 服务端校验并解析 ZIP，将归一化后的卡顿事实、采样证据和详情按 `appId + eventId` 幂等写入事件仓库。
- 成功时只返回 `accepted` 或 `duplicate`，不返回完整 `RHEA_STACK_REPORT`。
- 首版不保存原始 ZIP，不引入对象存储、消息队列或异步任务。
- v1 `.rheatrace.zip`、v2 卡顿 manifest、旧媒体类型和旧报告响应不兼容。

服务端固定使用 `io.github.mashanshui:rhea-trace-processor:1.0.1`，并已用真实 v3 fixture 验证 processor 的严格解析、声明文件/采样字节和报告关键字段。当前验证使用本机 Maven Local；生产构建仍必须把同坐标制品放入受控 Maven 仓库，不得依赖开发机缓存或通过服务端兼容层放宽 v3 校验。

本次真实验证输入为 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip`：11,189 字节，SHA-256 为 `1445628B2FA6D4ED055FDEDE218F950B1B44D20FFE6AE990F05869ACEB6C16FE`；manifest 为 `schemaVersion=3`、`artifactType=RHEA_JANK`、`packageName=rhea.sample.android`、`eventId=demo-jank-2308233515248871`，且不含旧 `appId`。服务端实际解析到的 processor fat JAR SHA-256 为 `e31cc2b2bf9a4015419fa8d0cd07f89978f9000a8cc8bcf3952c1933e7a568f3`。

2026-09-01 使用 `scripts/start-dev.ps1 -StorageMode clickhouse` 完成本地真实 HTTP 验收：该 ZIP 首次返回 `200 accepted`，原始 ZIP 重传返回 `200 duplicate`；为同一应用和 `buildId` 预置 mapping 后，另一 eventId 返回 `200 accepted`，ClickHouse `FINAL` 查询确认 `symbolication_status=symbolicated`。未知格式 Key 返回 `401 INVALID_APP_KEY`，其他应用 Key 上传该 ZIP 返回 `403 PACKAGE_NAME_MISMATCH`，旧 v2 fixture 返回 `422 INVALID_JANK_MANIFEST`；服务重启后原始 ZIP 再传仍返回 `200 duplicate`，事件详情返回 200。appKey 仅在测试进程内存中使用，未写入输出。

## 2. 请求

```http
POST /ingest/v1/stack-artifacts:parse
Content-Type: application/vnd.shanshui.rheajank+zip
X-App-Key: <appKey>

<原始 .rheajank.zip 字节>
```

请求体必须是原始 ZIP 字节，不能使用 JSON、Base64、multipart 或外层 gzip。控制器在读取正文前通过 PostgreSQL 应用凭据摘要校验 Key，再校验媒体类型，并通过 `Content-Length` 与受限输入流执行默认 64 MiB 上限。Key 映射同时得到 UUID `appId` 和绑定 `packageName`。

客户端不发送 `X-Mapping-Id`。服务端从通过校验的 manifest 读取 `buildId`，按 `<mapping-root>/<appId>/<buildId>.txt` 查找应用隔离的 mapping；文件不存在时继续保存未解混淆证据，非法 buildId、路径越界或符号链接逃逸返回 422。

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
| `apm.stack-parser.mapping-root` | 空 | 应用 mapping 根目录；空表示不使用 mapping |

```bash
curl -X POST "http://localhost:8080/ingest/v1/stack-artifacts:parse" \
  -H "X-App-Key: <从应用设置页获取的 appKey>" \
  -H "Content-Type: application/vnd.shanshui.rheajank+zip" \
  --data-binary @demo.rheajank.zip
```
