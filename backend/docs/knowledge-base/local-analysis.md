# 单事件当前代码分析与本地修复

## 当前实现（2026-10-04）

agent 域提供单事件任务、应用 Worker 身份、Task/Run 和停止审计；跨域只访问 identity.api、crash.api、symbol.api、platform.api。源码登记与 analysis-builds API 已删除，无构建登记/mapping 仍可冻结原始 JVM fatal 异常证据。原 Crash/符号 buildId 保留。

证据 schemaVersion=2，不含源码来源绑定；任务查询只投影元数据和 evidenceSchemaVersion，不返回完整 JSON。prepare 事务外取得同次单事件还原，发布短事务核对事件身份、准备代次、取消状态及 mapping 版本锁。超限失败，不截断。现有符号未知原因随证据保留，冻结后不被后续 mapping 更新改写。

Worker 凭据仅限应用，30 天到期、可撤销，完整值只显示一次，独立安全链不接收 Cookie、App Key 或 Query Token。事务按应用、凭据、任务顺序锁定。列表/历史数据库分页且有上限；历史万条合成数据检查仅证明索引选择，不能推导生产容量。

Run 使用稳定 requestId，同请求换任务冲突；新分配 snapshotId=null，仅保留历史列。报告 schemaVersion=4 绑定 evidenceId/runId；源码引用由宿主提供，提交时核对 CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE。repair 和 verification 均为 HOST_REPORTED，无法证明历史版本、实际读取/修改或验证后工作区一致性；宿主模型/用量未知。旧版本 1～3 用原 JSON 返回，旧冻结证据不能执行新协议。SUCCEEDED 仅表示报告保存。

宿主从当前目录或明确 --project 选择 Git 项目，直接搜索/读取、明确授权时编辑和测试；Python 不全量采集或限制源码规模，只管理配置、证据、租约和报告。提交时有界核对所引用位置，不形成读取证明。看护与配置见 [Worker](../../../agent-skills/apm-crash-analyze/worker/README.md)。后台看护不控制宿主模型或验证进程，localToolsStopped 与 hostStopState 分开；UNKNOWN 继续阻止重试/领取，管理员实际核验后确认。

V14 删除分析登记结构及重复列，保留终态 JSON 原字节、摘要和审计；活动旧任务或未确认停止 Run 阻止迁移。迁移与回退见 [存储](03-持久化与数据库迁移.md) 和 [部署](../../../docs/knowledge-base/07-部署与运维.md)。默认功能关闭，当前未部署云端。

专项 35 项（任务 26、迁移 3、ArchUnit 3、实时还原 3）已通过，使用 Android Studio JBR、真实 PostgreSQL 16；任务 Crash 边界为合成 DTO。包括四类角色、CSRF、幂等、取消、大小、身份、并发、停止门禁、旧报告只读、Run 归属及万条历史计划。全量回归和真实联调单独记在 [当前代码验收](../../../docs/analysis-validation/current-code-validation.md)。正式契约见 [分析 API](../../../docs/api/analysis-api.md)，安装见 [Skill](../../../agent-skills/apm-crash-analyze/README.md)。

## 历史实施记录（2026-10-01～02，已被当前方案替代）

以下构建登记、固定提交和执行器描述只保留原验证记录，其命令/前提不适用于当前版本。

Worker 源码现位于 Skill 内，支持独立 ZIP 安装；身份、HTTP 契约与停止门禁不变。交付方式见 [Skill 说明](../../../agent-skills/apm-crash-analyze/README.md)，独立安装验证见 [交付验收](../../../docs/analysis-validation/skill-package-validation.md)。

当前新增 agent 域的构建登记，跨域授权只访问 identity.api，错误使用 platform.api。另已实现单事件准备和独立 Worker 身份；另已实现领取/租约、停止与重试、结构化结果及到期维护；Python 正式任务与网页已实现，实际链路及受控质量评估见跨端记录。正式契约见[分析 API](../../../docs/api/analysis-api.md)，执行器接入见[Worker](../../../agent-skills/apm-crash-analyze/worker/README.md)。

Flyway V6 新增固定仓库标识表、不可变构建版本表和当前版本指针表。完整版本键为 appId/buildId/revision；事务级应用 advisory lock 串行化首次登记和更正。历史写入与指针更新原子完成，同内容不追加，过期期望版本拒绝。既有迁移不修改。

