# Android 合仓设计

## Context

动机与范围见 [提案](proposal.md)。以下是 2026-11-08 对当前本地检出的检查结果，不表示已实施迁移或刷新远端。

| 项目 | 当前事实 | 设计约束 |
|---|---|---|
| Git | 服务端 HEAD `b6698b2035101ed9f0a037489fc168ee6ae649c6`，19 条可达提交；客户端 HEAD `b6c4de327fd39c12b8e549e8d60b383512a2222a`，31 条；均非浅克隆、工作区干净；只有本地 master、无标签 | 导入当前本地提交，实施前重新清点分支、标签、工作区；已有远端跟踪引用各落后本地 4 条，不能作为完整来源 |
| OpenSpec | 服务端正式规格 20 份、历史归档 19 个；客户端正式规格 3 份、历史归档 2 个；均无活动变更 | 本提案成为新活动变更；既有正式规格和归档在迁移时保持语义 |
| 归档冲突 | 两边均有 `2026-11-08-report-memory-runtime-peaks`，核心文件内容不同 | 两份记录分别保留；不能目录覆盖或重新合并历史任务 |
| 构建 | 后端 Gradle 9.5.1；Android Gradle 8.13、AGP 8.13.2、Kotlin 1.9.25；Android Wrapper 当前文件模式为 100644 | 保留独立 Wrapper，macOS 命令使用 bash 和明确工程目录；迁移不升级工具链 |
| 共同测试 | `MemoryRuntimePeaksTest.sharedFixtureRoundTripHasOnlyPeakPayload` 通过相邻 `IdeaProjects/apm-server` 查找 fixture；联合脚本默认寻找相邻 `AndroidStudioProjects/Performance` | 路径从工程/脚本位置确定，解除旧检出布局依赖 |
| 技能 | 根 OpenSpec 技能生成版本为 1.13.2，客户端为 1.11.0；客户端 Crash 技能保留旧证据/报告和 Python 补丁流程 | 使用根当前技能；现有证据版本 3、报告版本 4 和宿主直接读取契约继续生效 |
| 签名 | `app/sign` 已跟踪，App 与三个独立消费者共用此测试签名，配置存在字面量 | 保持证书身份；最终工作树从未跟踪配置读取，不在迁移中更换密钥或清除应用数据 |
| 部署 | `.dockerignore` 与 `scripts/deploy-cloud.sh` 按后端产物、前端产物、MCP 和部署材料列白名单 | Android 不进入服务端镜像上下文或部署包，保留当前运行环境 |

正式规格严格校验为服务端 20/20、客户端 3/3。归档任务校验为服务端 16/19、客户端 2/2；服务端既有失败是 `2026-08-26-add-auth-project-workspace`（42/43）、`2026-08-27-add-jank-monitoring-server`（47/48）、`2026-09-13-add-memory-leak-reports`（21/22）。这些是检查基线，不属于本次需要补做的业务任务。

## Goals / Non-Goals

**目标：**

- 一份 Git 仓库保存平台代码和 Android 原始提交；历史对象可按原 ID 访问，当前目录按工程职责组织。
- 一套根 OpenSpec 与技能入口；Android 仍可独立打开、构建、测试和以 Maven 坐标交付。
- 当前代码、测试、共同样例和文档使用合仓后的真实位置；有明确的文件映射、验证结果和本地回退边界。

**范围之外：**

- Rhea、KOOM 源仓库迁移、依赖升级、远程 Maven 发布、CI 平台建设与统一 Gradle 大工程。
- API、事件字段、SDK 公共接口、应用包名、Maven 坐标、数据库迁移或生产容量改造。
- 推送、合并到主分支、云端部署、重启已有服务、原 Android 检出删除、历史敏感对象清理以及完整设备实验重跑。

## Decisions

### 1. 在当前适用检出中完整导入本地历史

实施在 `codex/consolidate-android-into-monorepo` 本地分支进行，原主分支指向和原 Android 检出保留。优先复用当前检出；若工作区发生新改动，先核对保留边界，不自动 stash、清理或提交用户修改。暂存区不得混有无关内容；subtree 要求的已跟踪文件干净条件不满足时，不绕过检查。

冻结实施时双方实际 HEAD、Android 可达提交集合、已跟踪文件路径/模式/对象摘要，以及未跟踪本地配置的存在性。完整导入使用 `git subtree add --prefix=android <本地来源> <冻结提交>` 的非 squash 模式；必要时先 fetch 本地来源到专用引用，再按该提交导入。实施确认源码相对规划基线未变化后，可采用规划所列 HEAD；有新增提交则在导入前更新清单与差异结论，不能静默导入旧快照。

