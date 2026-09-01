## Context

当前 `POST /ingest/v1/stack-artifacts:parse` 使用客户端请求头选择 mapping，同步调用 processor 后把完整 `RHEA_STACK_REPORT` 返回给客户端；它不写数据库。现有卡顿存储链路则从 `/ingest/v1/batches` 的结构化 `JankPayload` 生成分析和指纹，再通过 `EventRepository.append` 写入原始事件、卡顿事实和详情。

新客户端契约改为一个卡顿事件对应一个 `.rheajank.zip`，事件事实位于 v2 `sourceManifest`，采样证据位于报告的 `threads[].segments` 和 `callTree`。客户端继续提供当前 processor 必填的 `attemptedSampleCount`，processor 读取并校验后把它放入 `sourceManifest`；服务端归一化边界不读取该字段。当前 `rhea-trace-processor:1.0.0` 可以直接复用。参见 [proposal.md](proposal.md) 和两份 delta spec。

现有未归档变更 `add-binary-stack-artifact-parser` 记录的是旧“解析并返回报告”行为。本变更把它作为已实现基线，但不重写其历史任务；最终归档时旧 delta 不应再同步为平台正式接口事实。

## Goals / Non-Goals

**Goals:**

- 用一条同步请求完成鉴权、解析、服务端采样计数、归一化、指纹、幂等落库和最小响应。
- 复用现有项目 Key、输入大小和并发保护、文本脱敏、卡顿查询模型及 `EventRepository` 的去重和部分写入修复。
- 明确区分精确消息耗时、解析出的证据数量和估算覆盖，不伪造客户端尝试次数。
- 让指定真实样例的二进制证据进入自动化回归，证明主线程 454 条 segment 能被保存和查询。

**Non-Goals:**

- 不保存原始 ZIP 或完整 processor 报告，不增加对象存储、消息队列、异步任务和状态轮询接口。
- 不增加 mapping 上传、注册和生命周期管理；只读取现有项目隔离目录中的可选文件。
- 不重新实现 ZIP、manifest、SHA-256 或 Sampling v5 解码。
- 不在本变更中改变 Crash、场景 FPS、前台挂起汇总及其查询公式。

## Decisions

### 1. 原路径改为卡顿落库入口，不保留旧响应分支

保留 `/ingest/v1/stack-artifacts:parse` 路径，但媒体类型改为 `application/vnd.shanshui.rheajank+zip`，控制器删除 `X-Mapping-Id` 并返回新的最小响应 DTO。服务端只接受 v2 `RHEA_JANK`，不根据请求或产物类型分流到旧报告响应。

这样可以直接满足客户端已经接入的上传路径，又避免同一路径存在两套成功响应。替代方案是新增 `:ingest` 路径或保留 v1/v2 分支，但都会增加客户端和测试矩阵，与“不考虑旧兼容性”和首版简化原则冲突。

### 2. 复用当前 processor，由服务端解析一次

服务端继续只调用一次 `parseWithMappingResolver`。mapping 回调在产物完整性校验后使用认证得到的 `projectId` 和 metadata 中的 `buildId` 查找 `<mapping-root>/<projectId>/<buildId>.txt`；文件不存在时返回 `null`，文件存在时继续执行规范化路径与真实路径包含校验。

不在服务端预解包并修改 `attemptedSampleCount`，因为重写 manifest 会破坏 ZIP 契约边界，也会让 processor 不再是完整性校验事实来源。processor 按当前严格契约校验 manifest 并把已知字段复制到 `sourceManifest`；服务端只读取核心事件字段，不读取 `attemptedSampleCount`。服务端依赖继续固定为已验证的 `1.0.0`，并记录实际 JAR 哈希和公共 API。

### 3. 采样数量只统计目标主线程有效 segments

归一化层从 `sourceManifest.processId` 找到唯一目标线程，再筛选满足以下条件的 segment：

- `startOffsetNs` 位于 `[0, messageDurationNs]`；
- `stack` 是非空数组；
- segment 已由 processor 保留在消息分析窗口中。

`parsedSampleCount` 等于筛选后 segment 对象数量，包含 `kCustom`、`kObjectAllocation` 和其他 processor 认可的事件类型。它不等于报告顶层 `recordCount`，也不包含其他线程记录。若目标线程不存在、出现多个相同 tid 的线程或没有有效 segment，整个事件以 422 拒绝。

`expectedSampleCount` 使用消息区间除以 `minSampleIntervalNs` 向上取整并做整数溢出保护；`missingSampleCount` 是 expected 与 parsed 的非负差。parsed 大于 expected 时 missing 为 0，不截断 parsed，也不把 missing 命名为 dropped。

替代方案是只统计 `kCustom` 或沿用 manifest 的 attempted 值。真实样例没有 `kCustom` 却有 454 条有效主线程证据，前者会错误得到 0，后者会错误得到 1，因此均不采用。

### 4. 直接映射 processor 证据，不再运行旧 JSON 卡顿输入校验

新增专用归一化边界，把 `sourceManifest` 转换为公共事件事实，把主线程 segments 的规范化调用路径去重为稳定 stackId 字典，并把 processor 的估算区间和主线程调用树映射到现有查询模型。该边界复用现有脱敏、设备标识哈希和指纹服务，但不构造 `/batches` 请求，也不经过要求客户端质量字段的旧 JSON Schema/validator。

