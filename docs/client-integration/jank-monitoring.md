# Android 卡顿监控上传接入

本文统一维护卡顿个例、场景 FPS 和前台挂起汇总的客户端流程。身份、认证和批量确认见[接入公共规则](README.md)；正式请求响应见[卡顿压缩包 API](../api/stack-artifact-api.md)与[卡顿指标 API](../api/jank-server-api.md)。

## 两条接收链路

| 数据 | 接口 | 客户端产物 |
|---|---|---|
| 一个主线程卡顿个例 | `POST /ingest/v1/stack-artifacts:parse` | 原始 `.rheajank.zip` |
| 场景 FPS、前台挂起汇总 | `POST /ingest/v1/batches` | JSON v2 指标批次 |

批量入口不接受结构化 `eventType=jank`，会返回永久的 `JANK_ARTIFACT_REQUIRED`；同一卡顿不再同时构造 JSON 个例。现有采集、异步来源和导出入口见 [Android Jank 专题](../../android/docs/knowledge-base/04-Jank监控与堆栈产物.md)，默认配置见 [SDK 配置指南](../../android/docs/knowledge-base/16-SDK配置指南.md)。

## 卡顿 ZIP 的构造与持久化

1. 在消息发生时冻结 `eventId`、墙上时间、精确消息边界、原身份、包名、版本、`buildId` 和场景。
2. 将 `sampling.bin` 与 `sampling-mapping.bin` 导出到同一临时目录，关闭文件后计算真实大小和 SHA-256。
3. 按 [manifest v3 契约](jank-artifact-manifest.md)生成 `manifest.json`。`attemptedSampleCount` 仍由 processor 读取和校验；服务端不以它计算成功采样数或丢失数。
4. ZIP 根目录只包含这三个固定条目，关闭并刷盘后原子重命名为 `<eventId>.rheajank.zip`，随后持久化队列记录。
5. 重启恢复和网络重试使用原 ZIP 字节及全部原元数据，不能重新导出或替换身份。服务端质量、指纹和解析由服务端计算，客户端不另算 samples、调用树或质量字段。

UUID v4 `processId` 必须能对应目标主线程，不能使用 Android 数值 PID。异步采样的 extra 和 processor 版本要求见 [API 能力边界](../api/stack-artifact-api.md#1-能力边界)；制品准备和环境发布状态见[平台待确认事项](../knowledge-base/13-待确认事项.md#二进制堆栈解析)，不以历史同步采样结果证明异步采样通过。

## 卡顿 ZIP 上传与确认

使用 `Content-Type: application/vnd.shanshui.rheajank+zip` 和 `X-App-Key` 直接发送文件字节；不套 JSON、Base64、multipart 或外层 gzip。上传前核对文件非空、请求大小和当前部署限制。服务端按认证应用与 manifest 的 `buildId` 查找可选 mapping，客户端不发送 `X-Mapping-Id` 或 mapping 文件路径。

| 结果 | 本地处理 |
|---|---|
| HTTP 200，`success=true` 且 `status=accepted/duplicate` | 先持久化已确认状态，再删除队列项和 ZIP；删除失败后继续清理，不重新上传已确认产物 |
| 网络中断、超时、正文损坏或非预期成功响应 | 保留原 ZIP、eventId 和身份，按现有上限退避重试 |
| 503 暂时不可用或解析繁忙 | 保留原 ZIP，遵守 `Retry-After` |
| 400、401、403、413、415、422 永久错误 | 停止原样重试，按现有队列策略隔离或清理，记录错误码与原因 |

完整错误码和请求示例由 [API](../api/stack-artifact-api.md#5-错误语义)维护。没有收到响应不能推断服务端未写入，同字节重试通过 `appId + eventId` 去重。成功正文不含解析报告、mapping 状态或采样质量，不能以缺少这些字段判定失败。

当前实现见 [JankArtifactReporter](../../android/performance-jank/src/main/java/com/shanshui/performance/jank/JankArtifactReporter.kt)、[JankArtifactUploader](../../android/performance-jank/src/main/java/com/shanshui/performance/jank/JankArtifactUploader.kt)和 [JankArtifactNetworkClient](../../android/performance-jank/src/main/java/com/shanshui/performance/network/JankArtifactNetworkClient.kt)。

## 场景 FPS 与前台挂起汇总

两类指标分别使用 `eventType=frame_scene_summary` 和 `eventType=foreground_suspension_summary`，放入 JSON v2 公共信封；每条事件只携带对应专用载荷。身份、原发生时间和构建在入队时固定，批量确认按[公共确认规则](README.md#批量响应与队列确认)处理。

| 产物 | 客户端采集与字段来源 |
|---|---|
| 场景 FPS | [FPS 专题](../../android/docs/knowledge-base/05-FPS采集与场景汇总.md)、[算法说明](../../android/docs/knowledge-base/17-FPS算法说明.md)、[frame_scene_summary Schema](../../backend/src/main/resources/schema/frame-scene-summary-v2.schema.json) |
| 前台挂起 | [前台挂起专题](../../android/docs/knowledge-base/05-FPS采集与场景汇总.md)、[foreground_suspension_summary Schema](../../backend/src/main/resources/schema/foreground-suspension-summary-v2.schema.json) |

场景使用稳定低基数名称，算法版本、时间单位、刷新率及挂起阈值按 Schema 与服务端配置核对。队列可恢复不代表未结算的采集桶均已持久化，升级后恢复元数据的现有缺口见 [Android 待验证事项](../../android/docs/knowledge-base/12-常见问题与待验证事项.md#源码已确认待处理)。查询、趋势、分位数和设备日挂起率统一见 [API](../api/jank-server-api.md#fps设备日挂起率和多维查询)，客户端不另维护服务端统计公式。

## 联调检查

- ZIP 条目、消息边界、两个文件的大小和摘要一致，原 ZIP、服务端接收字节及 processor 输入可对齐。
- 首次 accepted、重复 duplicate、HTTP 详情质量字段和统计一致；质量样例使用对应固定数据集，不能把任意 manifest 套用固定 expected/parsed/missing 数量。
- 错误 Key、错误包名、数值 PID、旧媒体类型或版本、空文件、损坏 ZIP、超限、解析繁忙和存储故障均按对应语义处理。
- 验证客户端和服务端重启、响应丢失、已确认后文件删除失败；原 eventId、ZIP 和身份保持不变。
- 指标批次覆盖部分接受、计数错误和定位失败；日志不输出完整 Key、原始硬件标识、ZIP、完整堆栈或敏感正文。

既有主机/设备证据与剩余验收见 [Android 构建测试](../../android/docs/knowledge-base/11-构建测试与本地发布.md)和[客户端接入待验收](../../android/docs/knowledge-base/12-常见问题与待验证事项.md#客户端接入待验收)。历史 processor 输入和 HTTP 证据仍在 [API](../api/stack-artifact-api.md#1-能力边界)保留，不能直接作为当前生产制品或全设备验收结论。
