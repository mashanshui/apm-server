## Why

应用目前不能通过网页上传和纠正 R8 mapping，Crash 详情只能显示原始堆栈，卡顿依赖运维预置文件。需要在现有应用权限体系内管理符号表，并让 Crash 在每次查看时使用当前 mapping 还原，避免保存派生堆栈带来的存储增长。

## What Changes

- 新增应用级网页符号表列表、单文件上传和管理员确认替换，按 `appId + buildId` 精确匹配。
- Crash 入库不执行还原；每次详情请求调用官方 R8 Retrace，返回原始异常链、还原文本及本次处理状态，不持久化或跨请求缓存还原结果。
- mapping 替换通过不可变文件和原子版本切换生效；原始事件、Issue 指纹和统计保持不变。
- 卡顿采用已确认的方案 A：保留上传时解析和还原、保存证据、详情直接读取的行为，只接入统一 mapping 注册表。
- **BREAKING**：以网页注册的 mapping 替代 `<mapping-root>/<appId>/<buildId>.txt` 手工目录约定；不迁移已有符号文件或事件。
- 不实现 CLI、构建自动上传、Native SO、多个 mapping 合并、文件下载或删除、历史数据批量处理、卡顿详情实时还原及内存引用链还原。

## Capabilities

### New Capabilities

- `application-symbol-management`：应用隔离的网页 mapping 上传、校验、查询、替换和文件生命周期。

### Modified Capabilities

- `jvm-crash-monitoring`：详情查询实时还原，结果仅存在于本次响应。
- `crash-frontend`：原始与还原堆栈展示、失败降级及带构建信息的上传入口。
- `jank-stack-artifact-ingestion`：从统一注册表选择 mapping，保持上传时处理方式。

## Impact

- 后端新增 `symbol.api/internal` 业务域、PostgreSQL/Flyway 元数据和本地持久卷文件，调整 Crash 查询与 Jank mapping 解析入口及架构白名单。
- 新增应用符号表管理 API，扩展 Crash 详情响应；不修改 Android 上报字段，不新增 ClickHouse 还原结果列。
- 前端新增应用符号表页面并调整 Crash 详情、列表状态文案。
- 引入固定版本官方 R8 Java 依赖；实施前核对 processor 的依赖冲突和实际 mapping 格式支持。
- 实施阶段同步根、后端、前端知识库，API、客户端接入及部署配置说明。导航：[设计](design.md)、[任务](tasks.md)、[符号表规范](specs/application-symbol-management/spec.md)。
