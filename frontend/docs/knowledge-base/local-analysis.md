# 单事件当前代码分析页面

## 当前任务与报告

JVM fatal 详情按 crashKind/fatal 事实显示 CrashAnalysisPanel，成功后只有还原文本也能分析。角色按当前路由应用匹配，OWNER/ADMIN/DEVELOPER 可创建，VIEWER 只读。请求只有幂等键，无源码或构建登记。网页不会启动本地流程；READY 证据版本 3 显示仅分析和明确分析并修复的 Skill 示例，项目由宿主当前目录或明确位置选择。

当前任务仅支持证据版本 3（未准备时可为 null），报告只支持版本 4。旧任务、旧报告和旧快照字段作为协议错误处理，不保留历史只读渲染器或升级转换。任务冻结单份 crash-stack 和实际来源；原始链与还原文本分别标记，后续后台归组或 mapping 操作不改写它。

报告分别展示当前 Run、历史 APK 局限、宿主自报修改文件与说明、验证命令/退出码。源码片段为宿主自报，CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 仅表示提交时当前位置检查，不是实际读取、独立 CI 或历史源码证明。修改状态和验证状态独立，报告保存也不代表修复成功。

## 请求、权限与停止

Task/Run 均后端分页（20/页、页码最多 1000）。活动任务及停止未知五秒轮询；代次和 AbortController 防止路由/分页切换污染，会话到期清理。创建响应未知复用幂等键。重试要求当前证据未到期、全部旧 Run 已确认停止；UNKNOWN 继续阻止新尝试，成功报告也可能等待管理员实际核验。

AppSettingsView 的 AnalysisAdminPanel 仅管理应用 Worker 名称、创建/分页/撤销；不登记源码或模型。完整凭据仅组件内存显示一次，关闭、离页、角色/应用切换或认证到期清空，不写浏览器存储。创建响应未知须核对后处理，不自动重复创建。

所有文本和命令使用 Vue 普通插值，禁止解析 HTML/Markdown。组件、真实后端浏览器及历史模型评估的边界分别见[前端测试](06-测试与质量保障.md)、[隔离验收](../../../docs/validation/background-crash/README.md)和[历史评估](../../../docs/validation/analysis/historical-analysis-validation.md)。

正式入口见[Skill](../../../agent-skills/apm-crash-analyze/README.md)、[Worker](../../../agent-skills/apm-crash-analyze/worker/README.md)和[分析 API](../../../docs/api/analysis-api.md)。
