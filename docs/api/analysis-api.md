# 单事件分析 API

2026-10-04 当前契约已发布到云端，任务/凭据/报告完成真实受控冒烟；HTTP 字段与权限未作额外变更，见[云端发布验收](../analysis-validation/cloud-current-code-validation.md)。

当前实现单事件证据、Worker 身份、领取/租约、停止/重试、结构化结果及内容到期。Python 正式任务和网页入口已实现；真实链路和受控质量评估见跨端验收。默认 `apm.agent.analysis.enabled=false`，开启后管理员可写入。网页使用既有 Session、CSRF 和应用成员授权，不接受 App Key 或查询 Token 替代登录。

## 宿主直接读取协议（2026-10-04）

Python 0.4.0 与报告版本 4 配套使用。宿主直接搜索/读取当前项目，明确授权时使用自身编辑和测试工具；Python 不遍历仓库、不复制源码，不提供 read/search/apply/revert。保留配置、应用身份、冻结证据、租约、报告上限及停止审计。旧活动/待回传本地记录不能静默升级；先用旧版结束并按平台核验。历史报告原样读取。此次无需新数据库迁移。

## 当前代码唯一模式

2026-10-03 后端源码改为当前工作区分析/修复契约，V14 追加迁移；Python、Skill 和网页已切换新协议，跨端验收进度见 [当前记录](../analysis-validation/current-code-validation.md)。原始 Crash 上报与 mapping 仍使用 buildId，客户端接入见 [JVM Crash](../client-integration/crash-client-integration.md)。

分析专用 `analysis-builds` 的登记、当前列表、历史接口均已删除。新任务不接收 buildId、commitSha、repositoryId、origin、登记版本、混淆声明或核验依据。使用宿主已配置模型，不配置独立模型密钥。

## 单事件任务及证据

`POST /api/v1/apps/{appId}/crashes/events/{eventId}/analyses`，OWNER/ADMIN/DEVELOPER 可创建，必须携带 CSRF。请求仅提供稳定幂等键：

```json
{"idempotencyKey":"11111111-1111-4111-8111-111111111111"}
```

成功返回 200：任务元数据字段 `taskId/appId/eventId/fingerprint/createdBy/createdAt/state/blockReason/evidenceId/evidenceSha256/evidenceSchemaVersion/contentExpired`；不包含完整证据正文。只读取当前应用选中事件，必须为 JVM fatal Crash。同键同事件返回原任务，同键不同事件为 `409 ANALYSIS_IDEMPOTENCY_CONFLICT`。无构建登记、无 mapping 也可直接准备 READY 原始证据；准备在请求内同步进行，不调用模型。应用最多三个 BLOCKED/READY/RUNNING/CANCELLING 任务，超过为 `429 ANALYSIS_TASK_LIMIT`。

新任务证据版本为 2，不保存重复源码配置。当前工作区无法证明历史 APK 源码一致；报告必须记录差异与未知项。历史版本 1～3 报告保留原 JSON，只读展示，不能重检或重试旧冻结证据来改换执行方式。

`GET /api/v1/apps/{appId}/analysis-tasks/{taskId}`：应用成员读取单任务。`GET /api/v1/apps/{appId}/crashes/events/{eventId}/analyses?page=0&size=20`：事件下任务数组，按 createdAt/taskId 倒序；size 1～100、page 0～1000。全部接口禁止缓存。

`POST /api/v1/apps/{appId}/analysis-tasks/{taskId}/recheck`：有创建权限的成员显式重新准备尚未冻结证据的 BLOCKED 任务；无请求体，要求 CSRF。成功返回当前任务，非法状态 `409 ANALYSIS_STATE_CONFLICT`。原任务创建重试不会自动重检。

| blockReason | 说明 |
|---|---|
| EVIDENCE_PREPARING | 尚在同步准备，不能执行 |
| SYMBOL_VERSION_CHANGED | 发布时 mapping 版本或摘要已变化 |
| EVENT_IDENTITY_CHANGED | 单事件事实与已绑定输入不一致 |
| EVIDENCE_TOO_LARGE | 完整内容超过上限，未截断或发布部分证据 |
| PREPARATION_FAILED | 准备异常，不回显敏感异常内容 |

READY 证据保存 schemaVersion=2、独立 evidenceId、必要事件字段、mapping 版本及摘要、完整原始异常链和可用还原片段。排除 sessionId/processId/设备唯一 ID/人工核验备注；异常和帧文本处理明确标记的凭据与常见邮箱，不能识别任意自然语言秘密。正文原始 UTF-8 与 JSONB 投影都限制 1 MiB，可通过 `apm.agent.analysis.max-evidence-bytes` 调小。摘要依据原始发送字节计算。冻结后 mapping 更正不改写原证据，日常详情仍每次实时还原。

