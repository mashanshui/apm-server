# 单事件当前代码分析与本地修复

## 当前任务与证据

agent 域提供单事件任务、应用 Worker 身份、Task/Run 和停止审计；跨域只访问 identity.api、crash.api、symbol.api、platform.api。源码登记与 analysis-builds API 已删除，无构建登记/mapping 仍可冻结原始 JVM fatal 异常证据。原 Crash/符号 buildId 保留。

证据 schemaVersion=2，不含源码来源绑定；任务查询只投影元数据和 evidenceSchemaVersion，不返回完整 JSON。prepare 事务外取得同次单事件还原，发布短事务核对事件身份、准备代次、取消状态及 mapping 版本锁。超限失败，不截断。现有符号未知原因随证据保留，冻结后不被后续 mapping 更新改写。

Worker 凭据仅限应用，30 天到期、可撤销，完整值只显示一次，独立安全链不接收 Cookie、App Key 或 Query Token。事务按应用、凭据、任务顺序锁定。列表/历史数据库分页且有上限；历史万条合成数据检查仅证明索引选择，不能推导生产容量。

Run 使用稳定 requestId，同请求换任务冲突；新分配 snapshotId=null，仅保留历史列。报告 schemaVersion=4 绑定 evidenceId/runId；源码引用由宿主提供，提交时核对 CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE。repair 和 verification 均为 HOST_REPORTED，无法证明历史版本、实际读取/修改或验证后工作区一致性；宿主模型/用量未知。旧版本 1～3 用原 JSON 返回，旧冻结证据不能执行新协议。SUCCEEDED 仅表示报告保存。

宿主从当前目录或明确 --project 选择 Git 项目，直接搜索/读取、明确授权时编辑和测试；Python 不全量采集或限制源码规模，只管理配置、证据、租约和报告。提交时有界核对所引用位置，不形成读取证明。看护与配置见 [Worker](../../../agent-skills/apm-crash-analyze/worker/README.md)。后台看护不控制宿主模型或验证进程，localToolsStopped 与 hostStopState 分开；UNKNOWN 继续阻止重试/领取，管理员实际核验后确认。

V14 删除分析登记结构及重复列，保留终态 JSON 原字节、摘要和审计；活动旧任务或未确认停止 Run 阻止迁移。迁移与回退见 [存储](03-持久化与数据库迁移.md) 和 [部署](../../../docs/knowledge-base/07-部署与运维.md)。默认功能关闭；控制面和网页已有有限云端验收，执行位置仍在宿主本地，环境状态见[平台最近核验](../../../docs/knowledge-base/00-当前实现与验证边界.md#环境最近核验)。

自动化与外部范围分别见[后端测试](06-测试与质量保障.md)和[当前代码验收](../../../docs/analysis-validation/current-code-validation.md)。正式契约见 [分析 API](../../../docs/api/analysis-api.md)，安装见 [Skill](../../../agent-skills/apm-crash-analyze/README.md)。

## Run 可靠性与恢复

稳定 requestId 用于领取幂等；旧 Run 不能覆盖新尝试。应用、凭据、任务按固定顺序加锁，未确认停止的任务/Worker 索引门禁阻止再次领取。每 Run 总期限十分钟、租约 90 秒；到期、取消、撤销或功能关闭均拒绝新操作，但不能强制停止宿主模型或已启动测试进程。

Python 将原始结果字节与摘要先保存到私有状态；RESULT_PENDING 只重传同一结果，不重新分析、读取或编辑。stop 关闭本任务工具而不回退代码；恢复用户工作区由宿主负责，不执行自动 reset/clean。SUCCEEDED 只表示报告保存，localToolsStopped 与 hostStopState 分别记录；UNKNOWN 需要管理员实际核验后解锁，不能由报告自行声称整体停止。

## 内容保留与升级

终态内容默认 30 天后清理证据/结果正文，保留 ID、摘要、状态和停止审计；存在未确认停止 Run 时不清理。候选按索引分批查询，并在应用锁下重检，不扫描全部历史到 Java。

V14 删除旧分析源码登记，保留旧报告 JSON 与审计；活动任务或停止未知时迁移拒绝。新 Run 不再写 snapshot_id，历史版本 1～3 按原值只读展示，不能用当前协议重跑。工具升级前用原版本结束活动/待回传记录，并保留 Skill 本地配置，详见[Worker 恢复规则](../../../agent-skills/apm-crash-analyze/worker/README.md#租约与恢复)。
