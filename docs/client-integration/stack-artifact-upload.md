# Android 卡顿压缩包上传接入

服务端完整契约见[卡顿压缩包解析与落库 API](../api/stack-artifact-api.md)，ZIP 内 `manifest.json` 字段见[manifest v3 契约](jank-artifact-manifest.md)。

## 1. 当前分工

一个卡顿个例对应一个 `.rheajank.zip`。客户端负责在本地稳定生成并持久化 ZIP；服务端负责解析 Sampling 数据、计算采样质量、生成指纹并幂等落库。客户端只处理上传成功或失败，不解析成功响应中的堆栈。

`POST /ingest/v1/batches` 不再接收 `eventType=jank`，但仍用于 Crash、启动、场景 FPS 和前台挂起汇总。

客户端生成 ZIP 时必须把安装级 `anonymousDeviceId`、启动级 `sessionId` 和进程实例 UUID v4 `processId` 一并写入 manifest 并冻结；主进程可以令 `processId=sessionId`，子进程每次创建生成新的 UUID，不能写入 Android 数值 PID。重试沿用原 ZIP 字节和身份字段。

## 2. 上传前持久化

1. 在同一临时目录生成 `sampling.bin` 和 `sampling-mapping.bin`。
2. 文件关闭后计算真实大小和 SHA-256，再生成 `manifest.json`。
3. ZIP 只写入这三个固定条目，关闭并刷盘后原子重命名为 `<eventId>.rheajank.zip`。
4. 本地队列保存最终路径、`eventId`、重试次数和下次重试时间；重试不得重新生成 ZIP 或 eventId。

`attemptedSampleCount` 仍必须写入 manifest，因为 processor 会读取并校验它；服务端业务逻辑不使用也不保存该值。不要为服务端补算 `parsedSampleCount` 或 `missingSampleCount`。

## 3. HTTP 请求

先在网页创建应用并填写正确 Android application ID；名称和描述可选，只有包名必填。再由该应用 `OWNER`/`ADMIN` 在应用设置页查看 appKey。appKey 永久有效、不可轮换，并只允许上传 manifest `packageName` 与绑定包名完全一致的产物。当前服务端固定使用 `io.github.mashanshui:rhea-trace-processor:1.0.2`，已支持 UUID v4、`threadScope=main` 的主线程-only v3 产物，并已用客户端设备 ZIP 完成真实 UUID HTTP/ClickHouse 验收；生产制品仓库、Android 长期运行和持久重试仍需单独验收。

```http
POST /ingest/v1/stack-artifacts:parse
Content-Type: application/vnd.shanshui.rheajank+zip
X-App-Key: <应用上报Key>

<原始 ZIP 字节>
```

- 请求体直接流式发送文件字节，不使用 Base64、JSON、multipart 或外层 gzip。
- 不发送 `X-Mapping-Id`。服务端按认证应用和 manifest `buildId` 选择可选 mapping。
- 客户端建议在发送前检查文件非空且不超过部署给出的上限；当前服务端默认压缩体上限为 64 MiB。
- 日志只记录 eventId、HTTP 状态和稳定错误码，不记录应用 Key、绝对路径、ZIP 内容或完整堆栈。

Android/OkHttp 示例：

```kotlin
private val mediaType = "application/vnd.shanshui.rheajank+zip".toMediaType()

fun uploadJankArtifact(baseUrl: HttpUrl, appKey: String, zip: File): Request {
    return Request.Builder()
        .url(baseUrl.resolve("/ingest/v1/stack-artifacts:parse")!!)
        .header("X-App-Key", appKey)
        .post(zip.asRequestBody(mediaType))
        .build()
}
```

## 4. 成功、失败与队列处理

首次写入：

```json
{"success": true, "status": "accepted"}
```

重复上传：

```json
{"success": true, "status": "duplicate"}
```

两种 HTTP 200 都表示服务端已经拥有完整逻辑事件，客户端可以删除本地 ZIP。响应不会包含解析报告、mapping 状态或采样数量。

| 结果 | 客户端处理 |
|---|---|
| `accepted` / `duplicate` | 删除队列项和 ZIP |
| 网络中断、超时 | 保留原 ZIP 和 eventId，指数退避重试 |
| 503 `APP_AUTH_UNAVAILABLE` / `STACK_PARSER_BUSY` / `EVENT_STORE_UNAVAILABLE` | 保留同一 ZIP，按 `Retry-After` 重试 |
| 401、403 `PACKAGE_NAME_MISMATCH`、400、413、415、422 | 永久失败；隔离或删除，记录稳定错误码并修复 Key、包名或产物 |

不要因为没有收到响应就推断服务端未写入。使用同一个 ZIP 重试后，服务端会通过 `appId + eventId` 返回 `duplicate`，不会重复放大统计。

## 5. 联调清单

- 现有 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip` 只证明旧 processor 对数值 PID v3 输入的解析、字节和报告检查；客户端设备 ZIP 已证明当前 `1.0.2` 对 UUID v4 manifest 的解析、HTTP 首次落库、重复上传和 Jank 详情回传。mapping、错误 Key、错误包名和重启语义仍需按生产配置单独验证。
- 确认事件详情的质量字段是 `expectedSampleCount`、`parsedSampleCount`、`missingSampleCount`，没有 attempted/successful/dropped。
- 样例 manifest 即使包含 `attemptedSampleCount=1`，服务端仍应从主线程得到 expected=460、parsed=454、missing=6。
- 使用旧媒体类型、v2 manifest 或 v1 `.rheatrace.zip`，确认返回不可重试错误。
- 分别验证空文件、超过上限、损坏 ZIP、解析繁忙和存储故障。
- 使用其他应用 appKey 或其他 `packageName` 验证 401/403，且服务端不留下原始事实、卡顿事实或详情。
- 检查客户端与服务端日志不包含应用 Key、原始设备 ID、ZIP、完整堆栈和服务端路径。
