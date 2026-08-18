# 查询与 Dashboard

## 查询 API

当前已实现的 JVM Crash 查询接口如下：

```http
GET /api/v1/projects/{projectId}/crashes/overview
GET /api/v1/projects/{projectId}/crashes/trend?interval=hour|day
GET /api/v1/projects/{projectId}/crashes/issues
GET /api/v1/projects/{projectId}/crashes/issues/{fingerprint}/events
GET /api/v1/projects/{projectId}/crashes/events/{eventId}
```

查询支持 `from`、`to`（ISO-8601）、`appVersion`、`channel`、`environment`、`osVersion`、`deviceModel`、`fingerprint`、`limit`、`cursor` 和 `timeoutMs`。默认时间范围为最近 24 小时，最大范围 31 天；`limit` 默认 50、最大 500；`timeoutMs` 默认 2000、最大 5000；趋势粒度只支持 `hour` 和 `day`。服务端在进入查询服务前校验项目请求头，并在读取结果后的事件遍历中执行应用层时间限制；当前 ClickHouse HTTP 请求自身使用固定 10 秒超时，尚未把 `timeoutMs` 映射为数据库级查询超时。

原方案中的启动、网络、卡顿、项目管理、制品上传等路径仍是目标 API，不应当作当前已发布接口。当前没有独立的版本对比 API，Grafana 的版本对比由 Dashboard 直接查询 ClickHouse 原始表完成。

## Dashboard 清单

| Dashboard | 核心内容 | 主要筛选 |
|---|---|---|
| 全局总览 | 事件量、活跃设备、启动 p95、卡顿率、ANR 率、网络错误率 | 时间、项目、版本、渠道、环境 |
| 启动分析 | 冷/温/热启动趋势、分位数、阶段耗时、慢样本 | 版本、设备、系统、启动类型 |
| 网络分析 | 接口耗时、错误率、状态码、DNS/连接/TLS/TTFB 分解 | 域名、路径、网络、版本 |
| 卡顿分析 | 卡顿趋势、页面排行、帧耗时、受影响设备 | 页面、设备、系统、版本 |
| 版本对比 | 新旧版本 p95 变化、回归维度、影响范围 | 基准版本、目标版本、渠道 |
| 数据质量 | 上报延迟、拒绝量、Schema 分布、无数据告警 | SDK 版本、Schema、项目 |

## 全局变量

```text
project_id, app_version, channel, environment, os_version,
device_model, network_type, event_type, interval
```

时间范围使用 Grafana 自带时间选择器。当前 JVM Crash Dashboard 实际使用项目、版本、渠道、环境、Android 版本、设备型号和问题指纹变量；`network_type`、`event_type`、`interval` 仍是通用方案变量，不代表当前 Crash API 已提供对应筛选。

## Grafana 查询规则

- 使用专用 ClickHouse 只读账户，仅授权指定库表的 `SELECT`。
- ClickHouse 不暴露公网，Grafana 通过内网访问。
- 目标架构默认查询聚合表；当前 Dashboard JSON 直接查询 `apm_event_raw FINAL`，后端查询服务也通过仓库读取原始事件并在 JVM 内计算。
- 设置查询超时和最大返回行数。
- Dashboard JSON 进入 Git 管理。
- 多租户场景不能仅靠隐藏 `project_id` 变量隔离数据，应使用数据源隔离、行级策略或后端查询代理。

## Grafana 与 Vue 的边界

Grafana 适合趋势、排行、筛选、临时分析和告警；Vue 更适合项目/密钥管理、Crash/ANR 详情、堆栈反混淆、问题归组、评论状态和完整多租户体验。

第一版不自研 Vue 页面，也不优先使用 `iframe` 嵌入 Grafana。产品化后 Vue 负责业务交互，Grafana 保留为内部分析和运维平台。

## 告警候选

- 启动耗时 p95 超过阈值。
- 网络错误率超过阈值。
- 某版本 ANR 率高于基准版本。
- 卡顿率在短时间窗口内明显增长。
- 连续一段时间没有收到数据。
- 上报拒绝率超过阈值。
- ClickHouse 磁盘使用率超过阈值。

阈值、评估窗口、通知渠道和恢复条件属于待确认的运维参数。

## JVM Crash 查询与 Dashboard 实现

已增加以下查询语义：Crash 总览、小时或天趋势、问题排行、按指纹的事件列表和单事件详情。查询支持版本、渠道、环境、Android 版本、设备型号和指纹筛选，并限制时间范围、limit、游标和超时。比例使用 `app_start` 会话分母；分母为空时返回 `null` 和 `denominator_insufficient` 状态；没有任何事件时状态为 `no_data`。

Grafana JSON 位于 `src/main/resources/grafana/dashboards/jvm-crash.json`，包含总览卡片、趋势、问题排行、版本对比、状态说明和下钻链接，当前数据源类型为 `vertamedia-clickhouse-datasource`。Dashboard 已在 ClickHouse 26.7.3.19 与 Grafana 13.1.3 实例上用固定数据集完成截图验收，截图和期望值记录见 [`docs/grafana-screenshot-check.md`](../grafana-screenshot-check.md)；该验收不等同于多租户生产权限或大规模性能验收。
