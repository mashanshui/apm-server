# Android 合仓实施任务

本清单的 27 项实施任务均已完成，具体结果与边界见[核验报告](verification.md)。执行依据为 [提案](proposal.md)与[设计](design.md)；本变更设置 `skip_specs: true`，没有规格增量待生成。

按组顺序执行并逐项保留实际验证结果。subtree 的必要本地导入合并提交已在设计中说明；普通整理提交、推送、合并主分支、部署及归档按后续用户指令执行。原 Performance 检出、私人配置、现有服务和设备数据在本次实施中保持原位。

## 1. 固定迁移来源与既有基线

- [x] 1.1 重新核对双方本地 HEAD、分支/标签、浅克隆状态、工作区和暂存区；列明与规划基线的差异及本地远端跟踪引用的滞后情况，冻结待导入 Android 提交集合，确认不会遗漏本地未推送提交；用 `git rev-parse`、`git rev-list`、`git for-each-ref` 和 `git status` 的实际结果核验，遇到新改动先保护，不自动 stash、提交或清理。
- [x] 1.2 记录实施前两仓正式规格与归档任务校验结果，清点客户端三份正式规格、两个归档及同名冲突；确认既有三项服务端失败的名称/完成数与设计基线一致或记录新差异，新增问题先定位，不补勾历史任务。
- [x] 1.3 对原 Android 已跟踪文件建立路径/模式/对象摘要清单，并记录本地 SDK 配置、签名资产和原索引的保留边界；验证签名文件仍在原位、没有读取或输出秘密值，清单和验证日志仅存放未跟踪的本地位置，后续能逐项对比来源未被修改。

## 2. 导入原始 Git 历史与独立 Android 工程

- [x] 2.1 在当前适用检出的 `codex/consolidate-android-into-monorepo` 本地分支使用非 squash subtree 导入冻结本地提交到 `android/`；检查暂存区无无关内容和导入目标未被占用，确认原主分支指向、原 Android HEAD/索引未变，导入产生的提交确为预期本地合并提交。
- [x] 2.2 逐条检查冻结的 Android 提交是导入结果祖先，核对原 commit ID、提交元数据与 `android/` 初始树的对象/模式映射；实际执行祖先检查和树对比，保留全量核对结果而不只报告提交数量。
- [x] 2.3 保留 Android settings、八个 Library、独立消费者及两套 Wrapper，按需调整忽略/换行规则；从根执行 Android Wrapper 的项目/任务清单检查，确认能发现原模块且未生成根聚合工程；同组同步根 `AGENTS.md`、`docs/README.md`、根知识库 README/02，以及 `android/AGENTS.md` 和 Android 知识库 README/01 的入口与路径，检查新增链接实际存在。

## 3. 外置测试签名配置并保留证书身份

- [x] 3.1 用 Gradle Providers 在 Android App 读取设计指定的四个 `performance.signing.*` 本地属性，移除字面量配置和最终跟踪树中的 `android/app/sign`；保留原文件及必要的同字节忽略副本，用文件/证书摘要核对保留关系，检查 Git 差异不含新凭据且原检出未被修改。
- [x] 3.2 让 `android/verification/modular-consumer` 的三个消费者使用相同签名属性和明确绝对路径；核对无硬编码密码或旧 `../../app/sign` 依赖，以原证书配置分别完成 App Release 和消费者签名任务，比较最终证书一致性，不重新安装设备应用。
- [x] 3.3 验证缺少 Release 配置时 App Debug 可构建，缺少或部分配置时显式签名任务以脱敏错误失败，完整配置时使用原证书；测试过程中不输出配置值、不替换证书、不清除设备数据，必要失败用例只针对本机临时配置执行。
- [x] 3.4 同步 Android 知识库 11/16、独立消费者 README 与安全提示，提供占位属性和本地准备步骤；说明完整 Git 历史仍含既有测试签名资产，核对文档没有真实密码、证书私钥或“历史已清理”的错误声明。

## 4. 迁移正式规格与历史归档

- [x] 4.1 将客户端 `modular-sdk-composition`、`modular-sdk-distribution`、`memory-runtime-peaks` 移入根正式规格目录；核对三份内容摘要与来源一致、原服务端 20 份未被改写，当前正式规格共 23 份。
- [x] 4.2 将客户端模块化归档完整移入根归档目录，并将客户端内存峰值归档命名为 `2026-11-08-report-memory-runtime-peaks-android`；核对元数据、增量、任务勾选、验证报告和证据均完整，两份内存峰值记录均可单独读取，历史归档总数为 21。
- [x] 4.3 完成路径映射后修复归档可导航链接，补充必要来源说明，移除当前树的内层 `android/openspec/`；更新根 config 的工程边界与工作目录，并同步两端知识库、客户端文档和验收资料中的正式规格/归档入口；检查历史日期、构建哈希、任务状态及证据范围未被改写。
- [x] 4.4 从根和 Android 子目录执行 `openspec list --json` 并确认均解析到平台根；运行正式规格严格校验 23/23、归档任务校验并确认仅原三项失败（18/21），核对本迁移变更仍为活动变更且 `skip_specs` 生效，保留实测结果。

