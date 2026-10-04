# 宿主直接分析的任务接入工具

宿主负责当前源码搜索、读取、明确授权的修改和实际终端验证。Python 负责配置、项目定位、冻结单事件证据、Run/租约、报告结构与引用核对、幂等回传。入口为 [Skill](../SKILL.md)，安装见 [使用说明](../README.md)。

## 配置与开发

默认读取 Skill 根 config.local.json 的 platformUrl/workerCredential。空模板随包提供；仅当前用户所有、含凭据时权限 600、最多 8 KiB、严格两个字符串字段。拒绝链接、特殊文件、未知/重复字段。配置失败先于网络/状态/任务动作；不打印秘密、不自动加载 .env。成对环境 APM_ANALYSIS_URL/APM_WORKER_CREDENTIAL 整组覆盖，后台使用前台同次身份。

项目从当前目录 Git 根或 --project 绝对位置确定，只执行 rev-parse --show-toplevel，不读取 HEAD、origin、Git 文件清单或源码树。开发在本目录用 `uv sync --locked --python 3.12`、`uv run --locked pytest`；独立使用统一启动器，不需额外检出 Worker。

## 动作与协议

| 动作 | 用途 |
|---|---|
| doctor | 本地配置格式与权限诊断，不验证平台权限 |
| prepare taskId | 定位当前项目、领取、核对冻结证据；明确修复首次可 --repair |
| status taskId | 查询当前分配，活动任务核验租约和看护 |
| submit taskId | schemaVersion=4 语义 JSON；恢复待回传不带 --result |
| stop taskId | 关闭本任务工具，宿主停止保持 UNKNOWN |

无 search/read/apply/revert，也无全仓库采集、源码总量/数量门禁、源码快照或工具补丁日志。新 claim 只 requestId；报告绑定 evidenceId/runId。snapshot_id 和旧报告 JSON 仅历史保留。

## 源码和报告边界

宿主自行使用项目读取/编辑工具；遵循用户分析/明确修复范围和项目规则，不读取秘密、不覆盖用户原改动。Python 不承诺操作系统沙箱、实际读取证明或统一限制宿主写入、模型及测试进程。

源码引用由宿主提供相对路径、行范围、片段，最多 20 条、每条 200 行/8 KiB。Python 只对这些当前位置做提交时匹配，不遍历其他文件；使用 openat/O_NOFOLLOW 拒绝链接和特殊文件。核对单条位置最多扫描八 MiB、单行/片段八 KiB，超过/缺失/并发变化返回 UNAVAILABLE，不能据此阻塞整仓库或宣称匹配。结果 CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 与 HOST_REPORTED 来源分别记录，片段脱敏 SHA-256 仅说明展示内容完整性，不证明宿主实际读取。

修改文件 path/summary、状态及测试 commands/exitCode/summary 均 HOST_REPORTED。工具不生成修改前/后摘要、独立测试结果或 workspaceUnchanged。只读意图拒绝报告已修改，不代表能限制宿主其他工具。PASSED 要有命令且全部零退出，FAILED 要有非零，NOT_RUN 无命令且有原因；后端再校验结构和当前 Run 归属。

保留配置、网络响应、报告一 MiB及本地状态二 MiB限制。自由文本/命令清理明确秘密和本机路径，不能识别任意未命名秘密；宿主须检查报告内容，不回传整个仓库、私有状态或原始秘密日志。

## 租约与恢复

看护 20 秒、租约 90 秒、总期限十分钟。活动 status/submit 核对看护和数据库租约；取消/到期/失联拒绝新工具动作，宿主须停止本任务新的读写/验证。Python 无法中止宿主其他工具或已经启动的验证进程。

结果原字节先落私有状态，再回传；RESULT_PENDING 仅原字节对账，不重新读取源码、推理或编辑。领取回执未知复用 requestId。修改恢复由宿主负责，不执行 git reset/clean，stop 不回滚。localToolsStopped 表示本任务工具关闭；hostStopState=UNKNOWN 仍由管理员真实核验，不用宿主自报解锁。

默认私有状态 ~/.local/state/apm-analysis，目录 700/文件 600、串行锁防止重复任务。版本 0.4.0 不升级旧活动/待回传记录；须原工具结束/停止核验后更新。旧终态按原报告历史显示，无法用新入口重跑。升级保留配置见[安装说明](../README.md#更新时保留配置)。
