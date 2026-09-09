# Android APM 前端知识库

本知识库是 `frontend/` 工程的中文维护入口，记录前端当前已经实现的页面、代码边界、接口契约、本地联调、测试与发布约束。平台级架构、后端协议、数据存储和 Grafana 仍以仓库根目录的[平台知识库](../../../docs/knowledge-base/README.md)为准。

## 当前状态

| 应用 | 状态 | 说明 |
|---|---|---|
| 工程形态 | 已落地 | 独立的 Vue 3 + TypeScript + Vite 单页应用 |
| 页面范围 | 登录、应用工作区、JVM Crash、卡顿和内存分析闭环 | 除管理和 Crash 页面外，已提供卡顿指标、问题列表、Issue 事件、单事件采样证据及 PSS/VSS/Java 堆内存指标页面 |
| 数据访问 | 已落地 | 统一 HTTP 客户端携带同源 Session Cookie 和 CSRF；已具备管理、Crash、卡顿及内存 summary/trend API 客户端，不直连 ClickHouse |
| 图表 | 已落地 | ECharts 展示 Crash/卡顿问题趋势、多算法指标趋势和 PSS/VSS/Java 堆趋势；原生 SVG 展示采样估算火焰图 |
| 视觉范围 | 已落地 | 浅色语义 Token、固定 1280px PC 设计基线；不承诺移动端适配 |
| 自动化验证 | 已覆盖关键行为 | Vitest、Vue Test Utils、`vue-tsc` 和 Vite 构建；页面、Store、路由、Crash API、卡顿状态/证据/应用切换、内存筛选和统计卡片均有测试 |
| 生产能力 | 部分完成 | 登录/应用成员授权已由后端强制执行；生产同源网关、HTTPS、静态资源部署和 OIDC 仍待实现 |

当前实现基线：2026-09-08。Crash 页面、卡顿指标/问题/Issue/事件证据页面和 PSS/VSS/Java 堆内存指标页面均已落地；卡顿详情质量字段已切换为 expected/parsed/missing。代码与已发布 API 是当前事实，本文档中的“目标”或“待确认”不代表已经实现。

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
- API 客户端：`src/api/crashApi.ts`、`src/api/jankApi.ts`、`src/api/memoryApi.ts`
- 认证/应用 API：`src/api/http.ts`、`src/api/authApi.ts`、`src/api/appApi.ts`
- 接口类型：`src/types/crash.ts`、`src/types/jank.ts`、`src/types/memory.ts`
- 用户/应用状态：`src/stores/pinia.ts`、`src/stores/session.ts`、`src/stores/apps.ts`
- 查询参数、应用切换、证据/图表布局与格式化：`src/utils/query.ts`、`src/utils/jankQuery.ts`、`src/utils/memoryQuery.ts`、`src/utils/jankNavigation.ts`、`src/utils/jankEvidence.ts`、`src/utils/jankMetricChart.ts`、`src/utils/format.ts`
- 构建和测试：`package.json`、`vite.config.ts`、`vitest.config.ts`

## 后端工程入口

后端位于仓库根目录的 `backend/`，内部实现与构建测试见[后端知识库](../../../backend/docs/knowledge-base/README.md)。整体架构仍以[平台知识库](../../../docs/knowledge-base/README.md)为准；前端继续只消费根目录 API 契约。
