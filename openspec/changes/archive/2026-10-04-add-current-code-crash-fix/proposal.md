> 2026-10-04 后续实施采用文末“宿主直接读取与修改”方案；前述快照、受控读取及 Python 补丁流程保留为历史实施记录。

# 当前工作区单事件崩溃分析与本地修复提案

## Why

现有分析要求人工登记 APK 构建、源码提交和本地仓库映射，且只能读取历史提交，使用成本较高。用户已选择仅基于当前项目工作区分析和修复，包含未提交修改；本变更替代旧分析入口，减少配置并交付可审阅的本地修改。

## What Changes

- 从单个 eventId 创建任务，宿主通过已有 taskId 调用自包含 Skill；Python 准备证据及当前工作区快照，宿主分析，按明确修复指令生成修改，Python 核验写入并回传结果。
- **BREAKING** 移除分析专用构建登记及其 API、管理表单、版本历史与相关前提：buildId、commitSha、repositoryId、origin、登记版本、混淆状态核验和人工核验依据不再属于新分析配置或源码绑定。
- **BREAKING** 取消 repositories.json、APM_ANALYSIS_REPOSITORIES 和固定 performance 限制；从宿主当前工作目录定位项目，不通过 Skill 安装位置或远端地址推测项目。不在任何运行步骤要求提交 SHA。
- 当前工作区包括已跟踪文件的未提交修改及未忽略的普通源码文件；保留源码快照 ID、读取事实及必要内容摘要，防止引用漂移和修改冲突。未提交修改不是阻断条件。
- 仅保留当前代码模式，保留原始 Crash 协议中的 buildId 和日常 mapping 还原；分析可使用原始堆栈，符号缺失作为未知项，不再强制构建登记或 mapping 完整才创建任务。不声称当前源码是崩溃 APK 的源码。
- 修复须用户明确要求；仅请求分析时保持只读。修复在当前工作区交付有限改动、必要测试和差异报告，不自动提交、推送、发布或开 MR。写入前核验原文件内容，保护用户已有修改。
- 复用单事件授权、Worker 应用身份、冻结证据、领取、租约、显式重试、私有恢复及停止审计。取消不能保证宿主模型停止，未知事实继续如实报告。
- 保留 Skill 内置 Python、锁定依赖和 ZIP 交付；模型沿用宿主设置，不增加模型管理、独立执行器、MCP、云端修复或通用插件框架。

## Capabilities

### New Capabilities

- `current-code-crash-fix`：无需构建登记的单事件任务、当前工作区材料、受控本地修复、验证和结果回传。

### Modified Capabilities

- `crash-frontend`：单事件当前代码分析/修复指引、最小 Worker 配置、修复与验证结果；删除构建源码登记 UI。
- `jvm-crash-monitoring`：仅分析任务可冻结本次单事件还原证据，日常实时还原保持原语义；当前代码分析不依赖构建源码绑定。

## Impact

- 后端 agent 域、后续 Flyway 迁移和分析 HTTP 契约：移除构建/仓库依赖，简化凭据作用域，调整证据及结果版本；不修改已发布迁移。已有终态报告、冻结 JSON 与停止审计保留，旧活动任务须结束并核验后切换，不保留旧模式继续执行。
- Python Worker、apm-crash-analyze Skill、打包脚本及其测试：以工作区快照代替 Git 提交导出，增加有限修改入口、冲突与恢复验证。
- 前端 AnalysisAdminPanel、CrashAnalysisPanel 和分析类型/API：只保留必要身份管理与任务、报告交互，移除旧 DeepSeek 文案。
- 实施时同步根、后端、前端知识库、正式 API、Skill 安装说明与验收；Android 上报、mapping 上传、Crash 统计及既有只读 MCP 契约不变。
- 本变更独立于已完成的 [add-local-crash-analysis](../2026-10-04-add-local-crash-analysis/proposal.md)，其 46 项记录及旧质量结论保留。上述内容均为待实施目标，不能作为当前运行能力或修复质量证明。

2026-10-03 用户追加授权：Skill 根目录增加空的本地最小配置文件，由 Python 校验读取、缺失时提示，并同步安全排除、空模板打包及升级保留步骤。原环境入口成对整组覆盖，不修改服务端、Android 或 MCP 契约。增量验收由 tasks 第 7 节跟踪。

## 宿主直接读取与修改（2026-10-04）

用户已授权实施：宿主自行搜索、读取当前项目，明确修复时自行修改并验证；Python 仅维护受保护本地配置、项目定位、单任务领取、冻结 Crash 证据、租约、结构化结果核验与幂等回传。移除全仓库采集、源码总量/文件数量门禁及 search/read/apply/revert 入口，不把宿主置于统一操作系统沙箱。

新 claim 仅 requestId；新报告 schemaVersion=4 绑定 evidenceId/runId，不创建 snapshotId。数据库旧 snapshot_id 及版本 1～3 的历史报告保持原值和只读展示，不增加迁移。新报告源码片段由宿主提供，Python 核验安全相对路径、范围、片段格式和脱敏摘要，并只对引用位置做提交时当前文件匹配检查；CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 不能作为宿主实际读取证明。

修改文件和测试命令明确 HOST_REPORTED，不再补造 Python 写前日志、修改前摘要或 workspaceUnchanged。只读准备拒绝提交已修改事实；--repair 仅记录用户明确修复意图，不能限制宿主其他工具。失联/取消/到期阻止 Python 继续获取证据或回传，宿主须自行停止本任务读写/验证；UNKNOWN 停止仍需管理员真实核验。

保留配置、网络响应、引用/报告及本地状态大小限制；这些用于有界协议处理，不限制宿主源码读取或模型预算。更新安装包并保留 config.local.json，Python 版本 0.4.0；旧活动/待回传记录不升级执行，须旧工具先完成或停止核验。通过真实 Git、受控 HTTP、后端 PostgreSQL 和前端测试分别验证，不重跑已完成真实任务或擅自修复 Android 业务。
