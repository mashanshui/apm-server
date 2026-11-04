# 单事件分析验收索引

本目录按阶段保存验收证据。当前实现、环境最近核验和工程完整基线分别见[平台状态](../../knowledge-base/00-当前实现与验证边界.md)、[后端测试](../../../backend/docs/knowledge-base/06-测试与质量保障.md)和[前端测试](../../../frontend/docs/knowledge-base/06-测试与质量保障.md)。文档整理不代表重新执行测试或更新环境状态。

## 当前流程与证据

当前方案由宿主直接读取当前工作区，明确授权后编辑和测试；Python 管理配置、冻结事件证据、Run、租约与报告。源码引用、修改和验证包含宿主自报，报告保存成功不等于修复有效或宿主已停止。操作说明只在 [Skill 安装与调用](../../../agent-skills/apm-crash-analyze/README.md)和 [Worker 运行说明](../../../agent-skills/apm-crash-analyze/worker/README.md)维护。

| 阶段 | 证据入口 | 验证边界 |
|---|---|---|
| 2026-10-04 宿主直接读取 | [宿主直接读取源码增量](current-code-validation.md#2026-10-04-宿主直接读取源码增量) | Python 0.4.0、版本 4 报告、受控修复及合成 API 页面；不是生产修复质量 |
| 2026-10-04 云端控制面 | [云端发布与验收](cloud-current-code-validation.md) | 任务、凭据、报告及真实页面的有限闭环；没有云端修复执行器，旧数据和备份已重置 |
| 2026-10-03 当前工作区模式 | [当前代码分析与本地修复](current-code-validation.md) | 受控修复与证据不足零修改；早期快照/补丁工具及文件数只属于该阶段 |
| 2026-10-02 宿主 Agent | [宿主直接分析](host-analysis-validation.md) | 当时固定提交材料、真实 Codex 报告与停止未知；不是当前 Python 工具集 |
| Skill 自然语言入口 | [Skill 验收](skill-validation.md) | 当时宿主加载、受控行为与真实任务 |
| Skill 安装与配置交付 | [交付验收](skill-package-validation.md)、[配置增量](current-code-validation.md#skill-本地配置增量验收) | 当时独立安装、配置保留及受控 HTTP；文件数和测试数按各执行阶段解释 |

## 历史质量证据

[原始闭环、五个受控样本及面板截图](historical-analysis-validation.md)完整保留原构建、事件、人工核验、格式/引用失败、测试数字和限制。质量结论只适用于该阶段，不能外推当前宿主流程或生产准确率。

返回[跨端验收资料](../README.md)或[项目文档导航](../../README.md)。
