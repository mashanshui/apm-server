# Android 合仓核验报告

核验日期：2026-11-08。变更：`consolidate-android-into-monorepo`；流程：`spec-driven`；核验实现提交：`bfaa7b9`。

## 结论

适用检查全部完成，CRITICAL 0、WARNING 0、SUGGESTION 0，可归档。本次重新核对当前源码、Git 历史/文件保留边界、产物摘要与文档导航；自动化和完整构建结果复用同一任务已有证据，没有重新执行整套构建、设备实验或部署。

| 维度 | 结果 |
|---|---|
| 完整性 | CLI 跟踪 27/27 项任务完成，规划材料齐备；规格覆盖不适用，因为明确设置 `skip_specs: true` 且没有规格增量 |
| 正确性 | 新增/修改规格的实现映射与场景覆盖不适用；现有正式规格仅迁移，未声明新业务行为；任务对应的源码、测试、产物与失败处理证据已核对 |
| 一致性 | 7 项设计决定均有实现及验证依据；新增代码沿用 Gradle Providers、既有测试框架和中文注释，无额外构建聚合、旧协议或历史重写分支 |

不适用的规格检查不计为“未核验”；其余适用检查无未核验项。任务页遗留“全部未完成”的导语已原位校正，27 项勾选状态未变。

## 设计与实施证据

以下代码路径均相对平台仓库根；行号为本次核对位置。

| 设计决定 | 当前实现与核验依据 |
|---|---|
| 完整保留原历史 | `44a43fe` 的两个父提交分别接入原服务端与客户端历史，`HEAD:android` 在初始导入时与客户端原树一致。重新逐条验证原 31 个 Android 提交均为当前提交祖先，原 ID 不变；原检出的 310 个已跟踪文件摘要、索引及本地配置保持原样 |
| Android 独立工程 | `android/settings.gradle.kts` 保留 App 与八个 Library，后端及 Android Wrapper 分别维护；没有根聚合 settings、内层 `.git` 或额外 Rhea/KOOM 源码导入。完整 check、APK/仪器包与独立消费者构建证据有效 |
| 根 OpenSpec 与归档消歧 | 正式规格 23 份，客户端三份内容与来源一致；此前 21 个历史归档完整保留，内存峰值客户端使用 `2026-11-08-report-memory-runtime-peaks-android`。两端任务状态与原验证材料均保留；Android 内无嵌套 OpenSpec，根与子目录解析同一平台根 |
| 共同样例及工具路径 | `android/performance-metrics/build.gradle.kts:10` 从工程位置注入唯一 fixture；`MemoryRuntimePeaksTest.sharedFixtureRoundTripHasOnlyPeakPayload` 保留原协议断言。根与 Android 两种入口均通过，缺少样例明确失败。`scripts/verify-memory-runtime-peaks.py:33` 默认根下 Android，显式目录覆盖及语法检查通过，未执行会操作设备的完整联合脚本 |
| 根技能与 Git 根语义 | Android 旧技能、副本 ZIP 与目标标记已移除，根相对软链接仍有效；`test_source.py:68` 覆盖嵌套 Android 根定位及未提交文件/索引保留，`test_analysis.py:31` 覆盖版本 4 报告的带前缀引用匹配/不匹配。Worker 104 项通过，未领取真实平台任务 |
| 签名配置外置 | `android/app/build.gradle.kts:16`、消费者 `build.gradle.kts:9` 通过 Providers 读取本机配置，绝对证书路径与完整参数门禁有效；App 与消费者都有独立校验任务。缺少/部分配置按预期失败，无 Release 配置时 App Debug 可构建；Release 及六个消费者 APK 使用原测试证书。证书与配置被 Git 忽略，原文件仍保留，历史对象未清理 |
| 文档职责与部署边界 | 根知识库 5 份、后端 2 份、前端 1 份、客户端接入 2 份、Android 内部知识库 10 份，以及导航/验收 3 份已按职责同步；API 文档无变化。1,245 个本地引用及相关标题锚点核验无问题，`.dockerignore` 与部署脚本白名单内容保持原样 |