subtree 自身会产生必要的本地合并提交，这属于本设计的导入步骤。普通整理提交、合并主分支和推送均另按用户指令执行；实施交付可以包含可审阅的未提交整理差异。既有规划文件须保持可用且不被导入动作遗漏或覆盖。

导入后先核对原 31 条提交（或实施时更新后的完整集合）均为导入结果的祖先，再核对 `android/` 初始内容与原 HEAD 的树映射完全一致。目录、技能与签名调整采用明确文件清单，作为导入后的差异核对，禁止以删除原始历史换取新目录连续性。

取舍：普通复制无法接入原历史；submodule 继续保留两个仓库管理边界；filter-repo 会改写历史树和 commit ID，因此选用非 squash subtree。保留的旧提交仍使用原根路径，按新 `android/` 路径筛选日志不保证连续；用原 ID 与提交图追溯历史。导入完成后无需为日常跨端开发执行 subtree pull/push，也不建立双向维护机制。

### 2. 保留 Android 独立工程，统一外层管理

目标结构为根 `backend/`、`frontend/`、`mcp-server/`、`android/` 并列，根 `openspec/`、`docs/`、`scripts/`、`agent-skills/` 与 `.agents/skills/` 管理平台内容。Android 子目录保留 `app/`、八个 `performance-*` 模块、`verification/`、Gradle 配置及内部知识库。Android Studio 打开 `android/`，根目录不新增聚合 Gradle settings。

根启动/部署脚本继续负责原有服务。Android 命令显式使用 `bash android/gradlew -p android ...`，独立消费者使用 `bash android/gradlew -p android/verification/modular-consumer ...`；各工程按已有文档选择兼容 JDK、SDK、NDK 和 CMake。保留现有测试构建类型、buildId 及 build-info sidecar 导出行为。只按迁移需要调整 `.gitignore` 和 `.gitattributes`，覆盖 Android 本地配置、缓存、签名和 Wrapper 的平台换行边界。

Android 的 Rhea `1.0.4`、KOOM `2.2.4` 与后端 processor `1.0.4` 按现有坐标解析。Maven Local 配置不能当作新环境可获取证明；实施核对实际解析来源并记录准备步骤，缺失时明确阻塞对应构建，不自动引入这些项目源码或发布制品。独立消费者继续通过本轮本地发布的 AAR/POM/module metadata 验证交付。

### 3. 当前工作树只保留根 OpenSpec

- 将客户端三份正式规格按原能力 ID 搬入根 `openspec/specs/`，逐文件核对内容摘要；服务端 20 份规格内容保持原样，正式规格共 23 份。
- 将模块化归档搬入根同名目录；内存峰值客户端归档改名为 `2026-11-08-report-memory-runtime-peaks-android`。服务端同名归档保留原名；两端 proposal/design/tasks/verification、增量和元数据均完整保留，任务勾选状态不变。
- 仅修复归档中的可导航路径、补充来源和路径映射说明；保留原版本、日期、构建哈希、设备、场景和验收边界。正文中历史执行命令的原目录可标为历史位置，当前复验入口指向迁移后的文档；不把历史代码段批量伪装成新位置执行记录。
- 旧内层 `android/openspec/` 完成搬迁和链接核验后移除；根 `openspec/config.yaml` 补充工程边界和必要工作目录，不复制完整知识库。分别从根与 `android/` 执行 `openspec list --json`，都应解析到平台根。

历史归档共 21 个，本迁移变更归档后另增加一个。正式规格严格校验预期 23/23；归档任务校验在本变更归档前预期 18/21，失败集合只包含上述三个旧问题。不得为满足全绿而补勾旧任务或重新执行历史同步。

本次不改变规格要求，规划明确采用 `skip_specs: true`。现有规格搬迁不属于新增业务能力，不创建用于描述目录整理的空泛能力规格。

### 4. 用工程位置解析共享样例与客户端目录

共同样例继续唯一保存在根 `docs/fixtures/memory-runtime-peaks.json`。Android Gradle 测试配置从 Android `rootProject` 的父目录计算样例绝对位置并注入测试进程；客户端测试读取该明确位置。保留原完整 JSON、整数/null/UUID 和时间一致性断言，缺少样例明确失败，不复制一份客户端样例或跳过断言。需要覆盖从根执行与进入 Android 后执行两种入口，不依赖测试进程偶然的当前目录。

`scripts/verify-memory-runtime-peaks.py` 默认客户端改为 `ROOT / 'android'`，保留显式 `PERFORMANCE_PROJECT_DIR` 覆盖能力作为工具可配置入口；去除对旧同级检出布局的隐式猜测。配置仍通过标准输入和已有 Gradle 环境属性传递，不写凭据到命令参数或公开日志。默认目录解析用无副作用测试或函数级检查验证，不因迁移冒烟自动运行会操作设备、数据库和网络的完整联合脚本。

