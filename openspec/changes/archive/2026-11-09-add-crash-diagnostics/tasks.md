# Crash 诊断上下文实施任务

本清单依据 [提案](proposal.md)、[设计](design.md)及三份 [采集](specs/jvm-crash-monitoring/spec.md)、[后台](specs/crash-background-symbolication/spec.md)、[页面](specs/crash-frontend/spec.md)规范增量编排。复选框表示已实现并完成对应验证；实施时每个新增或修改的方法、变量按仓库规则注释，日志只保留安全执行信息。真实服务与设备验收使用隔离合成数据，日常部署和云端升级不属于本清单的默认操作。

## 1. 上报与详情契约

- [x] 1.1 扩展 Android/后端 Crash 诊断模型和后端 JSON Schema，明确七类分区、采集状态、Java 线程字符串 ID、coreTruncation、字节单位及其他线程独立当前栈；保持主 `stack` 纯异常材料。以合法完整/最小/无诊断、空帧、未知状态、重复线程标识和 app_start 携带诊断的模型及校验测试验证。
- [x] 1.2 扩展后端 `CrashEventValidator`，校验独立数量、帧数、文本、数值、状态和完整事件 256 KiB 边界；加入直接绕过 SDK 的超限/非法诊断与批次部分接受测试，验证不静默丢字段、不修改现有主异常 16 层/200 帧配额。
- [x] 1.3 在 `docs/api/crash-api.md`、`docs/api/crash-error-codes.md` 及对应 schema/HTTP 测试中定义 diagnostics 入参与详情差异、缺失语义、CRASH_CONTENT_CONFLICT 和现有错误定位；同步 `docs/client-integration/crash-client-integration.md` 的契约链接。用安全合成请求逐项核对样例与序列化测试，不复制真实 tombstone。

## 2. Android 诊断采集与大小保护

- [x] 2.1 增加仅属于 `performance-crash` 的线程/环境采集器，在处理器入口保留崩溃 Thread 与系统 TID，按对象排除它并优先保留可取得的主线程；测试同名线程、后台崩溃、空帧、数量/帧数裁剪、Java ID 与 TID 区分和观测总数未知情形。
- [x] 2.2 增加 logcat 的固定参数、PID 过滤、分块字节读取和有界子进程生命周期，分别使用 200/50/50 行数并共享 500ms/剩余总预算；通过可注入进程与流测试验证超长无换行、中文切分、stderr 填满、权限失败、API 24 以下过滤、超时清理和空日志成功。
- [x] 2.3 增加内存、FD、进程和设备分区，使用公开 API 与自身 proc 文件，缓存可安全提前获取的环境和生命周期状态；测试内存单位转换/溢出/零值、Runtime 堆与 PSS 区分、proc 不可读、FD 消失、未知前后台和缺少进程启动时间，确认没有引入 Metrics/xCrash/原生依赖。
- [x] 2.4 增加诊断文本和私有路径脱敏，复用适合的既有规则并补齐凭据形式；以合成 token、邮箱、手机号、URL 和私有文件名哨兵测试本地捕获记录及排障日志均不泄露，结构化 PID、UUID processId、buildId 和内存数值不被误改。
- [x] 2.5 实现完整 UTF-8 事件 240 KiB 目标、128 KiB 诊断总额和各分区配额，按单事件批次外壳预留 requestId 并冻结前复验；测试中文、JSON 转义、最小 maxBatchBytes、巨长主异常及辅助内容，验证主异常最小有效结构、coreTruncation 和确定裁剪顺序。
- [x] 2.6 运行 `bash android/gradlew -p android :performance-crash:testDebugUnitTest`；同步 `android/docs/knowledge-base/03-Crash采集与上报.md`、`09-服务端对接与数据协议.md`、`16-SDK配置指南.md` 与 `11-构建测试与本地发布.md` 的采集、大小、默认启用和验证边界，核对实际默认值及本地链接。

## 3. Android 核心落盘、冻结与恢复

