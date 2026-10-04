# 2026-10-01 本地执行器接入验证

本次仅使用工具创建的三行 Kotlin 示例，不读取 Performance 或实际 Crash。固定配置见 `executor.json`（旧执行器配置已删除），运行步骤见 [README](README.md)。官方密钥来自被 Git 忽略的本地配置，记录不包含完整密钥。

| 验证项 | 实测结果 |
|---|---|
| 官方模型鉴权 | `/models` 返回 200，包含 `deepseek-flash`、`deepseek-v4-pro` |
| 官方 JSON 请求 | `deepseek-flash` 返回合法 JSON；输入 39、输出 5、总计 44 Token |
| 执行器 HTTP | `/global/health` 返回版本 1.18.34；未认证访问返回 401 |
| 创建与异步提交 | `/session` 返回会话 ID；`/prompt_async` 返回 204 |
| 文件读取与结果 | `read` 工具完成，最终 JSON 为 `{"exception":"IllegalStateException","line":2,"fileRead":true}`；Pydantic 验证通过 |
| 路径边界 | `/controlled.json` 的读工具返回 error，模型输出 `{"denied":true}` |
| 文件保护 | 写 `/source` 与根文件系统均返回只读错误；未挂载 `.git`、HOME 或 Docker Socket |
| 网络保护 | `1.1.1.1:443`、`169.254.169.254:80`、宿主探针端口连接失败；专属 gateway:8080 可达 |
| 配置注入 | 示例项目配置尝试放开权限及加载抛错插件，实际配置仍为默认拒绝且插件为空 |
| 凭据隔离 | 受信包装程序检查执行器环境，不存在 `DEEPSEEK_API_KEY`；密钥仅在出口持有 |
| 代理路径 | 任意抓取路径及 shell 控制接口返回 403 |
| 请求数预算 | 上限设为 1，首个官方请求 200，第二个 429 |
| 输入/时间预算 | 请求发出前返回 429，错误分别为 INPUT_LIMIT、TIME_LIMIT |
| 输出预算 | 执行器请求 4096，但出口上限设为 32，官方返回 completion_tokens=32 |
| 中止 | `/abort` 返回 200，活动状态清空；容器清理独立核验 |
| 独立截止时间 | 2 秒运行时限触发看门狗，确认全部资源移除后再删除配置 |

实际契约差异已处理：消息查询省略 limit 时返回 400，因此适配器固定有界 limit；原生 `json_schema` 输出模式会在本次组合中重复读取，采用文本 JSON 并由 Worker 验证 Schema。权限模式按 worktree 相对路径计算，使用 `source/*`；不能将 `steps` 提示视为请求次数限制，实际请求由出口拒绝。

末次合成探针两条助手消息分别报告非缓存输入 162/143、缓存读 2432/2560、输出 43/21、total 2637/2724；用量由执行器报告，未保存模型供应商未返回的修订号，费用未知。

这些有限本机验证不证明任意平台、生产隔离、真实 Crash 质量或源码提交身份。任务领取、租约、网页闭环、设备冒烟与至少五个独立样本评估仍未完成。

参考：[OpenCode HTTP](https://opencode.ai/docs/server/)、[DeepSeek 模型接口](https://api-docs.deepseek.com/api/list-models/)、[Docker 隔离网桥](https://docs.docker.com/engine/network/port-publishing/#gateway-modes)。

2026-10-01 补充：出口模型与控制入口使用各自随机认证，HTTP 缺失/错误认证测试确认在预留预算前返回 401，不发生供应商连接；完整 Python 测试 22 项通过。

## 单任务 CLI 与恢复验证

固定配置下的真实 Docker/OpenCode/官方 DeepSeek 合成任务已完成：任务 `94b7ab9d-3994-4a95-97e0-9e9f762adf0f`、Run `50fa12c6-ea02-4ce6-bd5f-f39e8b4a35a2`。平台为受控 HTTP 假服务，源码为独立 Git 提交中的三行 Example.kt；正式 CLI 完成领取、证据 SHA 校验、实际 read、结构化结果、引用第 1～3 行、完整环境停止和回传。结论 ROOT_CAUSE_CANDIDATE，引用含第 2 行显式 IllegalStateException。

执行器报告非缓存输入 1781、输出 606、缓存读 6016、缓存写 0 Token，费用未知。此用量不能推导其他 Crash 的成本。恢复状态 DONE 已清除短期租约与结果原文；Docker 中对应容器和网络已移除。单独的 1 秒租约探针确认出口与运行环境到期停止，停止确认后资源数为零。

Python 当前 68 项测试覆盖 CLI、HTTP、有界响应、租约暂停/时钟偏差、预算、源码身份/越界、脱敏、结构化结果、未知回传、重启清理及配置缺失零模型调用。未知结果仍保持同一摘要，明确过期拒绝后补停止回执；不重复模型。上述模拟平台和合成异常不计入独立根因质量样本。

Performance 设备事件、本地事件重放和网页验收分别见[跨端记录](../../../docs/analysis-validation/README.md)。后续五个受控场景评估已完成，格式/引用失败和结论枚举差异详见跨端记录。


Performance 真实事件已完成正式 CLI 分析与真实后端回传、网页结果验收；Run a7c41ebf-0916-4b1e-a15b-c23102678e5c，实际 read 后三处源码引用与固定提交核验一致，原源码和运行环境清理通过。测试仓库签名文件按用户确认明确排除并记录路径，详见[跨端记录](../../../docs/analysis-validation/README.md)。Python 68 项测试通过。此事件经过云端上报后本地重放，不代表新接口已部署云端。

本轮真实质量评估没有改提示词或 Worker：五个受控场景执行七个 Run，2 成功、5 失败；成功结果共五处引用独立核验，全部运行环境停止并清理。用户只显式授权两次重试，未自动重跑其他失败。FORMAT_INVALID 与 REFERENCE_INVALID 的具体拒绝原因尚未取得足够诊断依据；不能把安全拒绝等同定位质量成功。未运行新的 Python 全量回归。
