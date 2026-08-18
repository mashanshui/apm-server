# Android APM 平台知识库

本知识库由《[Android APM 服务端与 Dashboard 技术方案](../../Android_APM服务端与Dashboard技术方案.md)》整理而成，用于把一份线性方案转换为可按主题检索、可持续维护的项目知识。

## 一句话方案

目标架构采用 **Spring Boot 模块化单体 + PostgreSQL + ClickHouse + Grafana + Docker Compose**，先打通 Android 批量上报、服务端校验与脱敏、ClickHouse 入库、Grafana 分析和告警的最小闭环；Kafka、Vue、微服务和 Kubernetes 按实际规模与产品需求引入。当前仓库已经落地的是 JVM Crash 的 Spring Boot 接收、校验、脱敏、指纹、去重、查询和 Grafana 资源；PostgreSQL、管理模块、正式认证和 Compose 部署仍属于目标架构。

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| 方案版本 | 已形成 | 原方案版本 1.0，更新于 2026-08-14 |
| 事件协议 | JVM Crash v1 已落地（开发闭环） | 当前仅接受 `app_start` 与 JVM fatal Crash；支持 JSON/JSON+gzip、字段校验、脱敏、指纹、部分接受和 `projectId + eventId` 去重，Protobuf 未启用 |
| 数据库模型 | ClickHouse schema 与 HTTP 适配器已落地 | 已提供原始表、Crash 明细表、小时聚合表和物化视图；当前查询服务仍读取原始事件后在 JVM 内计算，容量与聚合查询压测待确认；测试可切换内存模式 |
| 工程技术栈 | Crash 模块部分落地 | 当前使用 Spring Boot 4.1、Java 17、Spring MVC、Jackson 和 Java HTTP Client；PostgreSQL/JPA/Flyway、管理模块和正式安全组件尚未加入 |
| Dashboard | Crash Dashboard 资源与验收材料已落地 | 已提供 Grafana JSON、变量、趋势、排行、版本对比和下钻链接；JSON 使用 `vertamedia-clickhouse-datasource`，生产权限隔离仍待完成 |
| 部署运维 | 开发/本地联调就绪，生产部署待实施 | 当前无 Docker Compose、Nginx 或正式监控部署文件；服务默认 ClickHouse，测试配置使用内存仓库 |

当前实现基线：2026-08-18。构建验证使用仓库内 `.gradle-local` 的 Gradle 9.5.1 分发运行 `./gradlew.bat test`；真实 ClickHouse/Grafana 截图验收材料见 [Grafana 实例截图验收](../grafana-screenshot-check.md)，不等同于生产上线。

## 知识导航

| 想了解的问题 | 阅读入口 |
|---|---|
| 平台解决什么问题，第一版做什么 | [产品目标与范围](01-产品目标与范围.md) |
| 系统由哪些组件组成，边界如何划分 | [总体架构与模块](02-总体架构与模块.md) |
| 事件字段、存储分工和聚合口径是什么 | [事件模型与数据存储](03-事件模型与数据存储.md) |
| Android 如何上报，失败如何重试 | [上报协议与可靠性](04-上报协议与可靠性.md) |
| 查询 API 和 Dashboard 如何设计 | [查询与Dashboard](05-查询与Dashboard.md) |
| 如何处理登录、多租户、隐私和密钥 | [安全与隐私](06-安全与隐私.md) |
| 如何部署、监控、备份与扩容 | [部署与运维](07-部署与运维.md) |
| 需要覆盖哪些测试与验收场景 | [测试与质量保障](08-测试与质量保障.md) |
| 应按什么顺序开发与产品化 | [实施路线图](09-实施路线图.md) |
| 为什么这样选型，何时改变选型 | [架构决策记录](10-架构决策记录.md) |
| 缩写和核心概念是什么意思 | [术语表](11-术语表.md) |
| 常见设计疑问的简短答案 | [常见问题](12-常见问题.md) |
| 开工前还必须确认什么 | [待确认事项](13-待确认事项.md) |
| 去哪里核对框架与组件的官方说明 | [参考资料](14-参考资料.md) |

## JVM Crash 交付入口

- [Android 客户端 Crash 上传接入](../crash-client-integration.md)
- [Crash API 与统计公式](../crash-api.md)
- [错误码与部分接受语义](../crash-error-codes.md)
- [固定数据集与期望结果](../crash-fixed-dataset.md)
- [ClickHouse 本地初始化](../clickhouse-local.md)
- [Grafana/ClickHouse 安装与项目接入教程](../grafana-clickhouse-install.md)
- [灰度、开关与回滚](../crash-rollout.md)

## 推荐阅读路径

- 新成员：本页 → [产品目标与范围](01-产品目标与范围.md) → [总体架构与模块](02-总体架构与模块.md) → [术语表](11-术语表.md)。
- Android SDK 开发：本页 → [Android 客户端 Crash 上传接入](../crash-client-integration.md) → [事件模型与数据存储](03-事件模型与数据存储.md) → [上报协议与可靠性](04-上报协议与可靠性.md) → [安全与隐私](06-安全与隐私.md)。
- 后端开发：本页 → [总体架构与模块](02-总体架构与模块.md) → [上报协议与可靠性](04-上报协议与可靠性.md) → [查询与Dashboard](05-查询与Dashboard.md) → [测试与质量保障](08-测试与质量保障.md)。
- 数据与运维：本页 → [事件模型与数据存储](03-事件模型与数据存储.md) → [查询与Dashboard](05-查询与Dashboard.md) → [部署与运维](07-部署与运维.md)。
- 项目负责人：[产品目标与范围](01-产品目标与范围.md) → [实施路线图](09-实施路线图.md) → [架构决策记录](10-架构决策记录.md) → [待确认事项](13-待确认事项.md)。

## 维护约定

1. 原方案是知识来源，主题页是执行时的快捷入口；两者冲突时必须记录差异并确认，而不是静默覆盖。
2. 已确定的关键技术取舍写入[架构决策记录](10-架构决策记录.md)。
3. 未确定的容量、SLA、保留期限和多租户要求写入[待确认事项](13-待确认事项.md)。
4. API、事件字段或统计口径变更时，同时更新对应主题页、示例和测试要求。
5. 新增文档使用中文，标题应能直接表达要回答的问题。

## 当前代码入口

- 接收控制器：`src/main/java/com/shanshui/apmserver/web/IngestController.java`
- Crash 查询控制器：`src/main/java/com/shanshui/apmserver/web/CrashController.java`
- 事件处理与统计：`src/main/java/com/shanshui/apmserver/service/`
- 存储适配器：`src/main/java/com/shanshui/apmserver/repository/`
- ClickHouse 初始化脚本：`src/main/resources/db/clickhouse/001_crash_schema.sql`
- JVM Crash Schema：`src/main/resources/schema/crash-event-v1.schema.json`
