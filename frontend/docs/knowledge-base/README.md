# Android APM 前端知识库

本知识库是 `frontend/` 工程的中文维护入口，记录前端当前已经实现的页面、代码边界、接口契约、本地联调、测试与发布约束。平台级架构、后端协议、数据存储和 Grafana 仍以仓库根目录的[平台知识库](../../../docs/knowledge-base/README.md)为准。

## 阅读入口与工程边界

本页只维护导航和源码入口。页面能力与限制见[产品范围](01-产品范围与当前状态.md)和[页面交互](03-页面路由与交互.md)，前端验证方法与最近完整基线见[测试页](06-测试与质量保障.md)；跨端验收和本地/云端部署结论统一见[平台状态](../../../docs/knowledge-base/00-当前实现与验证边界.md)。

前端是独立的 Vue 3 + TypeScript + Vite 单页应用，通过统一 HTTP 客户端访问后端；采用浅色 PC 控制台、ECharts 图表与 SVG 采样估算火焰图。认证、数据访问和状态边界由[架构页](02-架构与代码组织.md)及[数据模型页](04-API数据模型与状态管理.md)维护。

## 知识导航

| 想了解的问题 | 阅读入口 |
|---|---|
| 前端目前负责什么、不负责什么 | [产品范围与当前状态](01-产品范围与当前状态.md) |
| 工程目录和运行时数据流如何组织 | [架构与代码组织](02-架构与代码组织.md) |
| 有哪些页面、路由和交互状态 | [页面、路由与交互](03-页面路由与交互.md) |
| 如何调用后端、维护类型和处理错误 | [API、数据模型与状态管理](04-API数据模型与状态管理.md) |
| 如何启动、准备数据并排查联调问题 | [本地开发与联调](05-本地开发与联调.md) |
| 测试覆盖了什么，改动后运行哪些命令 | [测试与质量保障](06-测试与质量保障.md) |
| 生产发布、安全边界和当前限制是什么 | [部署、安全与运行边界](07-部署安全与运行边界.md) |
| 单事件当前代码分析、有限修复及应用 Worker 如何交互 | [单事件本地分析](local-analysis.md) |
| 文档如何维护，还有哪些产品化工作 | [维护约定与待办](08-维护约定与待办.md) |

## 推荐阅读路径

- 新前端成员：本页 → [产品范围与当前状态](01-产品范围与当前状态.md) → [架构与代码组织](02-架构与代码组织.md) → [页面、路由与交互](03-页面路由与交互.md)。
- 接口联调：本页 → [API、数据模型与状态管理](04-API数据模型与状态管理.md) → [本地开发与联调](05-本地开发与联调.md)。
- 提交与验收：本页 → [测试与质量保障](06-测试与质量保障.md) → [部署、安全与运行边界](07-部署安全与运行边界.md)。

## 当前代码入口

- 应用入口：`src/main.ts`、`src/App.vue`
- 路由：`src/router/index.ts`
- 页面：`src/views/`
- 复用组件：`src/components/`
- 查询编排：`src/composables/`
- API 客户端：`src/api/crashApi.ts`、`src/api/jankApi.ts`、`src/api/memoryApi.ts`、`src/api/memoryLeakApi.ts`、`src/api/symbolApi.ts`
- 认证/应用 API：`src/api/http.ts`、`src/api/authApi.ts`、`src/api/appApi.ts`
- 查询 Token 管理：`src/api/queryTokenApi.ts`、`src/components/QueryTokenPanel.vue`、`src/types/queryToken.ts`
- 接口类型：`src/types/crash.ts`、`src/types/jank.ts`、`src/types/memory.ts`
- 用户/应用状态：`src/stores/pinia.ts`、`src/stores/session.ts`、`src/stores/apps.ts`
- 查询参数、应用切换、证据/图表布局与格式化：`src/utils/query.ts`、`src/utils/jankQuery.ts`、`src/utils/memoryQuery.ts`、`src/utils/jankNavigation.ts`、`src/utils/jankEvidence.ts`、`src/utils/jankMetricChart.ts`、`src/utils/format.ts`
- 构建和测试：`package.json`、`vite.config.ts`、`vitest.config.ts`

## 后端工程入口

后端位于仓库根目录的 `backend/`，内部实现与构建测试见[后端知识库](../../../backend/docs/knowledge-base/README.md)。整体架构仍以[平台知识库](../../../docs/knowledge-base/README.md)为准；前端继续只消费根目录 API 契约。

本地单事件分析代码入口：`src/api/analysisApi.ts`、`src/types/analysis.ts`、`src/components/CrashAnalysisPanel.vue`、`src/components/AnalysisAdminPanel.vue`。

## 维护原则

当前说明原位更新，不追加部署流水、逐轮计数或迁移进度；环境核验只在[平台状态](../../../docs/knowledge-base/00-当前实现与验证边界.md#环境最近核验)维护，前端验证方法见[测试页](06-测试与质量保障.md)。完整规则见[维护约定](08-维护约定与待办.md)。
