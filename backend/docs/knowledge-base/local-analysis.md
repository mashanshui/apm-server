# 单事件当前代码分析与本地修复

## 当前任务与证据

agent 域提供单事件任务、应用 Worker 身份、Task/Run 和停止审计。源码登记与 analysis-builds API 已删除，无构建登记或 mapping 仍可冻结 JVM fatal 事件；buildId 仅保留为事件事实。跨域通过公开 API 访问，不访问内部实体。

新任务创建时写 contract_version=3，只有当前契约任务能被列出、访问、领取及维护。证据 schemaVersion=3，报告只接受版本 4。旧任务/证据/报告原字节及审计保留，但不进入当前接口，不提供只读展示或自动升级。

prepare 读取已发布的单事件详情，不调用 Retrace 或获取当前 mapping 锁。证据仅含一份 `crash-stack`：raw_chain 为完整脱敏原始链，retrace_text 为完整固定还原文本；状态、指纹、格式与实际来源一起冻结。pending/failed/symbolicated 均可创建，冻结后后台推进、mapping 修改或删除不改写证据。准备短事务只核对应用/事件身份、代次、取消和大小上限；超限明确失败，不截断。

Worker 凭据只限应用，30 天到期、可撤销，完整值只显示一次，独立安全链不接收 Cookie、App Key 或 Query Token。事务按应用、凭据、任务顺序锁定。列表/历史数据库分页且有上限；万条合成历史的索引检查不代表生产容量。

## Run 可靠性与恢复

Run 使用稳定 requestId；报告版本 4 绑定 evidenceId/runId，不返回 snapshotId。相同摘要的结果响应未知只重传，不重新分析、读取或编辑。过期执行者不能覆盖新尝试。同任务、同凭据最多一个活动 Run，未确认停止的环境继续阻止领取和重试。每 Run 总期限十分钟、租约 90 秒；到期、取消、撤销或关闭拒绝新操作，但不能强制停止宿主模型或测试进程。

宿主从当前目录或明确 --project 选择 Git 项目，直接搜索/读取，用户明确要求修复时编辑和测试。Python 0.5.0 不遍历或复制源码，只管理配置、冻结证据、租约、报告核对和回传。源码片段由宿主提供，提交时有界检查当前位置，CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 不证明实际读取或历史 APK 对应关系。repair/verification 均为 HOST_REPORTED，SUCCEEDED 只表示报告保存，不能证明修复或测试成功。

Android 合仓后，从 `android/` 启动工具仍定位平台 Git 根；宿主对 Android 任务限定在该工程搜索、修改和测试，报告源码引用使用 `android/performance-.../...` 的根相对路径。构建在 Android 工程目录执行，或从平台根使用 `bash android/gradlew -p android ...`。统一使用根技能，内层不保留旧 Python search/read/apply 工具；Git 根定位语义和证据/报告契约不变。

localToolsStopped 与 hostStopState 分开；UNKNOWN 继续阻止重试/领取。管理员必须实际核验本次宿主分析结束再确认，不能以模型回答代替。工具停止不回退工作区，不执行 reset/clean。配置、看护与恢复见[Worker](../../../agent-skills/apm-crash-analyze/worker/README.md)。

## 内容保留与协调切换

当前终态内容默认 30 天后清理证据/结果，保留 ID、摘要、状态与停止审计；未确认停止的 Run 不清理。候选按索引分批处理并在应用锁下重检，不把全部历史读入 Java。旧契约记录不由当前维护器转换或清理。

升级须先用原工具结束在途/待回传任务并实际核验停止，再同步切换后端、网页、MCP、Worker 与 Skill。保留本地配置与凭据，不复用旧协议恢复记录。V15 仅增量建表和标识新任务，不读取/转换/清空旧 Crash 或分析数据。独立恢复与环境边界见[部署](../../../docs/knowledge-base/07-部署与运维.md)。

默认分析功能关闭。隔离验证与既有云端记录须区分，环境状态见[平台状态](../../../docs/knowledge-base/00-当前实现与验证边界.md)。正式契约见[分析 API](../../../docs/api/analysis-api.md)，安装见[Skill](../../../agent-skills/apm-crash-analyze/README.md)，自动化方法见[后端测试](06-测试与质量保障.md)。
