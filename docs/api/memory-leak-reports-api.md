# 内存泄漏报告 API

本文描述当前首版的内存异常报告接收和查询接口。客户端使用 `X-App-Key` 上报；网页查询使用登录 Session 和应用成员权限。`hprof` 只受限保存，服务端不解析 HPROF，也不提供内存详情页或附件下载入口。

## 上报报告

```http
POST /ingest/v1/memory-reports
Content-Type: multipart/form-data
X-App-Key: <应用上报 Key>
```

请求必须包含两个文件 part，并可选包含第三个文件 part：

| part | Content-Type | 必填 | 内容 |
|---|---|---:|---|
| `metadata` | `application/json` | 是 | 统一事件 JSON 参数，不包含 `report` 对象 |
| `report` | `application/json` | 是 | SDK 生成的完整 `hprof.json` 内容 |
| `hprof` | `application/octet-stream` | 否 | 原始 HPROF 文件，只保存、不解析 |

`metadata` 示例：

```json
{
  "schemaVersion": 1,
  "eventId": "00000000-0000-4000-8000-000000000001",
  "occurredAt": 1780000000000,
  "packageName": "com.example.memoryleak",
  "appVersion": "1.0.0",
  "versionCode": 1,
  "anonymousDeviceId": "device-hash",
  "processId": "11111111-1111-4111-8111-111111111111",
  "processName": "com.example.memoryleak"
}
```

`report` 文件的 JSON 根对象示例：

```json
{
  "runningInfo": { "buildModel": "Test phone", "sdkInt": "33" },
  "gcPaths": [],
  "classInfos": [],
  "leakObjects": []
}
```

`metadata` 必须包含 `schemaVersion=1`、UUID `eventId`、Unix 毫秒 `occurredAt`、`packageName`、`appVersion`、非负整数 `versionCode`、`anonymousDeviceId`、UUID v4 `processId` 和 `processName`；可选 `sessionId`、`buildId`、`environment`、`channel`。metadata 不允许出现 `report` 或未知外层字段。缺失、空值、数值 PID 和非 v4 的 `processId` 返回永久的 `INVALID_MEMORY_LEAK_REPORT`；服务端不会用可选 `sessionId` 补值。

`report` 必须包含 `runningInfo` 对象和 `gcPaths`、`classInfos`、`leakObjects` 数组。GC 路径要求 `signature`、`gcRoot`、`leakReason`、正整数 `instanceCount` 和非空 `path`；节点要求 `reference`、`referenceType`。`classInfos.instanceCount`、`leakObjects.size` 可用十进制数字字符串或整数，`objectId` 按字符串保存。未知的报告内部字段会在大小限制内保留；文件名不参与解析或存储路径。

使用 curl 时，两个 JSON 文件和可选 HPROF 可以这样组合：

```bash
curl -X POST "https://example.invalid/ingest/v1/memory-reports" \
  -H "X-App-Key: <应用上报 Key>" \
  -F "metadata=@metadata.json;type=application/json" \
  -F "report=@hprof.json;type=application/json" \
  -F "hprof=@sample.hprof;type=application/octet-stream"
```

服务端限制默认值为 metadata 2 MiB、report 文件 2 MiB、HPROF 256 MiB、总请求 260 MiB、JSON 深度 32、数组 1000 项、单条引用链 256 节点、字符串 16 KiB。请求不支持压缩；无 `Content-Length` 时仍按流读取限制。纯 `application/json` 请求不再支持。