其他稳定错误：`400 UNSUPPORTED_ANALYSIS_EVENT`、`400 INVALID_ANALYSIS_PAGE`、`404 ANALYSIS_TASK_NOT_FOUND`。跨应用或未知对象统一返回不存在。

## 独立 Worker 凭据

`POST /api/v1/apps/{appId}/analysis-workers`，OWNER/ADMIN 且 CSRF 有效；请求 `{"name":"本地分析"}`。返回 `metadata` 和仅这一次展示的 `credential`，Cache-Control 为 no-store。名称 1～100 字符；仅绑定所属应用，有效期 30 天；应用最多十个活动凭据。数据库仅保存 256 bit 随机值的 SHA-256，不保存完整凭据。

`GET /api/v1/apps/{appId}/analysis-workers?page=0&size=20`，管理员读取元数据数组：credentialId/appId/name/displayPrefix/createdAt/expiresAt/revokedAt。无秘密与摘要，page 0～1000、size 1～100。`DELETE /api/v1/apps/{appId}/analysis-workers/{credentialId}` 撤销，返回 204；重复撤销保持幂等。关闭功能仍允许列表与撤销。

`GET /api/worker/v1/tasks/{taskId}` 仅供 Worker 领取前读取所属应用任务元数据，返回 `{task}`，只包含该应用任务摘要。不返回完整证据。需通过 Authorization Bearer 传入独立 `apm_aw_` 凭据，不使用 Cookie、App Key、查询 `apm_qt_` Token 或 URL 参数。每次调用按数据库时钟检查到期及撤销。非所属应用任务为 404；不存在、错误、到期、撤销凭据统一 `401 WORKER_CREDENTIAL_INVALID`。该入口没有领取或启动副作用，完整证据读取必须绑定下述 Run 接口。

凭据管理错误：`400 INVALID_WORKER_NAME/INVALID_WORKER_PAGE`、`429 WORKER_CREDENTIAL_LIMIT`、`404 WORKER_CREDENTIAL_NOT_FOUND`。调用方没有仓库、执行类型或应用扩权参数。

## Run 领取和租约

以下接口均要求独立 Worker Bearer 凭据，所有响应 no-store。JSON 请求正文在解析前限制 2 MiB（包含 chunked），超限 `413 ANALYSIS_REQUEST_TOO_LARGE`；证据和结果正文各自最大 1 MiB。

`POST /api/worker/v1/tasks/{taskId}/claim`：请求 `{"requestId":"22222222-2222-4222-8222-222222222222"}`。只领取指定 READY 任务。返回 `{run,leaseToken}`，Run 字段为 `runId/taskId/attempt/state/taskState/leaseGeneration/leaseExpiresAt/deadlineAt/serverNow/stopConfirmed/errorCode/localToolsStopped/hostStopState/snapshotId`。默认租约 90 秒，心跳 20 秒，固定总运行期限 10 分钟。本地身份使用 runId，证据和恢复记录不可跨分配混用。新分配 snapshotId=null，仅为历史查询保留该字段。旧冻结证据版本领取返回 `409 ANALYSIS_CONFIG_MISMATCH`。

同一凭据/requestId 重试返回原 Run 和活动租约；同 ID 换任务或复用旧快照分配为 `409 ANALYSIS_CLAIM_CONFLICT`。已结束的分配返回原终态且 leaseToken=null，不自动重新启动。同一任务、同一 Worker 凭据最多一个活动 Run；任何未确认停止的历史环境也阻止该身份领取其他任务。租约可逆值仅在活动 Run 临时保存以支持领取响应丢失重试，结束后清除，保留摘要；仅原分配身份能取得它，网页历史不包含它。

`GET /api/worker/v1/tasks/runs/{runId}`：原分配身份对账，返回 Run，无租约秘密。`POST /api/worker/v1/tasks/runs/{runId}/heartbeat`：请求 `{"generation":1,"token":"运行时租约占位值"}`，返回 Run；有效代次及随机 Token 必须匹配，续租不能超过 deadlineAt。取消或功能关闭时 taskState=CANCELLING，Python 应关闭本任务材料，宿主停止保持 UNKNOWN，不能把工具关闭视为模型中止。过期为 FAILED/LEASE_EXPIRED 或 TIME_LIMIT，stopConfirmed=false；心跳返回 `409 ANALYSIS_LEASE_INVALID`。后端定时维护也会识别到期或凭据失效，不把它们当成进程已停止。

`GET /api/worker/v1/tasks/runs/{runId}/evidence`：传 `X-Analysis-Generation` 和 `X-Analysis-Lease` 请求头，正文为冻结 JSON 原始 UTF-8；客户端按任务 evidenceSha256 核验。仅当前有效 Run、未取消且功能开启可读取。

