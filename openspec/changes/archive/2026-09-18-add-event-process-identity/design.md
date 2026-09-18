## Context

动机及范围见 [proposal.md](proposal.md)。当前公共 EventEnvelope、EventMetadata 已包含设备和会话字段；Crash 和卡顿详情已有两类 ID，但显示方式不同。内存泄漏报告的 sessionId 当前可选，内存采样及报告没有本版需要新增的独立详情页。

该变更跨公共接收、Crash、Jank、Memory、ClickHouse 和前端，必须同时保证传递链路和错误处理；仅给 Vue 增加字段不能形成闭环。

## Goals / Non-Goals

目标：三个接收入口到原始存储无损传递进程 UUID，现有 Crash／卡顿详情可查看和复制身份。

非目标：不增加聚合维度、身份索引表、查询页面、关联 API、跳转或新内存详情；不实现 SDK 跨进程启动协调。本仓库产出客户端契约和验收说明，外部 SDK／processor 实现及真机验收单独交付。

## Decisions

### 1. 复用公共信封，新增 processId

JSON 顶层、manifest 顶层和报告 metadata 顶层使用一致字段名。新增字段要求标准 UUID v4，接受标准大小写十六进制并保留原值，禁止清洗时截断或替换 UUID。沿用各入口当前版本号及字段校验失败语义，在初次开发阶段直接更新当前契约并显式标记破坏性变化，不额外维护旧协议分支。

设备和 sessionId 不在此次追加格式收紧，避免将进程字段工作扩大为全部身份历史整改。内存报告原有可选 sessionId 保持可选，显示时允许缺失；其他入口保持既有必填规则。

主进程 processId=sessionId 为客户端生成约定，服务端不凭字段值判断主进程或强制改写。子进程使用其产生事件时携带的 sessionId，本版不通过当前主进程状态回填或纠正归属。

### 2. 明确映射链路

- JSON：EventSchemaValidator／schema → EventEnvelope → BatchIngestionService → 各领域命令、元数据、sanitizer → 原始仓储。
- 卡顿 ZIP：入口读取并校验 manifest 的 processId，在 processor 结果归一化到领域事件时显式传递。先核查 processor 对新增 manifest 字段的接受能力；若依赖升级不可避免，列为外部配套阻塞，禁止静默丢弃或生成替代值。
- 内存报告：metadata 白名单及解析 → 报告领域对象 → 原始仓储。
- 查询：Crash、Jank 详情 DTO 和前端类型回传新字段。内存数据完成接收与保存，不为展示新增详情能力。

复用各模块 api 边界，不引入新的身份服务或内部包跨域依赖。已有 processName 的路径继续透传，不强制给所有事件增加新必填进程名称。

### 3. 增量存储与幂等

新增版本化 ClickHouse 迁移，为承载相应事件的 apm_event_raw、apm_jank_event、apm_frame_scene_summary、apm_device_suspension_segment、apm_memory_sample、apm_memory_report 增加 process_id。实施时核对查询实际源表，避免只改写入端。使用可表达缺失值的字符串列保存迁移前记录，新增有效写入始终要求非空 UUID。

不改排序键、TTL、统计聚合及事件去重键；已有内容冲突检测若覆盖元数据，则将 processId 纳入同一规范化内容比较，不引入新的冲突机制。内存仓储与 ClickHouse 仓储保持相同字段语义。

### 4. 详情只读展示

在 CrashEventDetailView 和 JankEventDetailView 现有元信息区分别显示三个中文标签，提供复制按钮与成功／失败反馈。长 UUID 允许换行或视觉截断，但复制完整值。主进程即使两个 ID 相同也分别展示，缺失显示“—”。不增加可点击路由链接。

### 5. 验证和文档边界

测试覆盖各入口合法／非法 UUID、映射保存、重试、历史缺失、两个详情响应及复制反馈。浏览器截图证明实际布局，区分模拟接口与真实后端证据。API 和客户端文档同步所有受影响入口与示例，知识库各自维护架构、存储和展示说明。

## Risks / Trade-offs

- 新字段必填会拒绝尚未适配的 producer → 发布前协调客户端，明确永久错误，不在服务端补造 ID。
- processor 可能拒绝未知字段 → 实施前用真实 fixture 核对，依赖不可用时如实记录阻塞，不声称 ZIP 链路完成。
- 多进程 sessionId 归属尚不由服务端定义 → 本版只保存，不提供关联查询，后续查询设计时确定协调机制。
- 存量数据缺失 processId → 保留缺失并显示占位，不虚构历史身份。
- SDK 独立仓库未修改 → 本仓库自动化不能替代真实 UUID 生命周期和补传验收。

## Migration Plan

1. 先增加数据库列，再部署包含新字段的服务端和控制台；不修改已发布迁移或清空数据。
2. 同步发布契约和安全示例，协调 SDK 与 ZIP producer 配套，进行三个入口的固定数据联调。
3. 失败时回退应用版本，保留新增列；客户端契约回退同步处理，不执行删列和数据删除。
4. 最终报告分别说明后端自动化、前端自动化、数据库联调、浏览器和 Android 外部验证状态。
