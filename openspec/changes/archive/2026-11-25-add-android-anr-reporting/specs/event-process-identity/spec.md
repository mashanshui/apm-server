# 事件进程身份增量规格

## MODIFIED Requirements

### Requirement: 各事件入口接收进程标识

系统 MUST 为现有JSON批量事件（启动、Crash、帧场景汇总、前台挂起汇总、内存运行峰值、ANR）、卡顿ZIP manifest和内存泄漏报告metadata接收必填processId。该值 MUST 为标准连字符UUID v4，拒绝空值、缺失、数值PID和非v4，沿用各入口永久错误结构。设备和启动ID既有必填/可选及长度规则 MUST 保持；服务端 MUST NOT 为缺失身份随机补值或从上传进程推断。内存峰值和 ANR 补传 MUST 保留原运行sessionId/processId，不以新启动身份替换；旧memory_sample不再作为受支持入口。

#### Scenario: 接收合法身份
- **WHEN** 任一入口其他要求均满足且带合法进程UUID
- **THEN** 接受并传递原始身份，不因主进程sessionId与processId相等而拒绝

#### Scenario: 拒绝缺失或错误进程标识
- **WHEN** 任一入口processId缺失、为空、数值或非v4
- **THEN** 按该入口既有校验错误和原子性边界拒绝，不写入无效事件并指出processId

#### Scenario: 新进程上传旧轮峰值
- **WHEN** 新进程封存并上传上一轮峰值后重试
- **THEN** 原sessionId/processId及版本保持不变，不将上传运行身份当成数据归属

#### Scenario: 新进程补传 ANR

- **WHEN** 新进程补传上一次运行中的 ANR
- **THEN** 上报、存储和详情保留 ANR 发生时的设备、启动和进程标识

### Requirement: 现有事件详情展示与复制

Crash、卡顿和 ANR 事件详情 API MUST 回传 `processId`，页面 MUST 分别展示“设备 ID”“启动 ID”“进程 ID”，每个有值字段 MUST 支持复制完整原值；已有进程名称 MUST 继续展示。空值 MUST 展示统一占位且不提供有效复制动作。复制失败 MUST 提供失败反馈，MUST NOT 显示成功。本版 MUST NOT 新增身份关联查询接口、列表、路由或 ID 跳转，也不新增内存详情页。

#### Scenario: 查看和复制完整身份
- **WHEN** 用户打开有完整身份的 Crash、卡顿或 ANR 详情并复制某个 ID
- **THEN** 页面复制该字段完整值并反馈成功，页面不跳转

#### Scenario: 缺失身份与复制失败
- **WHEN** 事件字段缺失或浏览器拒绝剪贴板写入
- **THEN** 缺失字段显示占位，写入失败显示失败反馈，不复制占位内容或误报成功

#### Scenario: 保持应用权限边界
- **WHEN** 用户没有对应应用事件的访问权限
- **THEN** 现有鉴权阻止详情访问，新增身份字段不绕过应用隔离
