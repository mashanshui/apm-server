# 变更提案

## Why

Android SDK 当前以普通 JSON 上传批量事件，Crash 诊断和重复元数据会增加传输量；服务端已有 gzip 解压与大小限制，但 SDK 尚未使用。为统一上报行为，本次将 SDK 自建网络会话发出的 JSON 批次全部改为 gzip，包括小批次和压缩后略微增大的批次。

## What Changes

- 在 `performance-transport` 共享网络层为 `POST ingest/v1/batches` 的 JSON 请求统一执行一次 gzip，保留 `application/json`，发送 `Content-Encoding: gzip` 和正确的压缩后长度。
- 覆盖已有 JVM/Native Crash、启动、FPS 和内存峰值上传；前台挂起仅使用合成 JSON 验证批次协议，不新增 Android 采集上传链路；不设置压缩阈值、压缩率判断或普通 JSON 自动回退。
- 保留原始 JSON 大小预算、冻结事件、本地队列、事件身份、响应分类与幂等语义，不因压缩扩大批次。
- 复用服务端现有有界解压，收紧 `Content-Encoding` 识别并补齐损坏 gzip、编码组合、大小限制和真实 HTTP 回归。
- 维持服务端对普通 JSON 的现有接收能力；Jank ZIP、泄漏报告 multipart、服务端响应、Dashboard 与 MCP 不纳入本次压缩范围。
- 复用现有日志设施记录压缩结果与失败阶段，不记录正文或凭据；不增加配置开关、依赖或数据库结构。

## Capabilities

### New Capabilities

- `event-batch-transport`：规定 SDK JSON 批次的 gzip 编码、请求头、资源限制、失败处理及服务端解码契约。

### Modified Capabilities

- `modular-sdk-composition`：明确全量与按需接入复用同一批次压缩行为，保持逻辑 JSON 格式与各领域上传语义，消除原“请求格式保持”与新增传输编码之间的歧义。

## Impact

- Android：主要修改 `performance-transport` 中的 `TransportSession` 与测试；Crash、Metrics 的真实 Retrofit 请求和上传队列作为回归范围，不修改事件模型或磁盘格式。
- 后端：`IngestController` 的编码校验、完整 gzip 读取与现有请求限制测试；保持 `/ingest/v1/batches`、Schema v2、App Key、错误码和批次响应结构。
- 资源：客户端压缩在现有后台上传链路执行，使用有界缓冲；服务端保留默认线上请求 1 MiB、解压后 4 MiB 的独立限制，不调整部署参数。
- 现有 `TransportSession.create` 外部 OkHttp/Retrofit 借用分支继续由宿主管理，不擅自改造外部实例；本次自动压缩承诺适用于 SDK 自建会话。
- 无前端、MCP、Nginx、数据库或依赖版本改动；不部署、不发布。规划阶段不修改知识库，归档时由 knowledge-sync 核对根/Android/后端知识库、API 与客户端说明。
