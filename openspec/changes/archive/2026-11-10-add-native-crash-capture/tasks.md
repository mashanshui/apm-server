# Native Crash 采集上传任务

## 1. 后端联合载荷、存储与查询

- [x] 1.1 扩展 `CrashPayload`、JSON Schema、Native 信号/寄存器/模块/帧和诊断类型，按 kind 校验互斥载荷、位宽、引用及预算；新增合法/最小/混合/非法/超限合成样例并通过相应单元与 MockMvc 测试，确认原 JVM 请求不变。
- [x] 1.2 扩展 `CrashStack`、`CrashState`、状态 JSON 与诊断编解码支持 raw_native、nativeThreads 和 Java 关系未知；通过空栈、精确 64 位地址、零值、未知字段与往返序列化测试，确认没有新增原始/还原双份字段。
- [x] 1.3 将 Native 主辅栈、模块和全部诊断纳入内容摘要与当前事实/状态写入，保留部分写入修复及 CRASH_CONTENT_CONFLICT；用现有可靠性测试扩展 Native 同内容重试、换内容冲突、确认丢失及无权限读取场景，真实 ClickHouse 中只计一个逻辑事件，不改写旧迁移或清空现有表。
- [x] 1.4 实现 native-tmp-v1 临时指纹及现场不足类别，按应用/构建/ABI/信号/模块身份/相对 PC 归组；测试 ASLR、诊断、函数显示名变化不拆组，不同构建/ABI 不误合并，无帧不伪造精确 Issue。
- [x] 1.5 在接收通知、ClickHouse/内存候选和后台入口排除 Native 及其 Java 辅助栈；测试同构建 mapping 存在、上传/替换/删除及后台重启，断言 Native 没有 mapping 查询/R8 调用或状态改写，JVM 还原仍通过。
- [x] 1.6 扩展混合 Crash 查询固定集及人工期望，验证总览/趋势/Issue/事件列表、会话分母、重复和原发生时间口径；检查生成 SQL、EXPLAIN 和有界规模下的读取投影，证明列表不读诊断，执行后端所需 `bash backend/gradlew -p backend test` 并报告条件跳过。
- [x] 1.7 随本组同步 `docs/api/crash-api.md`、共享 JSON 样例、`docs/datasets/` 入口、后端知识库 02/03/06、根知识库 03/04/05 及符号表主题中的 Native 排除说明；核对字段、错误、预算、统计和本地链接与本组测试一致，不提前写外部验收通过。

## 2. Android 原生捕获与基础交付

- [x] 2.1 在 `performance-crash` 内引入来源可追溯的必要 xCrash 原生源码和许可证，裁剪 Java/ANR 重复功能，配置自有库名、JNI 桥和四 ABI CMake；通过 Debug/Release AAR 构建、依赖边界检查及许可/来源文件核对，构建不引用外部 xCrash 路径。
- [x] 2.2 增加 nativeEnabled 及进程级初始化/关闭所有权，预准备上下文、缓冲区、固定 eventId、文件和工作者；通过默认/关闭/不支持/初始化中断/重复启停测试，确认 JVM 保持、资源回收且不覆盖后来注册的第三方 handler。
- [x] 2.3 实现信号入口最小核心的有限记录提交，核对异步信号安全、fork/clone 后路径和备用栈覆盖范围；通过受控 SIGSEGV/SIGABRT 及写入中断测试，验证 Java 回调未执行也能恢复原时间、PID/TID、寄存器和构建身份。
- [x] 2.4 接入独立 dumper 的线程暂停、寄存器读取、原始回溯、模块和 Build ID，补充父子等待/回调截止及清理；通过真实主/后台 Native 崩溃、无 Java 线程、子进程启动失败/超时测试，确认主栈使用出错上下文、其他线程按 TID 区分且超时不无限等待。
- [x] 2.5 从正常、其他线程、fallback、dumper 自身错误中删除 stack/memory near/memory map 转储生成入口；加入可观测写入/读取路径测试并检查实际暂存，证明三类转储没有生成，必要 backtrace 与寄存器仍存在。
- [x] 2.6 随本组更新 Android 知识库 01/03/07、配置指南 16 和模块化说明 15，说明默认开关、信号范围、ABI、生命周期、禁止转储和失败边界；核对公开配置、来源说明及样例，尚未完成的运行环境明确标注。

## 3. Android 完整诊断、隐私和队列恢复

- [x] 3.1 为 Native 事件补齐其他 Native backtrace、Java 线程快照及可靠可关联的主 Java 栈；测试同名线程、TID/Java ID 不同、关联未知、空帧、主线程优先和观测/遗漏数，确保不按名称归并或把回调线程当作崩溃线程。
- [x] 3.2 接入本次现场的 logcat main/system/events、Java/Native/进程内存、受限 meminfo/status/limits、FD、进程和设备信息；通过正常、权限拒绝、Java 回调不可用、受控内存不足和超时测试，确认独立状态、单位、零/null 和原采集时间。
- [x] 3.3 在原生首次落盘、Java 冻结和服务端前后的文本处理中落实一致脱敏；用固定敏感样本检查 Native 暂存、队列、请求和日志，保留精确地址/Build ID，无法安全处理的可选文本明确缺失，不先保存完整明文 tombstone。
- [x] 3.4 实现 Native/Java 共享线程总预算、模块引用完整性和实际 UTF-8 JSON 预算；测试中文/转义/长函数名/大量线程/小批次配置，确认源端有标记裁剪、核心优先，客户端 240 KiB、诊断 128 KiB 及各分区上限成立。
- [x] 3.5 将有限 Native 暂存导入现有 FileCrashQueue，并在任何上传前恢复；测试尾部半写、校验失败、核心提交后中断、导入后清理前退出、nativeEnabled=false 恢复、512 KiB/32 份/16 MiB 暂存边界，保证不删已确认核心、不重复 eventId、不无限导入坏记录。
- [x] 3.6 复用 CrashUploader 的批次及响应语义，补 Native 的断网、408/429/5xx、永久错误、伪造计数和确认丢失测试；验证 Native 崩溃处理中不联网，启动后只上传冻结内容，重试不重新采集，运行 `:performance-crash:testDebugUnitTest` 和必要设备测试。
- [x] 3.7 随本组同步 Android 知识库 03/08/09、客户端 `crash-client-integration.md`、`symbol-mapping.md` 及接入导航；核对完整上传字段清单、禁止项、大小、关联未知、原身份补传与符号延期语义，本地链接检查通过。