## 5. 修复共同样例与联合工具的目录解析

- [x] 5.1 在 Android Gradle 测试配置中从工程位置计算根 `docs/fixtures/memory-runtime-peaks.json` 并注入测试进程，调整 `MemoryRuntimePeaksTest.sharedFixtureRoundTripHasOnlyPeakPayload`；从根与进入 `android/` 后各运行该契约测试，确认全部原断言生效，样例缺失明确失败且未复制共同样例。
- [x] 5.2 将 `scripts/verify-memory-runtime-peaks.py` 默认客户端设为根下 `android/`，保留显式 `PERFORMANCE_PROJECT_DIR` 覆盖；用无设备/数据库副作用的路径解析检查覆盖默认值和自定义目录，验证 Python 语法，不执行完整联合脚本；同组更新脚本注释与 `docs/validation/memory-runtime-peaks/README.md` 的复验入口。
- [x] 5.3 扫描并修复根/Android 当前知识库、客户端接入、数据集与验收资料中的旧跨仓库可执行引用，重点覆盖 Android 09/10/11/12/13/16 和根 `docs/client-integration/README.md`、`memory-metrics.md`；逐条核对链接/锚点和实际文件，保留历史记录的原日期、版本、APK 与设备边界，并明确当前 SDK 已位于仓库内。

## 6. 统一技能并验证合仓分析路径

- [x] 6.1 移除导入 Android 树中的旧 OpenSpec 技能、旧 Crash 分析副本、ZIP 和目标标记，保留根现有技能与有效相对软链接；对照源文件清单核验移除范围，确认当前技能仅使用证据版本 3、报告版本 4 和宿主直接读取流程，私人配置/状态未被删除或纳入 Git。
- [x] 6.2 在现有 Worker 测试中增加真实 Git 根下嵌套 Android 目录回归，覆盖子目录根定位、带 `android/` 前缀引用匹配/不匹配、未提交内容与索引保留；使用合成证据/假平台验证当前报告并运行 Worker 完整 pytest，确认没有调用真实平台任务或改写实际项目文件。
- [x] 6.3 同步根和 Android 的开发规则、Android 调试/测试知识库、后端 `local-analysis.md` 及相关测试页，明确 Git 根相对引用与 Android 构建工作目录；检查根技能路径可达、文档命令与现有入口一致，并说明前端分析页面和 HTTP 契约未改变。

## 7. 执行跨工程构建与交付回归

- [x] 7.1 按用户本次明确授权，将 MemoryEventStore.peekBatch 的 Comparator.reversed() 替换为交换参数的 API 21 兼容比较器，保持队列排序；在迁移后的 Android 工程运行全模块 `testDebugUnitTest`、`checkPerformanceModuleDependencies` 和根 `check`，覆盖现有批次选择/退避/排序测试；核对依赖实际解析来源及 Rhea/KOOM 当前版本，取得终端成功结果和测试报告；缺依赖或环境时记录具体阻塞，不能沿用原检出的历史通过结果。
- [x] 7.2 从迁移入口构建 App Debug、Release 和现有 Release 仪器 APK，检查 applicationId、buildId、build-info sidecar 与同次 APK/mapping 的对应关系及原签名证书；只记录构建/产物验证，不声称已运行设备用例或重新完成频率实验。
- [x] 7.3 使用独立验证版本将八个 Library 发布到 Maven Local，逐项检查 AAR、sources JAR、POM 和 module metadata，再在隔离消费者项目缓存中构建 Crash-only/Metrics-only/Full 的 Debug/Release；核对模块依赖、混淆、精简产物和签名证书，避免覆盖既有同版本本地制品，实际结果同步 Android 构建测试页。
- [x] 7.4 运行 `bash backend/gradlew -p backend test`、前端与 MCP 的 test/typecheck，以及前述 Worker 回归；仅当迁移实际修改后端构建配置时追加后端完整 clean build，记录成功/失败/条件跳过，按各工程原位测试页同步实际边界，不启动、重启或部署已有服务。

## 8. 完成迁移整体验收与交付

- [x] 8.1 对最终工作树逐项复核源文件映射、允许的目录/技能/签名差异、完整历史祖先集合及原检出 HEAD/索引/用户文件保留情况；将结果与导入前清单对比，确保没有遗漏源码、夹具、原生资源、归档或有效证据。
- [x] 8.2 复验单一 OpenSpec 根、23 份正式规格、21 个历史归档及原三项失败集合，检查本变更自身严格校验、全部受影响 Markdown 本地链接/锚点与旧可执行路径；区分既有失败和新增问题，保持本迁移变更未归档。
- [x] 8.3 核验 Docker 上下文及部署白名单仍排除 Android/缓存/本地配置，执行 `git diff --check` 并审查差异范围；交付原位更新后的根/后端/前端知识库、API、客户端及 Android 内部文档清单，不受影响类别明确说明，分别报告实际构建、合成工具测试和未执行的设备/部署验证；保留原检出与迁移分支，不自动提交普通整理差异、推送或合并主分支。