估算覆盖、调用树和 warnings 以 processor 报告为准，避免服务端对同一二进制再次执行一套可能不同的采样估算。归一化过程中只保留当前查询和证据下钻需要的数据，完整报告字符串不进入响应或存储。

### 5. 逻辑质量字段改名，数据库采用向前新增列

领域模型、卡顿详情 API 和 PC 控制台统一使用：

- `expectedSampleCount`：理论采样槽位数；
- `parsedSampleCount`：解析出的主线程有效 segment 数；
- `missingSampleCount`：理论槽位与解析数量的非负差。

删除 `attemptedSampleCount`、`successfulSampleCount` 和 `droppedSampleCount` 的对外语义。ClickHouse 新增版本化脚本，为 `apm_jank_event` 增加 `parsed_sample_count` 和 `missing_sample_count`；已存在的旧列暂不删除，避免破坏已部署表和回滚，但新代码不读取或写入旧 attempted/successful/dropped 列。JSON 事实和详情使用新字段名，前端同步更换标签。

替代方案是把 parsed/missing 填入旧 successful/dropped 列并伪造 attempted。虽然改动更少，但会让物理字段和真实语义永久冲突，不符合数据正确性要求。

### 6. 复用单事件 append 完成幂等和部分写入修复

归一化得到一个 `StoredEvent` 后调用 `EventRepository.append(projectId, List.of(event))`。返回 accepted=1 时响应 `accepted`；duplicate=1 时响应 `duplicate`。ClickHouse 适配器继续负责检查原始事件、卡顿事实和详情，并在重复请求时补齐上一次失败留下的事实或详情。

如果新事件写入在原始事实之后失败，接口返回可重试 503；客户端以同一 eventId 重试后进入现有修复路径。成功响应只包含 `success` 和 `status`，不返回 eventId，因为客户端 manifest 已持有该值。

### 7. 批量入口明确拒绝结构化 jank

`/ingest/v1/batches` 继续处理 Crash、app_start、frame_scene_summary 和 foreground_suspension_summary，但 `eventType=jank` 返回稳定的事件级永久错误。删除旧 jank JSON Schema 的客户端发布入口和相关构造示例；服务端内部可以在迁移完成前保留必要类型用于查询历史数据，但它们不再是上传契约。

这避免同一逻辑卡顿同时通过 ZIP 和 JSON 上报而形成两套采样质量口径。

### 8. 真实样例转为仓库内可重复夹具

把 `demo-jank-2070003140242350.rheajank.zip` 转为仓库固定测试夹具。回归必须验证服务端没有把 manifest 中的 `attemptedSampleCount=1` 用作质量数据，主线程仍得到 parsed=454、expected=460、missing=6，且首次 accepted、重复 duplicate，并能通过事件详情查询读回。

开发时仍使用用户给出的原始绝对路径做一次来源对照，但自动化测试不得依赖另一个仓库的绝对路径。

## Risks / Trade-offs

- [本地 processor 构件可能漂移] → 固定使用 `1.0.0`，记录服务端实际解析到的 JAR 哈希和 `parseWithMappingResolver` API；不以相同坐标替换不同内容。
- [同一路径发生破坏性语义变化] → 客户端和服务端按同一发布窗口切换媒体类型与 manifest；成功响应只保留新契约，避免静默误判。
- [同步解析和落库占用请求线程] → 保留 64 MiB 双重限制、解析并发许可和稳定 503；首版不引入异步基础设施，容量问题以真实压测作为后续触发条件。
- [报告可包含大量重复或唯一堆栈] → 以规范化调用路径生成稳定字典并实施现有限制；超过限制整体拒绝，不静默改变指纹证据。
- [ClickHouse 多表写入不是事务] → 继续依赖 eventId 去重和重复请求修复；响应只在 append 完整成功后返回 accepted/duplicate。
- [旧质量列与新字段并存] → 新代码和文档只使用新增列，旧列保留用于回滚且明确标记废弃；后续确认没有旧读者后再单独清理。
- [旧 OpenSpec 变更与新能力描述同一路径] → 本变更实施完成后，归档旧变更时不把旧 parse-only delta 同步成主规格；任何归档操作仍需用户明确选择和严格校验。

## Migration Plan

1. 客户端继续按当前 v2 manifest 契约上传 `attemptedSampleCount`，无需修改或重新发布 processor。
2. 服务端固定并验证 processor `1.0.0`，增加新质量列和领域字段，完成 ZIP 到现有卡顿存储模型的转换及自动化测试。
3. 同步更新 API、客户端接入、知识库、固定数据集和 PC 控制台，明确旧媒体类型、旧成功响应和批量 JSON jank 已退出契约。
4. 先部署服务端，再发布只发送新 manifest 和新媒体类型的客户端；使用固定项目完成一次上传、重复上传和查询下钻冒烟。
5. 回滚时服务端和客户端必须成对回滚到旧 manifest/旧解析响应版本；新增 ClickHouse 列保留，不执行破坏性删除。
