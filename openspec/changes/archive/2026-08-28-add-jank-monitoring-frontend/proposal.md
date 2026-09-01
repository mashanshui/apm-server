## Why

卡顿监控服务端已经提供卡顿 Issue、事件详情、场景 FPS 和设备日挂起率查询，但当前 Vue 控制台仅覆盖 JVM Crash，用户无法通过产品页面完成卡顿指标观察、问题定位和采样证据下钻。与此同时，现有主规格要求提供 FPS 与挂起率趋势，实际 API 仍只返回查询范围汇总，必须在前端落地前补齐稳定的时间桶契约。

## What Changes

- 在现有浅色 PC 控制台中增加卡顿监控导航，并提供指标分析、卡顿问题列表、Issue 详情和卡顿个例详情页面。
- 提供 FPS 与设备日挂起率的汇总、时间趋势和白名单多维分析，正确区分无数据、无有效记录和前台分母不足。
- 提供卡顿总览、趋势、Issue 排行、Issue 个例列表和事件详情下钻，并沿用 URL 筛选、请求取消、游标分页和 Session 项目授权模式。
- 在卡顿个例页展示精确消息耗时、采样覆盖/空洞、采集质量、时间片、估算调用树和估算火焰图；任何采样推导值都不得展示为精确方法耗时或 CPU 自耗时。
- 补齐 FPS 按 `hour`/`day`、设备日挂起率按 UTC `day` 返回时间桶的服务端查询契约、存储适配和自动化测试。
- 项目切换时保持当前卡顿分析模块的稳定入口并重新加载目标项目数据，禁止复用旧项目筛选结果或事件详情。
- 同步前端知识库、平台查询文档、API 文档和测试验收边界；不包含移动端适配、Issue 负责人/状态/标签、操作日志、现场数据、版本对比或导出功能。

## Capabilities

### New Capabilities

- `jank-monitoring-frontend`: 定义卡顿指标分析、问题列表、Issue/个例下钻、精确与估算证据展示以及页面查询状态和安全边界。

### Modified Capabilities

- `jank-monitoring-server`: 明确 FPS 与设备日挂起率趋势的时间桶 API、粒度、状态、有效记录数和设备日聚合口径。
- `desktop-web-console`: 增加卡顿监控项目导航，并要求从卡顿页面切换项目时进入目标项目的对应卡顿稳定入口且不串用旧数据。

## Impact

- 前端：`frontend/src/router/`、`frontend/src/components/`、`frontend/src/composables/`、`frontend/src/api/`、`frontend/src/types/`、`frontend/src/utils/`、`frontend/src/views/`、全局样式及相关测试。
- 服务端：卡顿指标 Controller、查询服务、领域响应、仓库接口、内存实现、ClickHouse SQL 与相关测试；现有上传协议和移动端字段不变。
- API：在 `/api/v1/projects/{projectId}/jank-metrics` 下补充版本化兼容的趋势查询，不改变现有汇总和多维接口语义。
- 文档：前端知识库、根知识库导航与查询页、卡顿服务端 API、固定数据集和测试说明。
- 依赖：继续使用现有 Vue 3、TypeScript、Vue Router、Pinia、ECharts 和 Vitest，原则上不新增运行时依赖。
