# Android 符号表管理 API

符号表通过网页提供活动列表、multipart 上传、revision 条件替换/删除及构建后台进度，不接入 Gradle、CI 或构建自动上传。服务端按 `appId + buildId` 精确隔离一份活动 mapping；文件内容保存在受控目录，接口只返回元数据和摘要。

## 权限和资源边界

- 登录用户使用 Session；写操作要求 CSRF。列表和进度允许应用成员查看，上传、替换和删除只允许 `OWNER`/`ADMIN`。Android `X-App-Key` 不能管理 mapping。
- `buildId` 只能包含 `[A-Za-z0-9._-]`，长度 1～128，`.` 和 `..` 无效。服务端不会根据文件名或路径选择 mapping。
- 默认单文件上限为 32 MiB，默认单次 Retrace 输出上限为 1 MiB，并发解析默认 2，可通过 `APM_SYMBOL_*` 配置调整；网关和 Spring multipart 上限应不小于该值。
- 上传阶段使用官方 R8 Retrace API 读取并校验完整 mapping，校验失败不会发布文件或写入元数据。服务端不自行实现 ProGuard/R8 mapping 解析器。

## 列表

`GET /api/v1/apps/{appId}/symbols?buildId={buildId}&cursor={symbolId}&limit={limit}`

响应：

```json
{
  "appId": "8f0d…",
  "items": [{
    "symbolId": "42f7…",
    "appId": "8f0d…",
    "buildId": "1.2.3-release",
    "revision": 1,
    "originalFilename": "mapping.txt",
    "sizeBytes": 183420,
    "sha256": "…64 位小写十六进制…",
    "uploadedBy": "4ac1…",
    "uploadedAt": "2026-09-19T08:00:00Z",
    "updatedAt": "2026-09-19T08:00:00Z"
  }],
  "nextCursor": null
}
```

列表只返回活动记录，在 PostgreSQL 精确筛选和分页，默认 50、最多 200，按 `updatedAt DESC, symbolId DESC` 排序；文件不能下载。非法游标返回 INVALID_CURSOR；已删除或不属于应用的游标资源返回 404，不能静默回到第一页。

## 首次上传

`POST /api/v1/apps/{appId}/symbols`，`Content-Type: multipart/form-data`

| part | 必填 | 说明 |
|---|---:|---|
| `buildId` | 是 | 与 Crash/Jank 事件中的构建标识完全一致 |
| `file` | 是 | R8/ProGuard `mapping.txt` |

首次成功返回 `201` 和 `SymbolFileMetadata`。同一 `appId + buildId` 上传相同 SHA-256 时返回 `200` 幂等成功，不新增 revision。已有不同摘要时返回 `409 SYMBOL_CONFLICT`，`errors[0]` 给出当前版本元数据；服务端不会自动覆盖。

201/200 只表示文件可用且构建工作已可靠登记，不表示历史 Crash 已还原。元数据、审计与任务唤醒在同一 PostgreSQL 事务提交；登记失败不能报告成功，请求内不查询历史事件或执行其还原。若提交确认未知则返回失败并保留可能已被引用的文件，调用方用相同构建和字节重试确认；不因异常删除已发布文件。

## 管理员确认替换

`PUT /api/v1/apps/{appId}/symbols/{symbolId}`，使用 multipart part `file` 和 `expectedRevision`。服务端在数据库条件 `symbolId + revision` 下执行原子更新；成功后 revision 加一，并写入替换审计。并发请求只有一个能够成功。

旧文件在仍有后台批次或卡顿解析租约时保留，最后一个租约释放后回收。替换使用旧 revision 时返回 `409 SYMBOL_VERSION_CONFLICT`，响应携带当前元数据，网页必须刷新后再次明确确认。

## 条件删除

```http
DELETE /api/v1/apps/{appId}/symbols/{symbolId}?expectedRevision=1
X-XSRF-TOKEN: <CSRF 值>
```

成功返回 204。版本过期返回 `409 SYMBOL_VERSION_CONFLICT`；未知、已删除或非所属资源返回 404。删除在数据库软删除主记录并写 DELETE 审计，保留身份、最后 revision、摘要和操作者，提交后按读取租约退休文件。活动列表和新文件领取排除已删除记录；已经取得旧文件的一批允许完成并记录旧来源。同构建再次上传产生新的 symbolId，revision 从 1 开始；旧请求不能误删新身份。

## 构建后台进度

`GET /api/v1/apps/{appId}/symbols/{symbolId}/crash-symbolication`，只允许活动 symbolId，删除后返回 404。响应直接读取该应用/构建的 PostgreSQL 任务，不扫描 Crash 或堆栈：

```json
{
  "state": "queued",
  "succeededCount": 0,
  "failedCount": 0,
  "updatedAt": "2026-11-05T00:00:00Z",
  "errorCategory": null
}
```

state 为 `queued/running/waiting_mapping/retry_wait/idle`。计数属于本轮工作：succeededCount 是已确认首次成功发布的事件数，与发布确认在同一事务登记；failedCount 是已登记处理失败的尝试数，可以包含状态写回未确认或同一事件的重试。计数不是应用全量已还原/失败事件总数。空闲或等待构建被重新唤醒会开始新一轮；idle 只表示本轮当前没有可处理输入，不承诺未来没有迟到事件。尚无任务时返回 idle、零计数和空更新时间。

## 生效语义

- Crash 先保存 pending 原始链，由后台独立处理，详情只读当前材料。首次成功后保存完整还原文本、正式归组和实际来源，结果固定；上传、替换、删除和重传仅影响尚未成功及新到事件。完整契约见[Crash API](crash-api.md)，客户端视角见[生效说明](../client-integration/symbol-mapping.md)。
- 卡顿 ZIP 在上传解析时读取当前 mapping，并把已解析的证据写入卡顿事件。mapping 替换只影响后续上传的卡顿事件，已保存事件和指纹不回算。
- mapping 缺失、失败或退避中的事件仍可按临时归组查看原始材料；成功结果不要求符号文件继续存在。格式校验不能证明 mapping 与 APK 匹配，错误 mapping 的首次成功可能永久归错组，上传前必须核对同一次构建。

## 错误码

| HTTP | code | 是否可重试 | 说明 |
|---:|---|---:|---|
| 400/422 | `INVALID_BUILD_ID`、`INVALID_REVISION`、`INVALID_MAPPING`、`UNSUPPORTED_MAPPING_VERSION` | 否 | 请求或 mapping 校验失败 |
| 413 | `MAPPING_TOO_LARGE` | 否 | 单文件超出上传上限 |
| 403 | `FORBIDDEN` | 否 | 当前用户没有应用写权限 |
| 404 | `SYMBOL_NOT_FOUND` | 否 | 当前目标不存在、已删除或不属于应用 |
| 409 | `SYMBOL_CONFLICT` | 否 | 首次上传与当前摘要不同 |
| 409 | `SYMBOL_VERSION_CONFLICT` | 否 | `expectedRevision` 已过期 |
| 503 | `SYMBOL_STORE_UNAVAILABLE` | 是 | 数据库或受控文件卷不可用 |
| 503 | `SYMBOL_PARSER_BUSY` | 是 | Retrace 并发许可暂时耗尽 |

冲突和故障响应不会暴露服务器绝对路径、mapping 内容或 R8 异常堆栈。