用户在实施中明确授权的额外最小修复位于 `android/performance-metrics/src/main/java/com/shanshui/performance/memory/MemoryEventStore.kt:126`：交换比较参数形成最大堆，替代要求 API 24 的 `Comparator.reversed()`；原批次容量、顺序与退避语义保持，相关 JVM 测试及完整 lint/check 通过。没有调整 minSdk 或放宽 lint。此项恢复既有 API 21 契约，不产生规格增量。

## 自动化与产物核对

核验前当前提交的树 `5ea0bb9bbb6d28fdae737a24a6be89e1afbfe4fc` 与实施完成后保存的验证树相同，后续 Git 提交/快进未修改代码。本次只修改本任务导语并新增本报告，以下实现证据因此仍对应当前代码。

| 验证范围 | 已核对结果 |
|---|---|
| Android JVM | Debug 138 项、Release 138 项，失败/错误/跳过均为 0；报告包含共同契约和队列排序相关测试 |
| Android 构建 | 全量 `check`、模块依赖边界、App Debug/Release 及 Release 仪器 APK 均成功；修复后的 lint 通过 |
| 发布制品 | 独立版本 `1.0.0-monorepo-44a43fe7`，八个模块的 AAR、sources、POM、module metadata 共 32 份；本次重新计算摘要均吻合，SDK 内部传递版本一致，默认发布版本未改 |
| 消费者 | Crash-only、Metrics-only、Full 的 Debug/Release 共六个 APK 构建成功；原证书一致；精简产物无未选原生能力，Full 有 Native Tools；Release DEX 的 SDK 类与成员保留核验通过 |
| App 产物 | 本次重新核对 Debug/Release APK 摘要与原证据一致；同次 build-info、AGP 元数据和 DEX 标识一致，原 Release 证书、mapping 与仪器包已核对 |
| 后端 | 完整 `test --rerun`，316 项中 313 通过、3 条件跳过、0 失败；未修改后端构建配置，不要求额外 clean build |
| 前端、MCP、Worker | 前端 47 文件/156 项及类型检查通过；MCP 20 项及类型检查通过；Worker 104 项通过，其中包含三项合仓路径回归 |

详细主机测试、构建与签名使用方法保存在 Android 构建测试页；各工程的最近完整基线在各自测试页维护。本机忽略目录 `build/monorepo-migration/` 的原日志、摘要和清单只作为本次核验材料，不提交构建产物或私人配置。

## 已有问题与验证边界

- 全局归档任务校验原有三个失败：`2026-08-26-add-auth-project-workspace`（42/43）、`2026-08-27-add-jank-monitoring-server`（47/48）、`2026-09-13-add-memory-leak-reports`（21/22）。本变更自身 27/27 完成，不以修改旧任务消除历史失败；迁移归档后应是 22 个归档、19 个通过，仍仅这三项失败。
- 没有业务规格增量，不执行规格同步；23 份正式规格继续保持当前语义，`skip_specs` 是显式范围声明，不是遗漏。
- 依赖使用现有 Maven Local/Gradle 缓存，隔离验证的是消费者项目缓存；不证明全新环境能够无准备获取全部依赖。
- 没有重新运行设备用例、80 分钟频率实验、真实平台分析任务、云端部署或生产验收；既有设备/联合证据继续保留原日期和范围。本次主机结果不证明 API 21 设备运行或生产容量。
- 用户后续已明确要求 Git 收尾，迁移整理提交 `bfaa7b9` 已推送并快进到 master；这是规划中“按后续用户指令执行”的阶段，不属于未经授权的主分支修改。原 Android 检出仍保留。

## 归档判断

全部适用检查通过，无需确认不完整任务或未完成材料；可以将本变更归档至 `2026-11-08-consolidate-android-into-monorepo`。原规划、设计、任务与元数据随目录保留，核验不触发新的业务代码修改、规格同步或部署。
