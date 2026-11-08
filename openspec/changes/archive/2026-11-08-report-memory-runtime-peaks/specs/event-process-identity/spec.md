# 内存峰值进程身份规范增量

## MODIFIED Requirements

### Requirement: 各事件入口接收进程标识

系统 MUST 为现有JSON批量事件（启动、Crash、帧场景汇总、前台挂起汇总、内存运行峰值）、卡顿ZIP manifest和内存泄漏报告metadata接收必填processId。该值 MUST 为标准连字符UUID v4，拒绝空值、缺失、数值PID和非v4，沿用各入口永久错误结构。设备和启动ID既有必填/可选及长度规则 MUST 保持；服务端 MUST NOT 为缺失身份随机补值或从上传进程推断。内存峰值补传 MUST 保留原运行sessionId/processId，不以新启动身份替换；旧memory_sample不再作为受支持入口。

#### Scenario: 接收合法身份
- **WHEN** 任一入口其他要求均满足且带合法进程UUID
- **THEN** 接受并传递原始身份，不因主进程sessionId与processId相等而拒绝

#### Scenario: 拒绝缺失或错误进程标识
- **WHEN** 任一入口processId缺失、为空、数值或非v4
- **THEN** 按该入口既有校验错误和原子性边界拒绝，不写入无效事件并指出processId

#### Scenario: 新进程上传旧轮峰值
- **WHEN** 新进程封存并上传上一轮峰值后重试
- **THEN** 原sessionId/processId及版本保持不变，不将上传运行身份当成数据归属
