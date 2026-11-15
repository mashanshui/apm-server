# 模块化 SDK 组合增量规格

## MODIFIED Requirements

### Requirement: 拆分保持采集上传的可观察行为

各功能 MUST 保持现有默认采集口径、事件字段、认证头、端点、逻辑请求格式、持久化恢复、响应分类与重试删除语义；内存泄漏 MUST 仅上传 report JSON。全量与按需接入的 SDK 自建网络会话 MUST 按事件批次传输契约，对 JSON 批次统一使用 gzip 内容编码，该编码 MUST 不改变逻辑 JSON 或扩展至 Jank ZIP、内存泄漏 multipart。模块化 MUST 不引入功能之间的自动启停关系。

#### Scenario: 恢复待上传数据

- **WHEN** 同一版本消费者重启且存在未确认上传的 Crash、Jank、FPS、Memory 或 Leak 队列项
- **THEN** 对应功能按既有规则恢复和重试，确认前不因模块迁移改变的删除规则丢失数据

#### Scenario: 按各链路处理服务端结果

- **WHEN** 服务端分别返回成功、可重试、永久失败或无效业务响应
- **THEN** 对应功能维持原有分类和队列处理结果，不用一套统一判定覆盖各链路的差异

#### Scenario: 泄漏检测与消息历史配置

- **WHEN** Activity 泄漏被确认，或消费者使用默认 Looper 配置
- **THEN** dump 仍在生命周期/UI 线程之外触发且仅排队上传 report JSON；Looper 历史及密集记录仍默认关闭、显式开启

#### Scenario: 全量与按需接入共享 gzip 行为

- **WHEN** 消费者使用全量、仅 Crash 或仅 Metrics 接入，并通过 SDK 自建网络会话发送 JSON 批次
- **THEN** 各组合均使用同一 gzip 传输契约，不要求引入未选择的业务模块，也不改变原始事件、认证、响应或持久化规则
