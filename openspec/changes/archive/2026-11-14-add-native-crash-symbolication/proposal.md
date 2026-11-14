# Native Crash 后台符号还原提案

## Why

平台已支持按应用版本上传 SO，Native 事件也已保存模块身份、相对 PC 和 Java 快照，但上传后仍只能查看原始栈。需要接通可恢复的后台还原，使已存和新到事件都能使用 SO 与 mapping，并在符号文件分批上传时逐步补齐。

## What Changes

- 为 Native 主栈及其他 Native 线程增加后台地址还原，使用同应用、同版本、同 ABI、完整 ELF Build ID 精确选择 SO，取得函数名、可取得的源码位置及完整内联调用链。
- Native 按原始物理帧逐步补齐，首次取得有效函数名即成功并固定结果、处理时间和实际 SO 来源；只有函数名而没有行号也算成功，后续文件替换不再升级该帧。缺失和失败帧保留材料，按来源变化或临时失败退避继续处理。
- 同时还原 Native 事件可靠关联的 Java 栈和其他 Java 线程快照，复用同版本构建专用 mapping 优先、版本默认其次的规则；按完整 Java 栈首次成功固定，缺少 SO 与缺少 mapping 互不阻塞。
- 保留 Native 现有 `native-tmp-v1` 指纹、构建内归组及 `temporary` 类别；任何 Native/Java 还原与补齐均不改变问题归属、原身份或统计数量。
- 扩展现有版本后台工作、文件租约与跨库发布机制，支持 Native 多来源和多轮状态发布；持久化唤醒、有界公平扫描、首次成功单元固定和同字节恢复，不增加队列或新服务。
- **BREAKING**：扩展 Native 查询的结构化栈格式、逐帧/逐 Java 栈处理状态和实际来源；事件摘要的三态只描述 Native 主栈，辅助结果独立展示。SO 进度由“不适用”改为版本 Native 本轮进度；网页、Agent HTTP 与 MCP 同步使用新契约。事件上报格式及 JVM 查询行为保持原契约。
- 增加受资源限制的 `llvm-symbolizer` 运行依赖、真实 ELF 测试夹具、增量/故障恢复与查询性能验收；同步 API、客户端接入及各端知识库。
- 支持通过现有 SO ZIP 入口直接上传 AGP 生成的 `native-debug-symbols.zip`，接受 ABI 子目录中的 `.so.sym`、`.so.dbg` ELF 文件及既有 `.so`，无需用户解压、改名或重新打包。复用实际 ELF 身份校验、整批原子确认和后台唤醒，不按文件后缀承诺源码行号，不放宽上传与解压预算；超长文件名明确拒绝而非静默截断。

## Capabilities

### New Capabilities

无；复用已有能力边界。

### Modified Capabilities

- `native-crash-monitoring`：增加原始材料不变的逐帧还原、独立 Java 栈还原和保持既有归组的要求。
- `crash-background-symbolication`：增加 Native 专属版本工作、独立处理单元、多轮幂等发布与有界恢复，同时保持 JVM 首次成功固定。
- `application-symbol-management`：SO 精确读取租约、SO/mapping 对相应 Native 工作的可靠唤醒及按类型区分的版本进度；增加 AGP `.so.sym` 与 `.so.dbg` 符号包直接上传、文件名边界和实际内容校验。
- `crash-frontend`：展示 Native 逐帧结果、Java 栈结果、独立缺失原因、来源及 SO 后台进度，区分还原状态和归组状态；明确 AGP 符号包上传入口及函数符号、源码位置的能力边界。
- `apm-mcp-query`：严格解析 Native 增量还原及来源，通过既有详情和分片协议提供一致证据。

## Impact

- 后端：`crash` 状态、候选查询、调度、发布与详情；`symbol.api` 的 SO 租约及原生还原边界；`symbol.internal` 的文件生命周期、R8 复用及 LLVM 子进程。新增 PostgreSQL/Flyway 和 ClickHouse 迁移，不改已发布迁移。
- 前端与 MCP：Native 类型、运行时校验、主辅线程展示、符号管理进度和证据分片；保持应用授权与响应预算。Native 不因此获得 JVM 单事件分析/修复入口。
- 部署：后端镜像及本地开发明确 LLVM 版本、可执行路径和资源预算，继续使用现有单实例持久卷。规划包含切换与隔离验收，不自动执行本地或云端发布。
- Android 与外部 btrace：当前采集与冻结材料已满足输入，不要求改变 SDK、上报格式或依赖版本；补充客户端文档和已有设备样本的服务端验证。
- 文档：实现时原位更新根、后端、前端知识库和 Crash/符号表/Agent/MCP API、客户端接入；纠正路线图中 SO 上传仍未完成的过期描述。提案阶段不把目标写成已实现事实。
- AGP 符号包增量：只扩展现有归档候选格式、上传页面提示及对应测试/文档，复用既有 SO 身份、存储、授权、确认响应和后台流程，不新增接口、数据库迁移或 SDK 上报字段。本增量的根、后端、前端知识库及符号表 API、客户端符号接入说明随实现同步；不要求改动 MCP 契约或 Android 构建配置。
