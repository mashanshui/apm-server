# Android 符号表管理 API

管理接口以应用版本隔离 mapping 与 SO，只返回元数据，不提供文件下载。事件上报的 buildId 仍必填；只有 mapping 登记可省略 buildId。客户端步骤见[符号接入](../client-integration/symbol-mapping.md)，内部恢复与预算见[后端实现](../../backend/docs/knowledge-base/symbol-mapping.md)。以下描述工作树实现，部署状态以[平台状态](../knowledge-base/00-当前实现与验证边界.md)为准。

## 权限与身份

列表、进度允许应用成员查看；上传、确认替换、删除要求网页 Session、CSRF 和 OWNER/ADMIN。App Key 和查询 Bearer 不能管理文件。SO 批次 CSRF 只接受 `X-XSRF-TOKEN` 请求头，禁止通过 multipart 参数补交。

`appVersion` 必填，1～128 字符；非空 mapping buildId 为 1～256 字符。均保留原文字面值，拒绝控制字符、首尾空白，不裁剪、截断或解释语义版本。mapping buildId 省略、null 或纯空白表示版本默认，非空表示构建专用。

| 类型 | 活动身份 | 选择或管理规则 |
|---|---|---|
| MAPPING 默认 | appId、appVersion、buildId=null | 事件自身构建专用登记不存在时使用 |
| MAPPING 专用 | appId、appVersion、非空 buildId | 同版本专用优先；不可读时返回存储失败，不回退默认 |
| NATIVE_SO | appId、appVersion、ABI、完整 ELF Build ID | 身份取自 ELF；不接受应用 buildId，不执行 Native 还原 |

旧无版本 mapping 保留 symbolId、文件和审计，列表 appVersion/mappingScope 为 null，不能匹配新版事件或查询进度；必须指定明确版本重新上传，不按文件名或遥测推导版本。旧登记仍可条件删除。

## 列表与元数据

`GET /api/v1/apps/{appId}/symbols`

| 参数 | 含义 |
|---|---|
| type | 可选 MAPPING / NATIVE_SO |
| appVersion | 可选精确版本 |
| mappingScope | 可选 default / build；只适用于 mapping |
| buildId | 可选精确构建；只适用于专用 mapping |
| abi、elfBuildId | 可选 SO 模块筛选；ELF ID 为完整小写十六进制 |
| limit、cursor | 默认 50、最大 200；cursor 使用响应原值 |

默认范围与 buildId 同时提供、SO 类型带应用构建/mappingScope、mapping 类型带 SO 字段均返回 400。过滤、排序和分页在 PostgreSQL 执行。按 `updatedAt DESC,symbolId DESC` 排序，游标绑定应用和完整筛选；筛选变化、游标失效或删除返回 `400 INVALID_CURSOR`，不能静默重回第一页。

```json
{
  "appId": "00000000-0000-4000-8000-000000000001",
  "items": [{
    "symbolId": "00000000-0000-4000-8000-000000000002",
    "appId": "00000000-0000-4000-8000-000000000001",
    "type": "MAPPING",
    "appVersion": "2.0",
    "mappingScope": "default",
    "buildId": null,
    "abi": null,
    "elfBuildId": null,
    "hasFunctionSymbols": null,
    "hasDebugInfo": null,
    "hasDebugLine": null,
    "revision": 1,
    "originalFilename": "mapping.txt",
    "sizeBytes": 1234,
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "uploadedBy": "00000000-0000-4000-8000-000000000003",
    "uploadedAt": "2026-11-11T00:00:00Z",
    "updatedAt": "2026-11-11T00:00:00Z"
  }],
  "nextCursor": null
}
```

SO 的 mappingScope/buildId 为 null；abi/elfBuildId 和三个检测布尔值非空。检测到函数符号、调试信息或行信息是文件事实，不保证每帧可还原。revision 是文件修订号，不是应用版本。

## mapping 上传与替换