- [x] 3.1 扩展 `FileCrashQueue`，实现 capturing 核心原子保存、冻结到 events、启动恢复和重复文件处理；通过关键写入点中断测试验证临时文件/已存在 events/损坏 capturing/过期记录场景，恢复不覆盖最终记录、不延长原保留期。
- [x] 3.2 改造 `CrashReporter` 的完整 Thread 传递、预建单诊断工作线程、1500ms 等待、迟到结果隔离和原异常处理器委托，保持已有上传互斥与最多一批 3500ms 语义；通过可控时钟、阻塞采集器、受控 OOM 和并发 flush 测试验证核心记录保留、资源清理和只委托一次。
- [x] 3.3 让启动恢复先于周期/启动上传，并在已有 uploader 测试中覆盖捕获未冻结不可见、断网重启、原 eventId/身份/诊断补传以及 accepted/duplicate 后删除；运行 Crash 模块单测，确认不将重复上传当成诊断追加。
- [x] 3.4 在示例 Crash 测试页增加受控同名线程、阻塞线程和有限日志/FD 场景，持有测试资源至触发崩溃并避免真实秘密；更新应用单元/仪器入口测试和 `android/docs/knowledge-base/10-示例应用与调试路径.md`，以 `:app:testDebugUnitTest`、`:app:assembleDebug` 和入口检查验证可用性。
- [x] 3.5 同步 Android Crash/队列主题、`android/docs/knowledge-base/08-网络通信与持久化队列.md`、`12-常见问题与待验证事项.md` 及客户端接入说明，修正“线程池上传”与 Handler 同步尝试的已有差异；用队列测试和实际时序核对文档，明确等待预算不等于 ART/磁盘操作硬中断。

## 4. 后端诊断持久化、幂等与查询

- [x] 4.1 扩展后端诊断脱敏和确定编码，拆分主异常、不可变上下文和辅助原始帧，计算不含 receivedAt/批次身份的 capture_sha256；测试两端规则差异、字段顺序稳定性、结构化标识保留及主 stack 不重复嵌入 diagnostics。
- [x] 4.2 新增版本化 ClickHouse 迁移，为事实增加诊断上下文、为状态增加辅助当前栈并保存内容摘要；新增 Flyway 迁移将既有 publication 单份检查约束由 2 MiB 调至 4 MiB。以干净库升级和已有无诊断行迁移测试验证默认空值、TTL 不变、4 MiB 边界及超限拒绝，不改写旧迁移。
- [x] 4.3 同步 `CrashFact`、`CrashState`、`CrashStateJson`、内存/ClickHouse 适配器、启动结构校验和接收流程；覆盖状态先写/事实失败/确认丢失后原内容补全、不同内容 CRASH_CONTENT_CONFLICT、成功后正常重试、历史缺失诊断不回填，验证 accepted 仅在全部材料确认后返回。
- [x] 4.4 扩展 `CrashEventDetailResponse` 和详情组合，保持 appId/eventId/构建/发生时间有界定位及同一状态版本；以 API/适配器测试验证完整与空诊断、无权限 404、主异常还原后非堆栈内容不丢失、零值和空帧不被伪造。
- [x] 4.5 为统计、Issue、事件列表和后台候选 SQL 显式裁剪列，复用既有 Crash 查询性能夹具增加大诊断载荷；检查 EXPLAIN 与 query_log 读取列/字节，验证摘要不读 diagnostics_payload/thread_stacks_payload、原统计分母和分页不变、超预算仍明确拒绝，证据原位维护到后端 Crash 查询基线。
- [x] 4.6 运行 `bash backend/gradlew -p backend test`，并执行配置了真实 PostgreSQL/ClickHouse 的本组专项测试；同步后端 `02-接收解析与查询链路.md`、`03-持久化与数据库迁移.md`、`04-认证授权与安全实现.md`、`06-测试与质量保障.md`，以及根知识库 `03-事件模型与数据存储.md`、`04-上报协议与可靠性.md`、`06-安全与隐私.md`，区分普通单测与外部数据库实测。

## 5. 后台辅助还原与一致发布

