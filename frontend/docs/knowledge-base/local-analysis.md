# 单事件当前代码分析页面

## 当前交互（2026-10-04）

JVM fatal 详情的 CrashAnalysisPanel 按路由应用解析实时成员角色，OWNER/ADMIN/DEVELOPER 创建，VIEWER 只读。请求只包含幂等键，无构建登记。网页不会自动执行本地流程；READY 证据版本 2 显示“仅分析”和“明确分析并修复”的自然语言 Skill 示例，项目由宿主当前目录或用户明确位置选择。

报告版本 4 分别展示当前 Run、历史 APK 局限、宿主自报修改文件/摘要和验证命令/退出码。源码引用也为宿主自报，CURRENT_MATCH/CURRENT_DIFFERENT/UNAVAILABLE 只表示提交时当前位置核对，不能作为实际读取或历史源码证明。Python 不独立验证修改或测试。版本 3 保留历史快照与原摘要显示。APPLIED、PARTIAL、CONFLICT、FAILED、NOT_REQUESTED/NOT_APPLICABLE 与 PASSED/FAILED/NOT_RUN 独立显示；宿主验证不是独立 CI。报告已保存不代表修复通过。历史版本 1/2 保留原 commitSha/模型来源，只读展示，不提供旧执行指令或重试。

Task/Run 均后端分页（每页 20，页码至多 1000）。活动任务及停止未知五秒轮询；旧响应代次和 AbortController 防止路由/页码切换污染，会话到期清理。创建响应未知沿用原幂等键。重试要求版本 2、证据未到期、历史环境全部确认停止；管理员填写实际停止核验依据，成功报告仍可处于 UNKNOWN。

AppSettingsView 的 AnalysisAdminPanel 仅管理应用 Worker 名称、创建/分页/撤销；不发出 analysis-builds 请求，不登记源码或配置模型。完整凭据仅组件内存显示一次、主动复制，关闭、离页、角色/应用切换或认证到期清空，不写浏览器存储。创建结果未知提示核对并撤销，不自动重复创建。

所有模型内容和命令用普通 Vue 文本插值，禁止解析 HTML/Markdown。分析面板沿用 meta-item 和统一内边距，长 UUID 可换行。自动化 124 项、类型检查及构建已通过，生产构建与真实浏览器核验分别见 [当前验收](../../../docs/analysis-validation/current-code-validation.md)。

入口见 [Skill](../../../agent-skills/apm-crash-analyze/README.md)、[Worker](../../../agent-skills/apm-crash-analyze/worker/README.md) 和 [分析 API](../../../docs/api/analysis-api.md)。原 Crash 原始堆栈与实时 mapping 详情保留。

## 历史页面验收（以下不代表当前配置）

2026-10-01：真实后端网页已核验 Performance 事件的本地重放：构建登记、READY 创建及 SUCCEEDED 结果展示通过，模型配置、未知费用和三处引用可见，成功截图见跨端记录。后续五例受控评估已完成，质量未达预期；云端分析部署仍未验证。

五例评估中有格式/引用校验失败，FAILED 的 result=null 代表没有有效结果；证据不足结论与执行失败仍需区分。本轮未改前端代码或重复浏览器验收，质量结果以跨端记录为准。

## 2026-10-02 分析面板对齐修正

分析标题复用外层卡片内边距，与历史按钮及正文保持同一左侧基线；任务元信息复用 `meta-item` 样式，取消定义值的默认缩进，长任务 ID 可在列内换行。标题区允许换行，创建按钮不被压缩。真实本地后端页面已核对四组字段标签与值的左坐标一致，前端 120 项测试及类型检查通过。截图见[分析面板对齐效果](../../../docs/analysis-validation/panel-alignment.png)。


## 2026-10-02 宿主直接分析

新 READY 指令为“使用 apm-crash-analyze 分析任务 taskId”；旧 READY 冻结策略提示创建新任务。新报告展示宿主自报名称、模型未知、Python 工具版本与未知用量，历史 OpenCode/DeepSeek 报告仍按历史字段显示。

报告 SUCCEEDED 与宿主停止 UNKNOWN 可同时存在。页面分别展示工具关闭和宿主停止，管理员对成功报告也可填写实际停止核验依据；模型回答不能替代核验。未停止旧环境仍禁止新尝试。当前前端自动化和真实页面证据见[宿主验收](../../../docs/analysis-validation/host-analysis-validation.md)。
