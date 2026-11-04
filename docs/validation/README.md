# 跨端验收资料

本目录保存可复用的独立验收证据。记录中的结果只描述执行时的版本、环境和样本，不代表重新验收或生产能力；测试方法见[平台测试](../knowledge-base/08-测试与质量保障.md)，当前环境以[平台状态](../knowledge-base/00-当前实现与验证边界.md#环境最近核验)为准。

| 证据 | 范围与限制 |
|---|---|
| [Agent 查询链路](agent-query-e2e-validation.md) | 2026-09-29 本地双应用、Inspector、网页及云端入口冒烟；后续有效 Token 云端验证见下行 |
| [接口正确性云端验收](backend-api-cloud-validation.md) | 2026-10-05 JDBC、ClickHouse、Agent/MCP 和真实页面的有限合成样本；历史数据及备份已重置 |
| [单事件分析验收索引](analysis/README.md) | 当前宿主流程、云端控制面、Skill 交付及历史模型质量证据 |

固定样本和人工计算结果见[固定数据集](../datasets/README.md)；Crash、卡顿及报告的查询计划与性能证据由[后端知识库](../../backend/docs/knowledge-base/README.md)维护。

返回[项目文档导航](../README.md)。