`POST /api/v1/apps/{appId}/symbols`，multipart：`appVersion`、`file` 必填，`buildId` 选填。只接受通过官方 R8 校验的文本 mapping，业务上限 32 MiB。

```bash
# 版本默认：省略 buildId；实际会话与 CSRF 值由当前登录浏览器提供。
curl -b '<会话 Cookie>' -H 'X-XSRF-TOKEN: <CSRF 值>' \
  -F 'appVersion=2.0' -F 'file=@mapping.txt;type=text/plain' \
  'http://localhost/api/v1/apps/<appId>/symbols'
# 构建专用：在同一个请求中增加 -F 'buildId=release-A'。
```

首次创建返回 201；同一身份相同 SHA-256 返回 200，不增加 revision 或审计。不同内容返回 `409 SYMBOL_CONFLICT`，`errors[0]` 提供当前记录。

`PUT /api/v1/apps/{appId}/symbols/{symbolId}` 只替换有版本 mapping，multipart 提供 `file` 和正整数 `expectedRevision`。版本、类型和构建身份不可原位修改。修订不一致返回 `409 SYMBOL_VERSION_CONFLICT`；同内容仍校验修订，成功不增审计，不同内容原子增加修订。SO 必须走完整批次确认，旧无版本登记应重新上传。

mapping 元数据、审计和版本工作在同一事务提交；上传不等待历史事件还原。数据库提交确认未知时返回可重试 503，保留可能已引用文件；用相同版本、登记构建和字节执行普通上传对账。

## SO ZIP 批次

`POST /api/v1/apps/{appId}/symbols/native-batches`，multipart：

| part | 必填 | 说明 |
|---|---:|---|
| appVersion | 是 | 发布归属版本 |
| file | 是 | 包含带符号 SO 的普通单卷 ZIP |
| targetAbi、targetElfBuildId | 否 | 必须成对；归档必须包含该准确模块 |
| expectedEntries | 否 | 确认用 JSON 数组，必须包含全部实际候选身份 |

禁止 buildId part。支持 armeabi-v7a、arm64-v8a、x86、x86_64，完整 GNU Build ID 为 1～64 字节。至少具有已定义函数符号或非空调试段；仅 dynsym 不接受。文件名、目录不决定实际 ABI/身份。同身份同摘要合并，不同摘要拒绝整批。

首次请求仅允许新建及同内容幂等。任一不同内容返回 `409 SYMBOL_BATCH_CONFLICT`，标准 `errors` 数组提供全部候选快照，每项包含 abi、elfBuildId、incomingSha256、current；current=null 表示当时不存在。整批不产生部分修改。

确认时重传原 ZIP，每项预期现有 symbolId/revision 或明确为空：

```json
[
  {"abi":"arm64-v8a","elfBuildId":"aabb","symbolId":"00000000-0000-4000-8000-000000000002","revision":1},
  {"abi":"x86","elfBuildId":"ccdd","symbolId":null,"revision":null}
]
```

实际候选与确认集合必须完全相同；重复、漏项、多项、非法修订返回 400。已存在行和预期不存在项均在同一版本事务锁内核对；身份或修订变化使整批 409。确认过期时，即使当前内容相同仍不能充当覆盖授权。提交确认未知应普通重传原 ZIP 对账；同内容返回幂等成功，不带过期确认。

成功响应为 `{items,createdCount,replacedCount,unchangedCount}`，含新建项返回 201，其余返回 200。SO 不登记 JVM 工作，不回填或改变 Native 原始事件。文件全部通过校验后才发布到 UUID 受控键，原 ZIP 不保留。

## 接收与资源边界

SO 单实例并发 1，不排队；接收许可在 multipart 解析前取得，覆盖暂存、解析、事务和清理。繁忙或磁盘不足返回可重试 503。获得许可后总期限 60 秒，每次流读取和校验循环检查；单次底层阻塞由 Servlet/代理读取超时保护，不承诺任意时刻强制终止文件系统 I/O。

