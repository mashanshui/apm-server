## Why

当前服务端只具备 `app_start` 与 JVM 致命 Crash 的接收、存储和查询能力，尚不能接收或分析 Android 卡顿个例、场景帧率和设备挂起率数据。先冻结并实现服务端协议与分析接口，可以用固定数据集完成独立验收，并为后续 Android SDK 对接提供稳定契约。

## What Changes

- 扩展现有 JSON/JSON+gzip 批量上报协议，新增卡顿个例、场景帧指标汇总和前台挂起汇总三类服务端载荷。
- 对卡顿载荷实施版本、时间、字段、堆栈、体积和隐私校验，保持现有批次部分接受、可重试错误和 `projectId + eventId` 去重语义。
- 明确区分主线程消息精确总耗时与采样推导的估算堆栈耗时，并携带算法版本、采样间隔和采集质量信息。
- 在 ClickHouse 增加卡顿事件、堆栈详情、场景帧指标、设备挂起区间及必要聚合对象；高频统计由 ClickHouse 完成，不复制当前 Crash 的 JVM 全量扫描查询路径。
- 由服务端生成版本化卡顿 Issue 指纹，提供总览、趋势、Issue 排行、Issue 个例列表和单个卡顿事件详情查询。
- 提供场景 FPS 与设备日挂起率的趋势、分位数和多维筛选查询，并明确无数据、无有效分母和字段有效上报数语义。
- 提供中文接口文档、JSON Schema、请求示例、错误码、固定数据集和人工可核对的期望统计，供移动端后续接入。
- 按阶段实施：先交付卡顿个例与 Issue 查询最小闭环，再交付 FPS 和挂起率；本变更不包含 Android 采集、btrace 修改、Vue/Grafana 页面、告警、mapping 符号化、Protobuf、Kafka 或生产发布。

## Capabilities

### New Capabilities

- `jank-monitoring-server`: Android 卡顿个例、场景帧指标和设备挂起汇总的服务端接收、校验、存储、聚合、Issue 归组与查询契约。

### Modified Capabilities

无。

## Impact

- Spring Boot：影响公共事件信封、接收编排、校验与脱敏、卡顿指纹、查询控制器和查询服务。
- ClickHouse：新增版本化卡顿 schema、详情与聚合对象，并扩展 HTTP JSONEachRow 存储和聚合查询适配器。
- API：扩展 `/ingest/v1/batches` 支持的事件类型，新增 `/api/v1/projects/{projectId}/janks/*` 与 `/api/v1/projects/{projectId}/jank-metrics/*` 查询接口。
- 权限：Android 上报继续使用项目 Key；查询继续使用 Spring Security Session 和项目成员授权，不信任客户端项目身份请求头。
- 文档：同步更新 `docs/knowledge-base/`，新增卡顿上报协议、查询 API、错误码、固定数据集和联调说明。
- 测试：新增协议、校验、脱敏、去重、指纹、ClickHouse schema、聚合口径、跨项目隔离、固定数据集及真实 ClickHouse 冒烟验证。
