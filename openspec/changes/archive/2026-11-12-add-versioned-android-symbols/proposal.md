# Android 符号表版本管理变更提案

## Why

现有 mapping 必须按应用 buildId 上传，不能只登记应用版本默认文件；Native Crash 已有模块 ABI 和 ELF Build ID，但缺少 SO 管理入口。本变更扩展符号文件的登记与选择范围，事件上报仍保留现有非空 buildId 契约。

## What Changes

- **BREAKING**：mapping 上传必填 `appVersion`、选填 `buildId`。省略构建时登记该版本默认 mapping；填写时登记构建专用 mapping。事件始终携带构建，匹配时同版本专用优先，仅在专用不存在时回退同版本默认；存储故障不回退。
- Android 共享上下文、Crash/Native/FPS/Memory/Leak/Jank 模型与队列保留非空构建；公共 JSON v2 和 Jank v3 不放宽必填性，不引入可空事件、缺失值存储转换或新临时指纹。Leak report 服务端原有选填字段保持原契约，SDK 继续提供构建，不扩展其缺失语义。
- 按应用版本合并 JVM 后台调度与进度，以有界候选分页为事件分别选择 mapping；成功结果和已保存 Jank 证据固定，不重算。
- 在现有符号表模块新增 SO ZIP 上传、ELF/ABI/完整 GNU Build ID 和符号信息校验、元数据列表、幂等、整批冲突确认、条件删除及审计。SO 按版本归属、ABI + ELF Build ID 识别，不接受应用 buildId，不执行 Native 还原。
- 网页增加类型、版本和 mapping 默认/专用范围；JVM 入口预填版本与构建，Native 入口仅携带版本和 ELF 模块身份。Crash 查询和 MCP 契约不变。
- 旧无版本 mapping 保留原身份、文件和审计，不猜测版本、不参与新匹配；管理员按明确版本重传。切换排空在途租约与未确认发布，不删除遥测或重算成功结果。
- Rhea SDK 与 processor 固定 `1.0.4`，不修改 btrace，不要求新配套制品。Jank 使用同一不可变 ZIP 的预检版本，核对 processor 回调包名/构建及最终报告身份后使用版本选择器。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `application-symbol-management`：版本默认/构建专用 mapping、SO ZIP 管理、条件操作和旧登记隔离。
- `crash-background-symbolication`：按版本调度、有限候选分页和实际来源重试，保持首次成功固定、Native 不进入 JVM 还原。
- `jank-stack-artifact-ingestion`：保持 buildId 必填，按同一 ZIP 的版本与构建选择专用或默认 mapping，沿用 processor 1.0.4。
- `crash-frontend`：JVM 上传入口预填版本与构建，Native SO 入口不携带应用 buildId。

## Impact

- 后端：符号元数据及 Flyway 迁移、上传管理、统一选择器、Crash 后台与 Jank 版本选择。复用文件卷、审计、租约和首次成功发布机制。
- API：修改符号表请求响应和进度语义；公共上报 Schema、Crash/指标/内存查询、Agent 与 MCP 不因本变更变为可空构建。
- Android 与外部工程：继续使用 1.0.4 和既有构建生成/配置及队列，不改 btrace、采集模型和恢复格式；收回此前为无构建上报引入的修改及对应测试、文档。
- 前端：符号表页面及 JVM/Native 上传入口；不改事件/Issue 的构建可空语义。
- 配置：SO 上传、实际解压、并发、耗时及磁盘预算，协调 Spring multipart 和 Nginx。
- 文档：按实际实现原位同步根、后端、前端、Android 知识库和符号表/Jank API、客户端接入，明确尚未完成的上传与后台链路，不将计划写成事实。