当前列表使用应用过滤、updated_at/buildId 排序与 LIMIT/OFFSET，默认 20、最大 100、page 最大 1000；历史使用应用/构建主键前缀索引按版本倒序读取。禁止 JVM 全量分页。登记不触发 Crash 查询或模型调用。管理员人工核验依据仍可能错误，不能把它当成构建来源的密码学证明。

2026-10-01 使用 Android Studio JBR 启动 Gradle，编译及测试采用已有 Java 21 工具链。`AnalysisBuildPostgresTests` 在独立 PostgreSQL 16 容器、真实 Flyway/Spring/MockMvc 上执行 3 项，全部通过且未跳过，覆盖幂等、首次登记并发、冲突、历史、四类角色、CSRF、匿名与跨应用。ArchUnit 3 项通过。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
bash backend/gradlew -p backend test \
  --tests com.shanshui.apmserver.AnalysisBuildPostgresTests \
  --tests com.shanshui.apmserver.ArchitectureBaselineTests
```

本次有限测试不证明生产数据规模；完整后端回归、任务证据、网页联调、真机与质量样本尚待完成。

## 单事件证据及执行身份

V7 保存任务、幂等键、准备代次、固定模型配置和有界证据。create 只调用 `crash.api.rawEvent` 取得单事件绑定，事务外调用 `analysisSnapshot` 执行同次还原；公开 SymbolFileLease 携带该版本摘要。发布短事务在应用 advisory lock 下重新核对登记，并通过 symbol.api 在当前 mapping 行加 PESSIMISTIC_READ 锁核对 ID/版本/SHA。准备代次与状态条件防止重检或取消后的迟到发布。证据发送原文与 JSONB 双存，数据库约束分别限制 1 MiB；摘要来自原始 UTF-8。未混淆允许无 mapping，混淆必须成功还原，不静默截断。

V8 单独保存 Worker 凭据摘要与固定范围，默认到期 30 天。独立 `/api/worker/v1/**` SecurityFilterChain 不加载 Session、不使用 QueryTokenFilter；每请求核对撤销及数据库时间。元数据读取按凭据应用定位任务，完整证据仅开放给当前有效 Run。管理员创建/撤销沿用 Session、CSRF 和实时角色。

`AnalysisTaskPostgresTests` 当前 22 项在真实 PostgreSQL 16 和 Flyway 上通过，Crash 查询边界使用合成 DTO；覆盖单事件读取、重检、幂等、权限、超限、取消发布竞争、登记及 mapping 版本变化、冻结不变、凭据隔离/到期/撤销、万条合成历史索引计划。证据发布竞争使用 SQL 模拟已提交取消；取消/重试/领取/回传 HTTP 生命周期另有 MockMvc 集成覆盖。邮箱脱敏使用边界和占有量词避免长文本反复回溯，1 MiB 长消息超限测试通过。上述计划仅证明合成数据下的索引选择，不证明生产吞吐。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
bash backend/gradlew -p backend test \
  --tests com.shanshui.apmserver.AnalysisTaskPostgresTests \
  --tests com.shanshui.apmserver.AnalysisBuildPostgresTests \
  --tests com.shanshui.apmserver.CrashLiveSymbolicationTests \
  --tests com.shanshui.apmserver.ArchitectureBaselineTests
```

相关 Crash 实时还原、卡顿 parser 假租约和模块边界回归已通过；完整后端测试仍待本次功能完成后执行。本文记录代码和有限自动化验证，未进行网页、Performance 设备、独立质量样本或生产验收。

## Run 可靠性及内容维护

V9～V11 保存每次独立分配、稳定请求 ID、短期租约、停止事实审计、结果原始字节/JSONB 及终态内容到期标记。唯一部分索引分别限制活动任务及 Worker；事务先锁应用、再锁有效凭据，防止撤销认证和实际写入之间的竞争。到期状态先提交，再返回租约错误，避免异常回滚导致仍显示 RUNNING。原分配完成的相同摘要重传可对账，不同摘要冲突；旧 Run 不能覆盖更新尝试。

旧独立执行器方案取消先 CANCELLING，依赖完整运行环境清理；当前宿主方案如下节。失败或失联 stopConfirmed=false，不释放重试门禁。管理员核验记录具体环境检查依据和核验人；重复记录不改原事实。每次总期限 10 分钟，续租 90 秒不能无限延长；后台每批最多百条，核对租约、期限和凭据失效。关闭功能发出停止意图，保留停止确认、撤销及历史读取；不修改原 Crash 及查询 Token 链。

终态内容 30 天后仅清理证据和结果正文，保留 ID/摘要/任务及 Run 状态和停止审计；存在任何未确认停止的环境不清理。维护按终态索引候选过滤并在应用锁下重检，防止重试竞争。默认测试环境将周期任务启动延迟一小时，PostgreSQL 测试主动调用维护，避免 H2 未建立管理表时启动真实作业。

当前 `AnalysisTaskPostgresTests` 共 22 项通过，包含两个 Worker 并发领取、同请求并发/丢失响应重试、租约错误与过期提交、停止前重试拒绝、撤销后管理员核验、不同结果摘要冲突、跨证据/非法引用、证据不足、真实 HTTP 领取/完成/历史、2 MiB 入口限制、总期限、功能关闭及到期保留未知环境。代码不调用外部模型，源码实际行号核验仍由后续 Worker 实现；这些合成测试不证明真实源码定位质量。

2026-10-01 较早一次全量后端回归：253 项，249 通过、1 失败、3 跳过；失败为已有 `RheaStackAnalyzerIntegrationTests` processor 制品摘要校验。本次未修改 processor 或降低验收条件。最新新增 Run/到期逻辑后，相关六组共 46 项通过；全量最终回归还需在 Worker/前端完成后执行。


## 本地 Worker 与最终回归

Python 任务 CLI、受限运行时及结构化结果已实现；配置缺失零模型/领取调用、固定提交脏工作区隔离、未知完成重传、重启清理和取消均有专项测试。有限真实合成 CLI 使用固定 Docker/OpenCode/DeepSeek 官方模型成功生成引用，详细用量与环境边界见 [Worker 记录](../../../agent-skills/apm-crash-analyze/worker/validation.md)。网页与设备分层验收见 [跨端记录](../../../docs/analysis-validation/README.md)。

2026-10-01 全量 `test`：264 项，260 通过、1 个既有 Rhea processor SHA 校验失败、3 个专项性能测试按条件跳过。AnalysisBuildPostgresTests 3 项及 AnalysisTaskPostgresTests 22 项全部通过，均使用真实 PostgreSQL 16 和 Flyway。未修改 Rhea 摘要校验以绕过失败。TypeScript MCP 15 项回归通过，其实现语言与只读权限保持不变。


2026-10-01：正式 Worker 已对 Performance 真机事件的本地重放成功回传；指定提交三处代码引用核验一致，Run 停止确认和容器/网络清理独立核对通过。已识别签名/私钥文件按用户确认排除并记录，不送模型；详情与质量边界见跨端记录。

2026-10-01 质量评估补充：五例真实事件经本地 HTTP 重放并冻结证据，共七个 Run，全部停止确认及环境清理通过；两次显式重试保持同一 Task，失败历史保留。2 个有效结构化结果、3 个样本失败，格式/引用拒绝原因仍待进一步诊断。失败结果为空且用量未知，不能解释为空结果成功。本文早期阶段的“待实施/未进行”仅是当时记录，当前范围以 [跨端验收](../../../docs/analysis-validation/README.md) 的最新结论为准；本轮未改后端代码或重新执行全量回归。


## 2026-10-02 当前宿主分析

新任务保存 host-agent-local/version 2/HOST_AGENT；领取拒绝旧冻结策略，未冻结 BLOCKED 显式重检可准备新策略。结果 schemaVersion=2，Python 0.2.0 生成执行摘要与全部未知用量，宿主只能自报名称，不能填 OpenCode 版本或停止证明。历史结果版本 1 仍可展示，不改冻结输入。

V12 分开 local_tools_stopped 与 host_stop_state；成功回传必须工具已关闭且整体停止 false，保存 SUCCEEDED/UNKNOWN，不释放门禁。stopped 只报告工具关闭，管理员实际核验后通过既有审计确认整体停止，旧 NOT_APPLICABLE 不重写。V13 对 credential_id 建立 RUNNING 或 stop_confirmed=false 部分索引，门禁通过 EXISTS 命中即停，避免逐次统计该身份全部历史；任务/凭据锁及分页边界不变。

Python 每次材料动作都向后端核验租约，独立有限看护续租和取消清理；失联不能离线读取。回传按原结果字节和摘要幂等，Python 核验引用包含在真实返回的 read 范围内。后端独立检查证据归属、版本、片段摘要和字段结构，不能独立证明磁盘源文件存在。

本轮核对并修正旧专题“运行停止依赖容器”的当前口径，早期带日期测试记录仍是历史事实。当前自动化、实际源码和网页验收见[宿主分析验收](../../../docs/analysis-validation/host-analysis-validation.md)。原 Crash 上报、客户端事件与 TypeScript MCP 未改。