- [x] 5.1 在符号模块增加辅助栈专用的单工作线程、无队列、有等待/输出预算入口，并支持保活同一 mapping 的独立固定引用；测试工作者忙、超时、忽略取消、mapping 替换/删除、租约最终释放和迟到回调隔离，确认不增加无限替代线程、不放宽既有共享许可。
- [x] 5.2 扩展栈格式化与后台处理，先还原主异常再在同一 mapping 下逐个处理辅助线程，落实 2000ms 等待、单线程 32 KiB/总计 256 KiB 编码输出；通过空帧、完整内联/多候选、失败、忙、超时和超限测试验证独立状态及原始帧保留，正式指纹不混入辅助内容。
- [x] 5.3 扩展 publication 确定编码、载荷上限和读回对账，保留 16 MiB 在途总预算与现有候选查询资源边界；测试极端 JSON 转义、辅助输出退回原始帧、成功登记后确认丢失重放、过期执行者和不同 mapping 迟到，验证同一版本主/辅结果一致且主异常可用结果不因辅助越界丢失。
- [x] 5.4 回归缺少 mapping、主异常失败、补传 mapping、首次成功后替换/删除 mapping、事件过期和进程重启；使用既有后台/可靠性专项测试验证辅助失败在主成功后固定、不另启补算，非堆栈上下文摘要及事件统计不变。
- [x] 5.5 运行后端测试及本组 PostgreSQL/ClickHouse 发布恢复专项；同步 `backend/docs/knowledge-base/symbol-mapping.md`、后端测试页、`docs/api/crash-api.md` 与 `docs/client-integration/symbol-mapping.md` 的主/辅状态、固定来源、4 MiB 登记上限和回退限制，以真实测试结果核对描述。

## 6. 详情页面和现有只读消费者

- [x] 6.1 扩展前端类型、`requireCurrentCrash` 和诊断运行时解析，保持原主 stack 契约，补齐其他线程 raw_frames/retrace_text 和独立状态；以完整、null、空帧、未知状态/格式、损坏结构和合法零值的 API 测试验证不会猜测旧协议。
- [x] 6.2 在 `CrashEventDetailView` 及专用诊断组件中增加线程展开、logcat 缓冲区、内存、FD 和进程/设备区域，展示身份、来源、失败及截断说明；组件测试覆盖同名线程、主成功辅失败、未采集、空结果、主异常源端裁剪、HTML 转义以及切换应用/事件后的迟到响应和选择清理。
- [x] 6.3 回归现有 Agent HTTP/MCP 详情透传与格式校验，补充 diagnostics 的安全输出和大小测试；同时验证 `AnalysisEvidence` 仍显式冻结原主异常片段、证据 3/报告 4 未扩大。运行 MCP 的 `npm test`、`npm run typecheck` 和相应后端分析契约测试，明确网页现场与冻结分析材料的区别。
- [x] 6.4 在 frontend 目录运行 `npm test`、`npm run typecheck`、`npm run build`；同步前端知识库 `03-页面路由与交互.md`、`04-API数据模型与状态管理.md`、`06-测试与质量保障.md` 和根 `05-查询与Dashboard.md`，按 1280px PC 基线检查完整/缺失/部分失败页面并保存合成数据截图。

## 7. 跨端集成验收

- [x] 7.1 使用隔离服务与受控测试 APK，完成主线程和命名后台线程崩溃、同名线程/空帧、日志/内存/FD 采集、HTTP accepted/duplicate、ClickHouse 详情和网页对齐；使用 Release APK 同次 mapping 验证主/辅还原和固定来源，并记录各分区实际可用范围。
- [x] 7.2 完成断网崩溃后新进程补传、capturing 中断、日志超时/权限失败、受控 OOM、超限裁剪和接收部分写入恢复的联合实验；验证委托原处理器、原身份保留、无重复计数、无新现场冒充、无哨兵秘密泄漏及主异常始终可查看。
- [x] 7.3 在可用的最低支持 API、API 29 与 API 35/36 设备上记录有限线程规模实验的处理器等待、采集耗时和内存影响；示例应用最低 API 23 与 Crash 库最低 API 21 分开说明，缺少 API 21 消费者验证时明确记为缺口，不能外推真机或生产容量。
- [x] 7.4 将独立跨端验收证据收敛到 `docs/validation/crash-diagnostics/README.md` 并登记验证入口，更新根状态页与各工程测试页的已执行范围；检查文档本地链接、`git diff --check` 和 `openspec validate add-crash-diagnostics --strict`，最终分别列出根、后端、前端、Android 知识库及 API/客户端同步文件，明确未执行环境和 AI 证据未扩展。
