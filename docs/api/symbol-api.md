# Android 符号表管理 API

符号表管理首版只提供网页 multipart 上传和当前版本列表，不接入 Gradle、CI 或构建后的自动上传。服务端按 `appId + buildId` 精确隔离一份当前 mapping；文件内容保存在服务端受控目录，接口只返回元数据和 SHA-256。

## 权限和资源边界

- 登录用户使用 Session 和 CSRF 访问接口；列表要求应用查看权限，上传和替换要求应用 `OWNER` 或 `ADMIN` 权限。Android `X-App-Key` 只用于事件上报，不能上传 mapping。
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

列表只返回当前版本，按 `updatedAt DESC, symbolId DESC` 排序；文件不能通过本接口下载。

## 首次上传

`POST /api/v1/apps/{appId}/symbols`，`Content-Type: multipart/form-data`

| part | 必填 | 说明 |
|---|---:|---|
| `buildId` | 是 | 与 Crash/Jank 事件中的构建标识完全一致 |
| `file` | 是 | R8/ProGuard `mapping.txt` |

首次成功返回 `201` 和 `SymbolFileMetadata`。同一 `appId + buildId` 上传相同 SHA-256 时返回 `200` 幂等成功，不新增 revision。已有不同摘要时返回 `409 SYMBOL_CONFLICT`，`errors[0]` 给出当前版本元数据；服务端不会自动覆盖。

## 管理员确认替换

`PUT /api/v1/apps/{appId}/symbols/{symbolId}`，使用 multipart part `file` 和 `expectedRevision`。服务端在数据库条件 `symbolId + revision` 下执行原子更新；成功后 revision 加一，并写入替换审计。并发请求只有一个能够成功。

旧版本文件在仍有详情或卡顿解析租约时保留，最后一个租约释放后回收。替换使用旧 revision 时返回 `409 SYMBOL_VERSION_CONFLICT`，响应携带当前元数据，网页必须刷新后再次明确确认。

## 生效语义

- Crash 事件只保存脱敏后的原始异常链。每次请求事件详情都按事件 `buildId` 读取当前 mapping，使用官方 R8 Retrace 在请求内还原；还原文本不写入数据库、缓存或事件对象。mapping 上传或替换后，旧 Crash 详情下一次打开即可使用新版本。
- 卡顿 ZIP 在上传解析时读取当前 mapping，并把已解析的证据写入卡顿事件。mapping 替换只影响后续上传的卡顿事件，已保存事件和指纹不回算。
- mapping 缺失、存储不可用、解析繁忙、输出超限或 Retrace 失败时，Crash 详情仍返回原始异常链，并给出 `symbolicationStatus` 和 `symbolicationReason`；不会因还原失败拒绝详情查询。

## 错误码

| HTTP | code | 是否可重试 | 说明 |
|---:|---|---:|---|
| 400/422 | `INVALID_BUILD_ID`、`INVALID_REVISION`、`INVALID_MAPPING`、`UNSUPPORTED_MAPPING_VERSION`、`MAPPING_TOO_LARGE` | 否 | 请求或 mapping 校验失败 |
| 403 | `FORBIDDEN` | 否 | 当前用户没有应用写权限 |
| 404 | `SYMBOL_NOT_FOUND` | 否 | 替换目标不存在或不属于当前应用 |
| 409 | `SYMBOL_CONFLICT` | 否 | 首次上传与当前摘要不同 |
| 409 | `SYMBOL_VERSION_CONFLICT` | 否 | `expectedRevision` 已过期 |
| 503 | `SYMBOL_STORE_UNAVAILABLE` | 是 | 数据库或受控文件卷不可用 |
| 503 | `SYMBOL_PARSER_BUSY` | 是 | Retrace 并发许可暂时耗尽 |

冲突和故障响应不会暴露服务器绝对路径、mapping 内容或 R8 异常堆栈。