### 5. 根技能为唯一当前版本，分析工具保持 Git 根语义

删除导入后的 Android OpenSpec 技能、旧 Crash 分析技能副本、旧导出 ZIP 和目标标记，根现有技能入口继续生效。Crash 技能源码仍由根 `agent-skills/apm-crash-analyze/` 维护；不将旧 Python search/read/apply/revert 流程带回当前契约。根已有相对软链接必须保持有效，移除重复副本前按跟踪清单核对，私人配置和状态保持原位。

合仓后从 `android/` 调用 `project_root` 返回平台 Git 根，符合现有正式契约。宿主对 Android 故障的搜索、修复和测试范围限定在用户选择的 Android 工程；引用使用根相对路径 `android/performance-.../...`，测试命令显式选择 Android 工作目录。无需增加第二层项目映射或改变 `--project` 协议。

在现有 Worker 的 `test_source.py`、`test_analysis.py` 或 `test_host.py` 中补充真实 Git 根下嵌套 Android 目录的回归：从子目录定位到根、带前缀引用匹配/不匹配、未提交文件保留与索引不变。使用合成证据与假平台验证报告版本 4，不能为冒烟领取真实任务、提交报告或触发修复。只有现有实现不满足这些契约时才作最小修复。

### 6. 外置当前签名配置，保留原证书与历史

先保留现有测试签名文件和证书身份的本地核验信息，再让 App 与独立消费者通过 Gradle Providers 读取同一组本地属性：`performance.signing.storeFile`、`performance.signing.storePassword`、`performance.signing.keyAlias`、`performance.signing.keyPassword`。签名文件路径使用明确绝对路径，参数放在用户私有/未跟踪配置或成组环境属性中；文档示例仅用占位值。

默认 Debug 构建继续使用标准 Debug 签名，不要求 Release 私有配置。显式 Release/消费者签名任务缺少或只有部分配置时给出不含值的错误，不静默换证书；有完整配置时仍使用原测试证书。最终跟踪树移除 `android/app/sign` 和签名字面量，本地证书原文件不删除，必要的同字节本地副本仅进入忽略目录。核对签名证书一致性即可，不重新安装或清空设备应用来规避签名不匹配。

取舍：保留原 commit ID 意味着历史提交及导入快照仍含既有测试签名文件和旧参数；当前树外置配置不能清理历史。此设计将它记录为已有公开测试资产，不把它当作正式发布密钥；不扩展为历史扫描、重写或密钥轮换。实施发现用途与测试资产标注冲突时，先停止签名处理并核实用途。

### 7. 文档按职责原位同步

| 归属 | 实施时检查和同步的文件/主题 |
|---|---|
| 根知识库 | `docs/knowledge-base/README.md`、`00-当前实现与验证边界.md`、`02-总体架构与模块.md`、`08-测试与质量保障.md`；必要时在现有 `10-架构决策记录.md` 记录保留历史的取舍；`docs/README.md` 和根 `AGENTS.md` 补 Android 入口 |
| Android 内部知识库 | `android/AGENTS.md`、`android/docs/knowledge-base/README.md` 及 00/01/09/10/11/12/13/16 等含工程、构建、协议或旧链接的页面；保留内部机制正文及已有证据 |
| 后端知识库 | `backend/docs/knowledge-base/local-analysis.md`、测试页及需要说明的新工具调用位置；后端构建、服务与存储契约不变 |
| 前端知识库 | 检查指向客户端/验收/分析工具的引用；有影响才修正文档链接，不修改页面或复制 Android 构建说明 |
| API 文档 | 检查目录及契约文档链接；字段、错误码、统计和路由保持原样，有引用变化仅修链接 |
| 客户端接入 | `docs/client-integration/README.md`、`memory-metrics.md` 及含外部 Android 引用的主题，改为仓库内源码/内部知识库入口；删除“客户端尚未在本仓库实现”等迁移后失效的目录描述，继续区分实现和设备验收范围 |
| 验收资料 | `docs/validation/memory-runtime-peaks/README.md` 与分析验收资料的源码、归档和复验入口引用；旧 APK/证据哈希、时间和环境保持原事实 |

日常迁移检查结果留在任务回复；源码清单、历史提交集合和临时测试日志保存为未跟踪的本地验证材料。保留必要的架构决定及可复用步骤，不为本次操作新增知识库流水页。

## Risks / Trade-offs