## 4. 网页 Native 原始详情

- [x] 4.1 扩展 Crash 类型和 API 解码为 JVM/Native 联合分支，支持 raw_native、Native 线程和精确地址；组件/API 测试覆盖合法最小 Native、空帧、非法组合、缺分区及 JVM 回归，未知格式仍报协议错误。
- [x] 4.2 调整 Crash 导航和详情，显示信号/code/故障地址、寄存器、模块和原始主栈；Native 显示符号延期说明，隐藏 JVM mapping 与分析任务创建入口，通过组件测试确认角色、原始函数名及零地址展示正确。
- [x] 4.3 扩展现有诊断面板显示 Native/Java 线程分组及各自状态，复用日志/内存/FD/环境；通过同名线程、关联未知、截断、脚本文本和应用/事件/会话切换测试，旧响应不覆盖新页面，没有三类转储占位。
- [x] 4.4 运行前端 `npm test`、`npm run typecheck`、`npm run build`，以合成 API 检查桌面布局并保留截图；随本组同步前端知识库 01/03/04/06 和导航，区分组件、模拟 API 与真实后端验证。

## 5. Agent HTTP 与 MCP 读取

- [x] 5.1 使 Agent HTTP 原样提供当前 Native 主栈和完整有界诊断，保留原有应用授权、预算和错误；补 Token 隔离、原始详情和大诊断测试，并回归 Native 创建分析任务仍返回 UNSUPPORTED_ANALYSIS_EVENT。
- [x] 5.2 扩展 MCP server.ts/crashDiagnostics.ts 的 Native 联合校验，保持当前工具名和 stack/diagnostics section；测试大材料摘要绑定续读、精确地址、Native 线程、错误来源组合、越权/撤销和超限，运行 MCP 的 `npm test`、`npm run typecheck`。
- [x] 5.3 随本组更新 `docs/api/agent-query-api.md`、`docs/api/mcp-api.md`、MCP README 和后端只读查询/本地分析边界说明；通过接口示例与测试核对无新增符号上传、文件下载或 Native 分析写入口。

## 6. 示例与独立消费者交付

- [x] 6.1 在现有 Crash 测试页增加主线程 JNI SIGSEGV、命名后台 Native SIGSEGV、纯 Native 线程和 SIGABRT 入口；通过 UI/设备测试确认是实际致命信号、每次一个逻辑事件，不自行捕获成 JVM 异常，也不修改宿主已有 Native 工具 ABI。
- [x] 6.2 配置示例与独立 Crash 消费者所需原生库解压和受控 dumper 安装路径，使用本轮唯一 Maven Local 版本验证；检查四 ABI JNI/dumper、ELF/PIE/页大小、Release keep、APK 合并清单和运行依赖，无其他重型引擎，消费端不编译 SDK 原生源码。
- [x] 6.3 在 API 21 和 API 36 完成打包/加载/真实信号恢复冒烟，至少在可用架构实际执行 dumper；记录不能运行的 ABI、最低版本或真机缺口，不把产物存在当作运行通过，缺失的必需验收项保持未完成。
- [x] 6.4 随本组同步 Android 知识库 10/11/15/16、客户端接入示例和构建发布说明，核对默认开关、宿主打包约定、依赖来源、运行边界及回退方法，不执行 Maven Central 或云端发布。

## 7. 跨端集成验收

- [x] 7.1 在隔离服务与 API 29/35 目标上执行真实 Native 采集、下次启动补传和 ClickHouse/HTTP/网页/MCP 下钻；逐类核对主辅 Native 栈、Java 快照、日志、内存、FD、进程和设备的内容或真实失败状态，并确认三类转储全程不存在。
- [x] 7.2 联合注入断网、503、服务端已写但响应丢失、补充阶段终止和同构建 JVM mapping 变更；对照固定集确认原身份、一次计数、内容冲突、Native 不进入 R8 及现有 JVM 链路不回退。
- [x] 7.3 运行跨端改动要求的构建/测试、依赖边界、OpenSpec strict 和 Markdown 本地链接检查，审阅最终差异只涉及本变更；未执行或条件跳过项目保留真实状态，不因构建成功勾选设备验收。
- [x] 7.4 将可复用联合步骤与有限证据集中到 `docs/validation/native-crash/README.md`，加入 `docs/validation/README.md` 导航；依据实际结果原位同步根/后端/Android 当前状态、根测试页与路线图，列明 API/客户端/各端知识库同步文件和未验证边界，不追加日常部署流水。
