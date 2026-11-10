# Native Crash 采集与上传

## 变更原因

当前 SDK 和服务端仅支持 JVM fatal Crash，C/C++ 致命信号无法进入现有持久队列、Crash 统计和详情。先完成 Native Crash 采集上传，保留后续还原必需的 SO 身份和地址信息；符号文件上传与符号还原留待独立变更。

## 变更内容

- 在 `performance-crash` 内复用并裁剪 xCrash 的致命信号捕获、独立 dumper 和回溯实现，采集崩溃线程与其他 Native 线程；保留现有 JVM 监控，使用独立 Native 开关并保持资源所有权。
- 上传 Native 信号、寄存器数值、原始 backtrace、模块 ABI/ELF Build ID，并尽力采集可取得的 Java 栈。
- Native Crash 同样上传已有 Java Crash 的日志、内存用量与受限 proc 摘要、FD、进程、设备及 Java 线程诊断；各分区有状态、失败原因、时间、截断和资源上限。
- **明确排除**原始栈内存 `stack`、寄存器附近内存 `memory near`、完整虚拟内存映射 `memory map`：正常、其他线程、降级及错误路径均不生成、不落盘、不上传。回溯必要的临时读取不生成对应诊断转储。
- 先保存固定身份和核心现场，补充诊断后冻结；下次启动转换并复用现有上传队列，重试不改变原发生时间、构建、会话和进程身份。
- 扩展现有 JSON v2 批次及单份详情栈，接受 `crash.kind=native`；复用当前事实/状态存储、精确去重及统计，Native 事件保持原始临时归组，不进入 JVM R8 后台任务。
- **BREAKING（消费者契约扩展）**：详情 `stack.format` 新增 `raw_native`，诊断新增 Native 线程原始栈；同步后端、前端、Agent HTTP 和 MCP 的严格校验。现有 JVM 请求及还原语义保持，不提供旧协议转换。
- 控制台展示 Native 原因、寄存器、模块与主辅线程栈，以及共享诊断；Native 不显示 JVM mapping 上传或当前 JVM 专用分析任务入口。
- 本期不实现 SO/调试符号上传、LLVM 还原、Native Java 辅助栈的 R8 还原、跨构建正式归组、完整 tombstone 附件、ANR、非致命异常或云端部署。

## 能力范围

### 新增能力

- `native-crash-monitoring`：信号捕获、原始主辅栈、完整有界诊断、禁止转储、可靠恢复、接收存储与临时归组。

### 修改能力

- `jvm-crash-monitoring`：解除共享批次拒绝合法 Native 的约束，允许两类 fatal Crash 复用诊断，保留 JVM 专属异常链规则。
- `crash-background-symbolication`：明确 Native 原始栈、Java 辅助栈及通知不进入 JVM mapping 后台工作。
- `crash-frontend`：详情按 JVM/Native 类型展示单份栈与线程身份，保持诊断可读性并限定 JVM 专用入口。
- `apm-mcp-query`：严格校验并有界返回 Native 主栈、线程和诊断，保留授权及摘要绑定。
- `modular-sdk-composition`：Crash 自有 Native 采集的开关、降级、初始化和关闭责任，不引入其他功能模块。
- `modular-sdk-distribution`：Crash AAR 携带自己的 JNI/dumper、必要打包约定和 Release 运行验证。

## 影响范围

- Android：`performance-crash` 的 Kotlin/JNI/CMake、队列、预算与配置；示例 Crash 页面、发布消费者和测试。引用 xCrash 本地检出为只读参考，不修改外部仓库；导入源码保留原许可证及可追溯版本。
- 后端：`crash` 接收、类型校验、脱敏、摘要、状态编码、查询；共享 JSON Schema；`agentquery` 的只读返回。复用 ClickHouse 现有 `crash_kind`、通用载荷列和 TTL，不预设新表；确需迁移时新增迁移，禁止改写已发布脚本。
- 前端与 MCP：Crash 类型、详情、线程面板、协议校验及测试；不新增管理接口或查询服务。
- 文档：实施时同步根/后端/前端/Android 知识库、Crash/Agent/MCP API、客户端构造与重试说明及样例；当前提案不把目标写成已实现。
- 不新增队列服务、缓存、对象存储、符号平台或部署拓扑；已有本地和云端数据不清空。
