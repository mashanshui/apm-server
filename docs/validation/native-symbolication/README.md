# Native Crash 符号还原隔离验证

本页记录工作树中的有限验证，不代表现有本地或云端环境已部署，也不代表生产容量或真实设备闭环已验收。

## 真实工具和查询链路

在隔离 PostgreSQL、ClickHouse、符号目录和真实 HTTP 服务中，使用 Android NDK 生成的 arm64 ELF、按 ELF Build ID 登记的 SO 和 Linux LLVM 19，将合成 Native 事件的相对地址还原为 `apm_fixture_add` 与 `minimal.c:2`。还原前后的事件指纹保持一致；删除成功帧使用的 SO 后，网页详情仍保留该帧和指纹，Agent HTTP 返回同一主栈。`VersionedSymbolsHttpTests` 覆盖该流程；夹具生成和执行条件见[后端测试](../../../backend/docs/knowledge-base/06-测试与质量保障.md#elf-测试夹具)。

使用同一隔离服务在 1280 × 960 浏览器检查事件详情、符号上传入口和 SO 版本进度：主帧显示函数、源码行及实际来源，上传入口预填 ABI 与完整 ELF Build ID，进度区注明发布次数不是完成事件数。另用 Edge 保存[详情页截图](native-detail.jpeg)，截图使用合成事件与真实 ELF，不代表生产数据或跨浏览器验收。

隔离双库测试覆盖多轮快照、写入后确认丢失及同字节重放；真实 HTTP 测试进一步实际替换同一 SO，并在后端 Spring 上下文重启后确认原字节、原来源与幂等计数。该测试不模拟操作系统进程故障。十万条混合候选的查询计划和有限规模测量见[后端性能基线](../../../backend/docs/knowledge-base/crash-query-performance-baseline.md)。

## AGP 原始符号包与合成事件

2026-11-14 使用当前示例应用实际生成的 `native-debug-symbols.zip`，在隔离 PostgreSQL、ClickHouse、临时符号目录和真实 Servlet 中直接上传整包；包内 14 个 `.so.sym` 覆盖四 ABI，均通过实际 ELF 身份及符号材料校验。Linux LLVM 19 分别从四 ABI 的 `libapm_crash_demo.so.sym` 实际函数地址还原 `apm_demo_segv`。文件数量和包含的库仅适用于该构建，不是其他产物的固定要求。

使用 arm64 原始 ZIP 的 Build ID 和函数地址构造合成 Native 事件。后台从原包还原函数名，实际材料没有源码行号；随后通过 HTTP 确认替换为同 Build ID、包含调试行信息的未剥离 SO，已成功帧、原始来源、指纹及总览统计保持不变。隔离浏览器检查详情中的函数、行号未知和来源入口，以及预填目标模块的 SO 上传弹窗；页面展示均为合成事件，未使用真实设备现场。测试由 `VersionedSymbolsHttpTests.resolvesSyntheticEventFromActualAgpZip` 执行，显式文件与工具条件见[夹具说明](../../../backend/src/test/fixtures/elf/README.md)。

隔离 PostgreSQL 和真实 HTTP 另覆盖四 ABI 批次、目标核对、混合后缀去重、不同摘要拒绝、冲突快照确认、应用与版本隔离；MockMvc 及真实 PostgreSQL 注入提交确认丢失后验证同包重传对账。以上结果不证明任意 AGP 版本或第三方库均包含可用调试信息，不代表生产或真实设备验收。

2026-11-14 另以四 ABI 的 NDK 合成 ELF 建立隔离最小 Android 工程，使用 AGP 8.13.2 的 `debugSymbolLevel = "FULL"` 实际生成原始 ZIP；包内四个 `.so.dbg` 均与同一夹具的 `llvm-objcopy --only-keep-debug` 输出逐字节一致。真实 HTTP 和隔离双库直接接收此原包，Linux LLVM 19 将合成事件还原为 `apm_fixture_add` 与 `minimal.c` 源码行；随后替换为同 Build ID 的仅函数符号，已成功帧、来源、指纹与统计保持不变。隔离浏览器核对上传弹窗的 `.so.dbg` 提示和合成事件的 `minimal.c:2` 显示。另核对 `.so.dbg` 多 ABI 幂等、冲突确认及目录标记与实际 ELF 架构不一致时的目标校验。测试使用合成源码和临时工程，并非某个真实应用或设备的 FULL 产物验收。

## 多模块与故障推进

`VersionedSymbolsHttpTests` 在隔离 PostgreSQL、ClickHouse、文件卷和真实 Servlet 中覆盖两种到达顺序：先上传主模块 SO、后上传 mapping 和辅助模块 SO；以及先上传 mapping、后上传两个 SO。主关联 Java 栈、Java 辅助线程和另一 Native 模块线程各自推进；每个阶段核对网页、Agent HTTP 和真实 MCP HTTP 的主栈、诊断及指纹，完成后验证原事件重复上报。同一事件的 SO 实际替换、SO 与 mapping 删除后，三个入口仍读取固定成功来源；SO 先到场景多轮前后总览统计不变。MCP 按摘要续读并重建两个分片，测试数据与文件均为隔离合成材料。

另一场景在同一事件中，以真实睡眠子进程触发 Native 工具超时，Java 栈仍独立成功；恢复工具后等待实际退避到期，在状态写入后注入确认丢失，实际替换 SO 并使租约过期。Spring 后端上下文重启后重领租约，先按原字节确认，重复确认不增计数，随后 Native 主栈和辅助线程继续成功；Java 成功来源、Native 已确认来源及指纹保持不变。显式设置 `APM_NATIVE_PROCESS_RESTART=true` 并先构建 Boot JAR 时，还启动独立后端 JVM，在未确认发布存在时强制终止并启动新 JVM；新进程恢复后读回的确定编码与原登记载荷逐字节一致，后续线程处理完成。独立进程使用隔离双库与符号卷。

## 自动化回归

2026-11-13 后端 `clean build` 在真实 NDK 四 ABI 夹具、隔离 LLVM 镜像、PostgreSQL 和 ClickHouse 条件下执行 471 项，468 项通过、0 项失败、3 项条件跳过；显式执行了 Native 和既有 Crash 查询性能专项、真实 ELF 及 60 秒慢上传测试。三项跳过分别为未提供设备样本、未启用 mapping 性能专项和未提供外部 Release mapping。前端类型检查与构建通过，50 个测试文件共 189 项通过；MCP 类型检查与 24 项测试通过，包含真实 MCP HTTP 入口对旧摘要、应用隔离和资源限制错误的回归；该专项后端采用受控响应。

2026-11-14 后端 `test` 使用上述四 ABI 夹具、实际 AGP `SYMBOL_TABLE` 与隔离工程 `FULL` 原包、LLVM 镜像和隔离双库执行 480 项，474 项通过、0 项失败、6 项按条件跳过：设备现场、外部 Release mapping、三项性能专项及慢上传专项。前端 50 个测试文件共 190 项通过，类型检查与生产构建通过。本轮没有重跑 MCP 测试、性能专项或设备验收。

这些数字是执行时的自动化结果，不扩大为设备或生产环境验收。

## Pixel 6 模拟器联合验证

2026-11-13 在用户指定的 Pixel_6、Android API 35、arm64-v8a 模拟器上，使用从当前 Android 示例应用构建的独立 Debug 测试包。该包指向隔离 PostgreSQL、ClickHouse、符号目录和真实 HTTP 服务；崩溃、应用重启恢复及上报均经过实际 SDK。测试所用 SO 是同一构建的未剥离 `libapm_crash_demo.so`，其完整 ELF Build ID 与 APK 中对应库及事件模块三方一致。

先触发主线程 JNI SIGSEGV 并等待事件进入服务，随后上传 SO。后台自动处理将相对地址 `0x94c` 还原为 `apm_demo_segv`、`native_crash_demo.c:9`，将 `0x92c` 还原为 JNI 入口、`native_crash_demo.c:25`。22 个主栈物理帧中 2 个成功、19 个等待、1 个不适用；未上传对应系统库符号，故事件整体仍为 pending。成功帧保留实际 SO 身份和摘要，删除该 SO 后两帧和事件指纹仍不变。

Agent HTTP 返回的主栈与网页详情接口一致；通过 MCP 官方客户端按内容摘要续读 `stack` 与 `diagnostics`，重建结果均与 Agent HTTP 相同。测试环境为避免影响既有用例默认延迟一小时启动调度，本次通过临时 Gradle 初始化脚本设置 30 秒初始延迟，未修改生产默认配置。SDK 构建、设备事件及符号由同次构建产生；测试凭据、设备现场和原始堆栈不纳入仓库。

本次为 Debug 构建，没有对应混淆 mapping，未验证设备事件的 Java mapping 还原；也未验证其他 ABI、物理设备、真实生产环境或所有系统库。模拟器仅覆盖一个应用模块，不能据此声称完整栈都已还原。

## 其他待验证边界

SO 后到已由上述模拟器事件覆盖；生产环境故障和恢复演练仍需继续。生产部署、备份恢复和其他 CPU 架构运行镜像尚未验证。