上述大小是报告业务校验上限。当前 Spring multipart 与 Compose 默认单文件 32 MiB、总请求 34 MiB，会先限制大 HPROF；部署方需同步调整 `APM_MULTIPART_MAX_FILE_SIZE`、`APM_MULTIPART_MAX_REQUEST_SIZE` 并为 multipart 边界预留空间，网关当前上限为 260 MiB。配置细节见[后端运行说明](../../backend/docs/knowledge-base/05-构建配置与本地运行.md#二进制堆栈解析配置)。报告 parser 保留 metadata 的 `anonymousDeviceId` 和受限 report 原始 JSON，不执行 Crash/卡顿/内存采样 sanitizer 的部署级盐哈希或文本模式脱敏；客户端应提供匿名随机标识及不含个人信息的诊断内容。

成功响应（HTTP 200）：

```json
{
  "eventId": "00000000-0000-4000-8000-000000000001",
  "status": "accepted",
  "issueCount": 0,
  "attachmentStatus": "absent"
}
```

`status` 为 `accepted` 或相同内容重试时的 `duplicate`；`issueCount` 是该报告去重后的 `signature` 数量；`attachmentStatus` 只表示可选 HPROF 是否已经保存，为 `absent` 或 `stored`。服务端先保存声明的 HPROF，再保存报告事实，成功响应不会表示 HPROF 已经解析。

### 错误与重试

| HTTP/错误码 | 语义 | 客户端处理 |
|---|---|---|
| 400 `INVALID_MEMORY_LEAK_REPORT` | metadata、report 结构、数字、缺少 part 或限制校验失败 | 修正内容后再生成新报告 |
| 401 `INVALID_APP_KEY` | Key 无效 | 停止重试并重新配置应用 |
| 403 `PACKAGE_NAME_MISMATCH` | 包名与 Key 绑定不一致 | 停止重试 |
| 409 `EVENT_ID_CONFLICT` | 同一 `eventId` 的 metadata、report 或 HPROF 摘要变化 | 保留原事件，人工排查，不覆盖服务端事实 |
| 413 `PAYLOAD_TOO_LARGE` | metadata、report、HPROF 或总请求超过限制 | 缩小内容后使用新事件 ID |
| 415 `UNSUPPORTED_MEDIA_TYPE` | 请求不是 multipart、JSON part 媒体类型不正确或压缩请求不支持 | 按 multipart 约定重发 |
| 503 `EVENT_STORE_UNAVAILABLE` / `ATTACHMENT_STORE_UNAVAILABLE` | 存储暂时不可用 | 保留原 metadata、report、HPROF 和原 `eventId`，按 `Retry-After` 重试 |

报告默认保留 90 天，HPROF 附件默认保留 7 天；24 小时以上的临时或孤立文件由后台清理。只有带 HPROF 的请求依赖附件目录可写；仅上传 metadata 和 report 文件时不依赖该目录。

## 问题列表

```http
GET /api/v1/apps/{appId}/memory-leaks/issues
```

公共筛选参数：`from`、`to`（ISO-8601，UTC 左闭右开，默认最近 24 小时，最长 31 天）、`appVersion`、`deviceModel`、`processName`、`scene`、`manufacturer`、`sdkInt`、`dumpReason`、`anonymousDeviceId`、`signature` 和 `keyword`。除 `keyword` 对类名、引用、GC Root、原因和 signature 做字面子串匹配外，其余为精确匹配。

问题端点还支持 `page`（默认 1）、`pageSize`（默认 20，最大 100）、`sort=occurrences|affectedDevices|lastOccurredAt` 和 `order=asc|desc`。默认按发生次数降序，并以 signature 稳定排序。

响应包含 `total`、`totalOccurrences`、`totalAffectedDevices`、`dataSource` 和 `items`。每项包含 signature、最新报告的 `leakClass`/`leakReason`/`gcRoot`/完整 `path`、最近发生时间、发生次数及占比、影响设备数及占比和去重版本集合。发生次数按 `(eventId, signature)` 计一次，`instanceCount` 不加权；空 `gcPaths` 报告会保存但不进入问题统计。

## 趋势

```http
GET /api/v1/apps/{appId}/memory-leaks/trend?interval=5m|hour|day
```

趋势只接受公共筛选和 `interval`，最多返回 2000 个 UTC 桶；空桶返回零。每个点包含 `bucketStart`、`occurrenceCount` 和 `affectedDeviceCount`。不同桶的设备数不能相加作为总设备数。

## 明确未提供

当前不解析 HPROF，不计算 retained size、泄漏字节、复现率或用户数，不提供内存详情路由、附件下载和 HPROF 解析入口。行内引用链展开只属于问题列表展示。

客户端构造、队列和重试步骤见[内存泄漏报告客户端接入](../client-integration/memory-leak-reports.md)。