| 预算 | 上限 |
|---|---:|
| 压缩 ZIP / multipart 请求 | 256 / 260 MiB |
| SO 单文件 / 实际解压总量 | 128 / 512 MiB |
| 归档条目 / SO 模块身份 | 256 / 128 |
| 中央目录 / 路径 UTF-8 长度 | 1 MiB / 1024 字节 |
| 路径深度 | 8 |
| multipart 文本字段 / part 头 | 64 KiB / 8 KiB |

拒绝 ZIP64、多卷、加密、穿越、绝对路径、反斜杠、链接、特殊文件、重复路径、嵌套归档、CRC 或长度不符。目录、普通 `__MACOSX/` 辅助文件和 `.DS_Store` 可忽略，但仍计入实际解压预算。任一候选不合法或没有 SO，整批失败。应用接收至少预留 768 MiB 暂存空间，另需为已保存文件、数据库和代理运行预留容量。

## 删除与版本进度

`DELETE /api/v1/apps/{appId}/symbols/{symbolId}?expectedRevision=1`：mapping、SO、旧登记均可条件软删除，成功 204。过期修订 409；未知、已删除或不属于应用的记录 404。审计和历史成功来源保留；重新上传建立新 symbolId。只有有版本 mapping 唤醒 JVM，SO 和旧无版本登记不唤醒。旧 mapping 文件在最后一个在途租约退出后回收。

`GET /api/v1/apps/{appId}/symbols/{symbolId}/crash-symbolication`：只适用于有版本活动 mapping；SO 或旧无版本登记返回 `422 SYMBOL_PROGRESS_NOT_APPLICABLE`。查询 PostgreSQL 的同应用版本工作，响应 `{state,succeededCount,failedCount,updatedAt,errorCategory}`；默认和专用读取同一个版本进度，计数涵盖所有构建，属于本轮而非文件独有或应用历史总量。

状态为 queued/running/waiting_mapping/retry_wait/idle。空闲或等待时后续唤醒可开启新一轮；idle 不证明全部历史事件成功，也不排除迟到事件。网页最多每五秒轮询一次，最多五分钟；SO 不轮询。

## 生效与错误

JVM 先持久化原始材料，后台按版本筛选并逐构建选择实际 mapping。专用不存在才用同版本默认，禁止猜测其他构建、其他版本、SO 或旧无版本登记。失败按实际 symbolId/revision 与退避判断重试；成功 publication 固定原字节与来源，替换、删除和重启不重新计算成功。Jank 使用 processor 1.0.4，在同一 ZIP 预检版本并核对回调、最终报告，上传时固定文件；已存事件不回算。Native 本期保持 raw_native/pending，不执行 R8。详见[Crash API](crash-api.md)与[Jank 产物 API](stack-artifact-api.md)。

常见错误：身份/筛选/确认非法为 400；无效 mapping、SO、归档及目标不符为 422；字节超限为 413；不同内容及修订冲突为 409；存储/磁盘/并发暂不可用为可重试 503。SO 细分类别包括 INVALID_SO、UNSUPPORTED_SO_FORMAT、SO_BUILD_ID_MISSING、SO_SYMBOLS_MISSING、SO_TARGET_MISMATCH、SO_ARCHIVE_IDENTITY_CONFLICT、INVALID_SYMBOL_CONFIRMATION。SO 正文接收后的解压、ELF 检查、摘要读取或清理发生超时、中断、磁盘 I/O 故障时，同样返回 `503 SYMBOL_STORE_UNAVAILABLE`、`retryable=true` 和 `Retry-After: 30`；普通格式错误仍为 422，字节超限仍为 413。提交已完成但清理失败时保留已引用文件，可用相同版本及原 ZIP 普通重传对账，不带旧确认快照。错误不返回路径、文件内容或底层堆栈。上传校验不能证明 mapping 与 APK 一致，上传前应核对真实构建产物。
