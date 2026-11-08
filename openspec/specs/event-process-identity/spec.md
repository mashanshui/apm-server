# event-process-identity Specification

## Purpose

统一各类性能事件的设备、启动和进程实例身份，确保上传重试后仍能保留事件产生时的关联上下文，并在现有详情页提供可复制的标识，支持人工定位问题，为后续独立设计的关联查询提供基础。

## Requirements

### Requirement: 客户端身份生命周期约定

接入契约 MUST 约定 `anonymousDeviceId` 为客户端安装级 UUID v4，持久化并由各进程共用；`sessionId` 每次应用启动生成 UUID v4；主进程 `processId` MUST 等于该次启动 ID，子进程 `processId` MUST 每次创建独立生成 UUID v4。系统 MUST NOT 将 Android 数值 PID 当作该字段。跨进程启动归属协调不在本版实现范围。

#### Scenario: 主进程重新启动
- **WHEN** 同一次安装的主进程重新启动并产生事件
- **THEN** 客户端约定要求设备 ID 保持不变，启动 ID 更换，进程 ID 等于新的启动 ID

#### Scenario: 子进程重新创建
- **WHEN** 同一子进程名称对应的进程被重新创建
- **THEN** 客户端约定要求生成新的进程 UUID，不复用旧进程 UUID，也不将进程名称当作实例标识

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

### Requirement: 持久化和补传保留原始身份

系统 MUST 在各事件原始记录保存 `processId`，保持设备、启动、进程 ID 的原始关联。客户端接入说明 MUST 要求事件入队时固定身份。服务端 MUST 沿用既有事件幂等规则，不因新增进程字段把一次重试当成新事件，不更改统计分母和聚合维度。

#### Scenario: 跨启动补传
- **WHEN** 新进程上传旧进程产生的事件并重试同一事件
- **THEN** 存储和详情保留旧事件身份，既有幂等规则继续生效，不替换为上传时的新身份

#### Scenario: 存量记录无进程标识
- **WHEN** 读取迁移前没有进程 ID 的记录
- **THEN** 系统返回缺失值，不伪造 UUID，也不将启动 ID 自动填为进程 ID

### Requirement: 现有事件详情展示与复制

Crash 和卡顿事件详情 API MUST 回传 `processId`，页面 MUST 分别展示“设备 ID”“启动 ID”“进程 ID”，每个有值字段 MUST 支持复制完整原值；已有进程名称 MUST 继续展示。空值 MUST 展示统一占位且不提供有效复制动作。复制失败 MUST 提供失败反馈，MUST NOT 显示成功。本版 MUST NOT 新增身份关联查询接口、列表、路由或 ID 跳转，也不新增内存详情页。

#### Scenario: 查看和复制完整身份
- **WHEN** 用户打开有完整身份的 Crash 或卡顿详情并复制某个 ID
- **THEN** 页面复制该字段完整值并反馈成功，页面不跳转

#### Scenario: 缺失身份与复制失败
- **WHEN** 事件字段缺失或浏览器拒绝剪贴板写入
- **THEN** 缺失字段显示占位，写入失败显示失败反馈，不复制占位内容或误报成功

#### Scenario: 保持应用权限边界
- **WHEN** 用户没有对应应用事件的访问权限
- **THEN** 现有鉴权阻止详情访问，新增身份字段不绕过应用隔离