## 结果、失败和停止

`POST /api/worker/v1/tasks/runs/{runId}/complete`：请求字段为 `lease`（generation/token）、`resultJson`（完整结构化 JSON 字符串）、`resultSha256`（该字符串 UTF-8 SHA-256）、`stopConfirmed:false`、`localToolsStopped:true`。Python 已关闭本任务证据和回传工具后才能报告工具停止；Worker 自报整体停止 true 被拒绝。成功返回 SUCCEEDED 的 Run，hostStopState=UNKNOWN、stopConfirmed=false，仍保留停止门禁。第一次成功须为当前有效租约且未取消/关闭。已完成 Run 相同摘要重传返回原确认，不要求租约未到期，但仍检查凭据和原分配身份；不同摘要 `409 ANALYSIS_RESULT_CONFLICT`。后端不调用模型，不把收到结果理解为已修复或已经执行验证建议。

结构化 JSON 固定字段如下，未知字段拒绝：

| 字段 | 约束 |
|---|---|
| schemaVersion/evidenceId/runId | 新提交版本 4、当前任务证据 ID、当前分配 Run UUID（版本 1～3 只读） |
| conclusion/summary | ROOT_CAUSE_CANDIDATE 或 INSUFFICIENT_EVIDENCE；摘要 1～4000 字符 |
| candidates | 最多 5 个，title/reason/evidenceRefs，片段 ID 必须属于当前证据 |
| sourceRefs | 最多 20 个，path/startLine/endLine/snippet/snippetSha256/metadataSource/currentCheck；安全相对路径、1 起始、最多 200 行、片段 UTF-8 最多 8192 字节；metadataSource=HOST_REPORTED，currentCheck=CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE |
| unknowns/risks/fixSuggestions/validationSuggestions | 各最多 20 条、每条 1～1000 字符；证据不足必须给出未知项，根因候选必须有候选 |
| execution | mode=HOST_AGENT、toolVersion=0.4.0、metadataSource=HOST_REPORTED 或 UNKNOWN；host/hostVersion 最多 100 字符且可为 null；providerId/modelId 为 null。当前工具只能自报宿主名称，无法验证模型或版本；UNKNOWN 时上述来源字段均为 null |
| usage | 当前宿主无可信计量通道，inputTokens/outputTokens/cacheReadTokens/cacheWriteTokens/cost 均为 null；历史独立执行器用量原样保留 |
| repair | status=NOT_REQUESTED/NOT_APPLICABLE/APPLIED/PARTIAL/CONFLICT/FAILED；metadataSource=HOST_REPORTED；files 最多 20 条，含安全相对 path 与最多 1000 字符的 summary；reason 最多 2000 字符。由宿主自报，Python 不生成补丁日志或前后摘要 |
| verification | status=PASSED/FAILED/NOT_RUN，metadataSource=HOST_REPORTED，commands 最多 10 条（command/exitCode/summary），reason 最多 2000 字符；不提供 workspaceUnchanged |

NOT_REQUESTED/NOT_APPLICABLE 的修改列表为空；APPLIED/PARTIAL 必须有实际写入文件。NOT_RUN 命令列表为空且需原因。PASSED 要求命令非空、退出码全部为 0；FAILED 至少一条非零退出码。这是宿主报告，Python 不鉴证命令已执行或验证后工作区一致性。后端只能检查字段、归属、摘要及语义，不能独立鉴证磁盘修改或宿主测试。

源码片段由宿主提供，按 LF 拼接对应行、不带末尾换行；明确凭据值脱敏且保留行号，snippetSha256 对展示的脱敏片段计算。Python 仅在提交时读取引用位置并给出 CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE；这不证明宿主此前读取过文件、修改前版本或历史 APK 对应关系。缺失、链接、解码失败、扫描超限等只使引用核对 UNAVAILABLE，不阻止获取崩溃证据。后端核对范围、行数、片段摘要与证据/Run 归属，不能独立证明磁盘事实。用量缺失保持未知，不填写零或伪造费用。

`POST /api/worker/v1/tasks/runs/{runId}/failure`：请求 `lease/errorCode/stopConfirmed/localToolsStopped`，整体停止必须 false；工具停止 true 则采用与 stopped 相同的材料关闭语义。稳定原因仅支持 CANCELLED、LEASE_EXPIRED、EXECUTOR_FAILED、CONFIG_MISMATCH、SOURCE_MISSING、REQUEST_LIMIT、INPUT_LIMIT、TIME_LIMIT、FORMAT_INVALID、REFERENCE_INVALID、NETWORK_ERROR。false 表示失败且停止未知，仍阻止重试；不得附原始异常或凭据日志。

