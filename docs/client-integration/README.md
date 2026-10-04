# 客户端接入文档

本目录统一保存面向 Android SDK、客户端开发和联调人员的接入文档。内容覆盖事件构造、客户端持久化、批量上传、重试和联调边界，不包含移动端 UI。

## 文档目录

- [Android 卡顿监控上传接入](jank-monitoring.md)
- [Android 卡顿压缩包上传接入](stack-artifact-upload.md)
- [Android 卡顿压缩包 manifest v3 契约](jank-artifact-manifest.md)
- [Android JVM Crash 上传接入](crash-client-integration.md)
- [Android 内存指标上传接入](memory-metrics.md)
- [Android 内存泄漏报告上传接入](memory-leak-reports.md)
- [Android 符号表网页上传与生效语义](symbol-mapping.md)
- [Android/processor 后续改造清单](android-processor-follow-up-checklist.md)

服务端请求/响应、查询、统计和错误语义统一见[服务端 API 文档](../api/README.md)。

Android 上报 X-App-Key 只用于接收入口，不能调用 [Agent 查询 HTTP API](../api/agent-query-api.md) 或 [MCP 工具](../api/mcp-api.md)。应用查询 Token 由网页管理员单独创建，不属于 Android SDK 采集、批量或重试链路。

## 事件身份生命周期

客户端首次安装生成并持久化安装级 UUID v4 `anonymousDeviceId`，所有进程共用；每次应用启动生成新的 UUID v4 `sessionId`。主进程的 `processId` 使用本次 `sessionId`，子进程每次创建生成新的 UUID v4，不能使用 Android 数值 PID 或进程名称。事件、ZIP 和报告进入本地队列时必须冻结这三个字段；跨启动补传沿用原字段，不能按上传时进程重写。

服务端会永久拒绝缺失、空值、数值 PID 和非 v4 `processId`，不会随机补值。内存泄漏报告的 `sessionId` 继续可选，其他现有入口的 `sessionId` 规则不变。当前 Android SDK 尚未在本仓库实现，生命周期和持久队列须由外部 producer/SDK 交付并用真实设备验收。

## 应用 Key 获取与绑定

联调前先在网页创建应用并填写唯一的全小写 Android application ID；服务端自动生成 UUID v4 `appId`，再由应用 `OWNER`/`ADMIN` 在设置页查看完整 `X-App-Key`。appKey 与包名永久绑定、不过期且不可轮换；JSON v2 事件和卡顿 manifest v3 都必须显式携带相同的 `packageName`。完整 appKey 不得进入日志、URL、Web Storage、截图或版本库。

## 本地分析试点验证

[Performance 单事件分析验收](../analysis-validation/README.md)记录构建身份、既有崩溃按钮和实际事件的分层验证。最初单事件试点未修改 Performance 源码；后续按授权新增独立崩溃测试页用于补充质量样本，详见同一验收记录。Android 事件字段、持久化、批量、重试契约及签名配置保持不变。Worker 凭据不能用于 Android 上报，模型密钥不进入 APK。

试点续跑按授权修复 Performance FPS 持久化 DTO 的 R8 保留及无效快照恢复，保留签名和现有事件契约；补充包装 cause 测试，完成五个受控场景评估。模型质量未达预期，结果和构建证据见同一跨端记录，不等于云端分析或自动修复已可用。

2026-10-02 本地 Crash 分析改为宿主 Agent，客户端事件、采集、队列及上传契约不变；既有设备样本复测见[宿主分析验收](../analysis-validation/host-analysis-validation.md)。

2026-10-03 当前宿主代码分析/修复的受控样本、三项 JVM 测试及证据不足零修改见[当前验收](../analysis-validation/current-code-validation.md)。此分析流程移除了独立构建源码登记；Android 上报 buildId 和 mapping 关联继续有效，客户端事件、队列及重试契约未变。
