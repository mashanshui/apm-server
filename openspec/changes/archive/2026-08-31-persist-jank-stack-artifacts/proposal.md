## Why

客户端已经能够生成包含事件元数据和 Sampling v5 二进制数据的 `.rheajank.zip`，继续要求客户端把卡顿证据展开为 `/ingest/v1/batches` 的 JSON 并重复提供 `attemptedSampleCount` 会产生两套事实来源。服务端应直接解析压缩包、从解析结果计算实际采样数量并完成幂等落库，让客户端只处理文件上传成功或失败。

## What Changes

- **BREAKING**：直接改变 `POST /ingest/v1/stack-artifacts:parse` 的语义，只接受 `schemaVersion=2 / artifactType=RHEA_JANK` 的 `.rheajank.zip`，不再兼容 v1 `.rheatrace.zip`，也不再返回完整 `RHEA_STACK_REPORT`。
- 客户端继续按当前 processor 契约上传 `attemptedSampleCount`；processor 负责读取和校验，但服务端不得使用该值生成采样质量、统计或指纹，也不得将其作为正式质量字段落库。
- **BREAKING**：卡顿详情中的采样质量字段统一为服务端派生的 `expectedSampleCount`、`parsedSampleCount` 和 `missingSampleCount`，删除 `attemptedSampleCount`、`successfulSampleCount` 和 `droppedSampleCount` 的客户端语义。
- 服务端使用 `parseWithMappingResolver` 完成 ZIP、manifest、SHA-256、Sampling v5 和可选项目级 mapping 解析，并从 `sourceManifest.processId` 对应线程的有效 `segments` 计算 `parsedSampleCount`。
- 服务端由消息区间和最小采样间隔计算 `expectedSampleCount`，再计算 `missingSampleCount=max(0, expectedSampleCount-parsedSampleCount)`；不得使用报告顶层 `recordCount` 代替主线程采样数。
- 将 manifest 事件事实和解析出的采样证据转换为现有卡顿事实、详情、调用树和指纹，复用 `EventRepository` 写入 `apm_event_raw`、`apm_jank_event` 和 `apm_jank_detail`。
- 以 `projectId + eventId` 保证上传重试幂等；接口只返回 `accepted` 或 `duplicate` 成功状态，失败继续使用稳定 HTTP 错误和可重试语义。
- 保留 `/ingest/v1/batches` 处理 Crash、场景 FPS 和前台挂起汇总；Android 卡顿个例改由压缩包入口上报。
- 首版不增加消息队列、对象存储、新服务拆分或异步任务，也不持久化原始 ZIP。

## Capabilities

### New Capabilities

- `jank-stack-artifact-ingestion`: 定义 v2 卡顿压缩包的项目鉴权、解析、服务端采样计数、幂等落库、最小成功响应和错误语义。

### Modified Capabilities

- `jank-monitoring-server`: 将卡顿个例的数据来源从批量 JSON 载荷调整为服务端解析 `.rheajank.zip`，并用服务端派生的采样质量字段替代客户端 `attemptedSampleCount`。

## Impact

- 影响 `StackArtifactController`、`StackArtifactParseService`、mapping 解析、卡顿事件转换、指纹和现有事件存储适配器。
- 影响 `/ingest/v1/stack-artifacts:parse` 的媒体类型、请求字段、响应体和错误契约；不要求兼容旧解析响应。
- 影响卡顿 manifest、客户端上传步骤、卡顿服务端 API、知识库中的接收/存储/安全/测试边界。
- 影响卡顿事件详情 DTO、固定数据集和 PC 控制台采样质量展示，需要同步改为“期望、已解析、缺失”口径。
- 复用已验证的 `rhea-trace-processor:1.0.0`、现有同步并发限制和 ClickHouse 卡顿表；服务端只消费 processor 输出中的核心事件事实和采样证据，不消费 `attemptedSampleCount`。
