# 单事件当前代码分析页面

## 任务与报告交互

JVM fatal 详情的 CrashAnalysisPanel 按路由应用解析实时成员角色，OWNER/ADMIN/DEVELOPER 创建，VIEWER 只读。请求只包含幂等键，无构建登记。网页不会自动执行本地流程；READY 证据版本 2 显示“仅分析”和“明确分析并修复”的自然语言 Skill 示例，项目由宿主当前目录或用户明确位置选择。

报告版本 4 分别展示当前 Run、历史 APK 局限、宿主自报修改文件及说明、验证命令/退出码。源码引用也为宿主自报，CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 只表示提交时当前位置核对，不能作为实际读取或历史源码证明。Python 不独立验证修改或测试。版本 3 保留历史快照与原摘要显示。APPLIED、PARTIAL、CONFLICT、FAILED、NOT_REQUESTED/NOT_APPLICABLE 与 PASSED/FAILED/NOT_RUN 独立显示；宿主验证不是独立 CI。报告已保存不代表修复通过。历史版本 1/2 保留原 commitSha/模型来源，只读展示，不提供旧执行指令或重试。

Task/Run 均后端分页（每页 20，页码至多 1000）。活动任务及停止未知五秒轮询；旧响应代次和 AbortController 防止路由/页码切换污染，会话到期清理。创建响应未知沿用原幂等键。重试要求版本 2、证据未到期、历史环境全部确认停止；管理员填写实际停止核验依据，成功报告仍可处于 UNKNOWN。

AppSettingsView 的 AnalysisAdminPanel 仅管理应用 Worker 名称、创建/分页/撤销；不发出 analysis-builds 请求，不登记源码或配置模型。完整凭据仅组件内存显示一次、主动复制，关闭、离页、角色/应用切换或认证到期清空，不写浏览器存储。创建结果未知提示核对并撤销，不自动重复创建。

所有模型内容和命令用普通 Vue 文本插值，禁止解析 HTML/Markdown。分析面板沿用 meta-item 和统一内边距，长 UUID 可换行。自动化与真实浏览器范围分别见[前端测试](06-测试与质量保障.md)和[当前代码验收](../../../docs/validation/analysis/current-code-validation.md)。

入口见 [Skill](../../../agent-skills/apm-crash-analyze/README.md)、[Worker](../../../agent-skills/apm-crash-analyze/worker/README.md) 和 [分析 API](../../../docs/api/analysis-api.md)。原 Crash 原始堆栈与实时 mapping 详情保留。

## 凭据与停止状态

新报告中的宿主名称来自自报，模型/用量无法核验时显示未知；旧版本按原字段展示，不提供旧执行入口。SUCCEEDED 与 hostStopState=UNKNOWN 可同时存在，页面分别展示工具关闭、宿主停止与实际核验依据。管理员须核验本次宿主分析结束后才能确认整体停止，不能用模型回答代替。

长任务 ID 可换行，标题与正文沿用统一卡片内边距和 meta-item 样式；不保留单次样式修复流水。有限历史模型评估和页面截图由[跨端历史验收](../../../docs/validation/analysis/historical-analysis-validation.md)维护，不代表当前生产修复质量。