实施中完整 check 发现原 `MemoryEventStore.peekBatch` 的 `Comparator.reversed()` 触发 NewApi 错误（要求 API 24，模块最低 21）。已核对失败文件与迁移前提交完全一致。用户明确授权最小修复：通过 `order.compare(second, first)` 形成同语义的最大堆比较器，保留现有批次选择、退避和排序测试，重新执行完整 check；不升级 minSdk、不启用全局 desugaring、不放宽 lint。本项是恢复既有最低版本契约，不新增规格行为。

- 本地来源比记录的远端更新 → 冻结本地 HEAD 与提交集合，导入后逐条核对；远端状态未刷新就不声明已同步。
- 原历史路径与新目录不连续 → 保留原对象，说明原 ID/提交图查询方式；新目录 path log 不作为唯一历史完整性证据。
- 同名归档及旧引用可能被覆盖 → 明确客户端后缀、保存逐文件映射，检查正文、锚点与来源而非只比较目录数量。
- 本机缓存掩盖依赖缺失 → 记录解析来源，在隔离项目缓存中重验；Maven Local 可继续使用但明确它的准备条件，不声称无需外部依赖。
- 签名配置外置影响 Release 或消费者 → 用同一证书比对、缺配置失败与 Debug 无私有配置测试覆盖，保持原设备数据。
- 旧技能恢复旧协议或扩大工作范围 → 清除嵌套副本，使用根现有协议与嵌套目录回归，宿主明确 Android 修改范围。
- 历史归档本来有未完成项 → 对比失败集合及任务数；不将既有失败归因于迁移，也不将归档存在等同验收全部通过。
- 迁移中用户新增修改 → 每一阶段核对原检出与目标状态，只撤销本任务确切改动，禁止 reset/clean 或覆盖本地配置。

## Migration Plan

1. 复核双方 HEAD、工作区、暂存区、分支/标签、文件映射与签名资产边界；记录正式规格和旧归档失败基线。
2. 在本地迁移分支完整导入冻结的 Android 提交；验证祖先集合与初始树映射。源检出只读保留。
3. 外置签名配置，搬迁正式规格与归档，统一根技能和协作入口，修复 fixture、脚本默认值及文档引用。
4. 按下表运行迁移验证，失败按实际原因处理；环境缺失的必需项保持未完成，不以历史结果代替。普通整理差异交付用户审阅，不自动推送或合并主分支。
5. 用户后续请求核验/归档时再完成本变更归档；本次规划和未来实现阶段均不自动归档。

| 验收项 | 实施阶段的通过条件 |
|---|---|
| 历史与文件 | 冻结 Android 提交集合逐条为合仓结果祖先；导入原树匹配，整理差异均在清单内；原检出 HEAD、索引和用户文件未被修改 |
| OpenSpec | 根与 Android 解析同一根；正式规格 23/23；历史归档 21 个且旧失败集合不变；两份内存峰值任务/证据独立完整 |
| Android JVM/构建 | 从根和 Android 目录执行共同契约测试；运行全模块 `testDebugUnitTest`、`checkPerformanceModuleDependencies`、`check`、App Debug/Release 和现有 Release 仪器包构建，均取得终端成功结果；产物包名、SDK 坐标与 build-info 关联保持正确 |
| 独立消费者 | 用本轮验证版本本地发布八个 Library，隔离消费者项目缓存构建 Crash-only/Metrics-only/Full 的 Debug/Release，检查依赖、混淆与签名证书；结果只证明本机依赖准备下的交付 |
| 签名配置 | 无 Release 配置时 Debug 可构建；部分配置的签名任务安全失败；完整配置的 App Release 和消费者使用原证书；最终跟踪树无签名文件和新凭据 |
| Worker | 嵌套 Android 目录根定位、带前缀引用与当前报告的合成回归通过，Worker 完整 pytest 通过；没有调用真实平台任务 |
| 服务端回归 | `bash backend/gradlew -p backend test`、前端与 MCP 的 test/typecheck；后端如因迁移修改构建配置再执行完整 clean build；无需启动或部署已有服务 |
| 文档/部署 | Markdown 本地链接与相关锚点、旧可执行路径扫描和 `git diff --check` 通过；Docker 上下文/部署白名单不包含 Android、缓存与本地配置；分别报告各类文档同步情况 |

回退以保留的原主分支、原 Android 检出和确切差异清单为边界。迁移分支上的失败导入或整理不影响原仓库；保留该分支供排查，恢复之前先保存迁移中新产生的用户修改。已发布历史若需撤销，须设计新的撤销提交，不 force push、不改写原提交。原设备、数据库、数据卷和运行服务不属于本次回退对象。
