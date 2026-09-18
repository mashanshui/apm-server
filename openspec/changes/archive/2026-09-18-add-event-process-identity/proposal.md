## Why

当前事件已有匿名设备和会话标识，但缺少统一的进程实例标识，详情页也未统一呈现这些关联信息。本版先补齐事件身份的传输、存储和展示，为人工排查提供可靠上下文。

## What Changes

- 新增 UUID v4 格式的 `processId`，表示进程实例；主进程复用 `sessionId`，子进程每次创建独立生成。
- 明确设备 ID 为客户端生成并持久化的安装级 UUID，启动 ID 每次应用启动生成；事件产生时固定身份，补传保持不变。
- **BREAKING**：当前接收协议新增必填 `processId`，覆盖 JSON 事件、卡顿 ZIP manifest 和内存泄漏报告 metadata；不提供旧客户端缺失字段的兼容补值。
- 保留现有设备和启动字段校验边界；UUID 生成约定写入客户端契约，不额外收紧这两个已有字段的字符串格式。
- Crash 和卡顿现有事件详情展示设备、启动、进程 ID 并支持复制，已有进程名称继续展示。
- 暂缓设备／启动／进程关联事件列表、搜索页面和 ID 跳转；不新增内存详情页、设备注册、心跳或跨进程启动协调服务。

## Capabilities

### New Capabilities

- `event-process-identity`：跨事件入口的进程标识接收、保存、详情回传与只读复制展示，以及客户端身份生成和补传约定。

### Modified Capabilities

无。以新增公共身份能力补充现有领域协议；现有事件种类、统计口径、鉴权和页面路由不变。

## Impact

- 后端：公共事件信封、领域命令与元数据、三个接收入口的校验和映射、内存模式及 ClickHouse 存储、Crash／卡顿详情响应。
- 数据库：新增版本化 ClickHouse 迁移；不改写已发布迁移，不回填虚构 UUID，不清理现有数据。
- 前端：CrashEventDetailView、JankEventDetailView、关联类型、模拟数据和复制交互测试。
- 文档：根／后端／前端知识库，相关 API、JSON schema、客户端接入、manifest 和固定数据示例。
- 外部依赖：Android SDK 与卡顿 producer／processor 需要配套传递 `processId`，本仓库仅实现服务端与控制台及接入文档，不擅自修改其他仓库。