`POST /api/worker/v1/tasks/runs/{runId}/stopped`：请求 `lease/errorCode`。本任务工具关闭后确认 localToolsStopped=true，但 hostStopState=UNKNOWN、stopConfirmed=false；支持租约到期后的原分配回执。重复回执保留原事实，不改变更新尝试。活动取消任务转 CANCELLED，其他活动失败转 FAILED；已结束 Run 仅补停止事实。撤销凭据仍不可调用，需管理员核验。

## 网页生命周期与内容到期

`POST /api/v1/apps/{appId}/analysis-tasks/{taskId}/cancel`：当前具有分析创建权限的创建者或 OWNER/ADMIN，要求 CSRF；VIEWER 仅读取，即使其曾经创建任务也不能取消。BLOCKED/READY 直接 CANCELLED；RUNNING 先 CANCELLING，拒绝成功回传且等待停止确认。终态重复取消返回原任务。

`POST /api/v1/apps/{appId}/analysis-tasks/{taskId}/retry`：OWNER/ADMIN/DEVELOPER；仅 FAILED/CANCELLED、有完整未过期证据且所有旧 Run 停止已确认时转 READY。后续领取追加 attempt，保留原输入和旧结果。无证据的阻断任务走 recheck，证据变化或成功后重新分析应新建任务。

`POST /api/v1/apps/{appId}/analysis-runs/{runId}/confirm-stopped`：OWNER/ADMIN 和 CSRF，请求 `{"basis":"人工核对指定 Run 的本地材料关闭及宿主本次分析已结束"}`；依据 1～2000 字符并保存核验者。有效活动任务必须先取消；未知、撤销或到期 Worker 的环境须先由管理员实际检查，模型回答不能作为停止证明。管理员核验后 localToolsStopped=true、hostStopState=CONFIRMED、stopConfirmed=true；历史 NOT_APPLICABLE 保留。成功报告也可补该审计。重复核验保留原审计。

`GET /api/v1/apps/{appId}/analysis-tasks/{taskId}/runs?page=0&size=20`：应用成员，返回 `{run,result,contentExpired}` 数组，attempt 倒序，page 0～1000、size 1～100。任务响应也含 contentExpired。终态正文 30 天到期后清除证据/结果内容，保留 ID、摘要、Run 生命周期及停止审计；报告正文中的宿主来源和业务结论随正文清理，不承诺单独永久保存。停止未确认环境的内容不清理。过期 result=null 与 contentExpired=true 不表示分析成功的空结果。

功能关闭拒绝新任务、重检、重试、凭据创建及领取；已有 READY/BLOCKED 取消，RUNNING 发出停止意图。列表、历史、撤销、原分配停止和管理员核验仍可用。保留新增表及审计，不影响原 Crash、上报及只读 MCP。

| 错误码 | HTTP | 说明 |
|---|---|---|
| ANALYSIS_RUN_NOT_FOUND | 404 | 未知或非当前凭据所属 Run |
| ANALYSIS_NOT_READY/WORKER_BUSY | 409 | 任务非 READY 或 Worker 尚有未停止环境 |
| ANALYSIS_LEASE_INVALID | 409 | 错误代次、秘密或租约失效 |
| ANALYSIS_STOP_REQUIRED | 409 | 取消或关闭后必须停止 |
| ANALYSIS_STOP_UNCONFIRMED | 409 | 重试前旧环境停止未知 |
| ANALYSIS_STATE_CONFLICT | 409 | 不允许的重试、终态覆盖或核验状态 |
| ANALYSIS_REFERENCE_INVALID/ANALYSIS_CONFIG_MISMATCH | 409 | 证据/源码结构或实际配置不匹配 |
| INVALID_ANALYSIS_RESULT/INVALID_ANALYSIS_FAILURE/INVALID_STOP_BASIS | 400 | 结构、大小、停止或终止字段无效 |

2026-10-02 按用户授权替换为宿主分析，调整新任务策略、结果版本及停止字段；不再接受旧 Worker 的 complete(stopConfirmed=true) 写入。旧报告只读保留，旧冻结策略需另建新任务；未保留旧执行器写入兼容。自动化、真实宿主和质量边界见 [宿主验收](../analysis-validation/host-analysis-validation.md)；原五例质量评估结论继续见 [跨端历史](../analysis-validation/README.md)。

## 数据库切换门禁

V14 要求没有旧 BLOCKED/READY/RUNNING/CANCELLING 任务，且没有 RUNNING 或 stopConfirmed=false 的 Run；不满足时迁移原子失败，保留旧结构。关闭旧任务入口，取消未运行任务，完成指定宿主的实际停止核验后才能部署。迁移仅删除分析登记及重复列，不删除原始 Crash/符号服务 buildId，终态 evidence/result JSON、摘要及停止审计保持原字节。旧二进制不能直接复用迁移后数据库，回退须使用核验过的迁移前备份。
