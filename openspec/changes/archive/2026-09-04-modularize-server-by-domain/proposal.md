## Why

当前后端已经形成应用身份、公共接收、JVM Crash、卡顿产物与指标查询等稳定职责，但代码仍集中在 `web`、`service`、`repository`、`domain` 等技术分层包中，并存在应用层依赖 Web DTO、仓储层依赖 Service 类型以及跨信号共享大模型等反向依赖。随着 ANR、Native Crash、网络和更多查询能力加入，这些耦合会放大修改范围，并阻碍按负载独立演进，因此需要先把单进程服务整理为边界可验证的模块化单体。

## What Changes

- 在保持一个仓库、一个 Spring Boot 进程和一个部署产物的前提下，按 `identity`、`telemetry`、`ingest`、`crash`、`jank`、`platform` 业务、窄共享内核与平台职责重新组织后端代码。
- 为业务模块定义最小公开 API 与内部实现边界，移除 Service 对 Web DTO、Repository 对 Service 实现类型以及领域响应对安全实现类型的反向依赖。
- 将公共批次接收编排与 Crash、卡顿指标的领域处理分离，保留现有 JSON/gzip、卡顿 ZIP、鉴权、幂等和错误响应契约。
- 将跨信号联合事件模型逐步收敛为公共事件元数据和信号专属模型，避免新增信号继续扩张单一 `StoredEvent`。
- 拆分 ClickHouse HTTP 基础能力、通用原始事件写入、Crash 存储、卡顿事实/详情存储和卡顿指标查询职责；卡顿多表写入与修复继续由卡顿模块统一协调。
- 拆分公共接收、Crash、卡顿与指标配置及可观测性职责，使模块只依赖自身需要的配置和指标端口。
- 增加自动化架构验证，阻止模块循环、跨模块访问内部实现和新增反向分层依赖；评估 Spring Modulith 与当前 Spring Boot 版本组合，不满足时使用 ArchUnit 实现同等门禁。
- 按业务域同步整理测试包；保持固定数据集、ClickHouse SQL、HTTP 集成测试和现有启动方式可继续验证。
- 本变更不拆分 Gradle 子项目或独立服务，不引入 Kafka、对象存储、异步任务、微服务通信或新的部署单元。

## Capabilities

### New Capabilities

无。本变更是保持外部行为不变的代码结构、依赖治理和测试门禁重构，已在变更元数据中声明跳过 delta specs。

### Modified Capabilities

无。现有 JVM Crash、卡顿监控、卡顿产物、应用管理、上报凭据和网页登录需求均保持不变。

## Impact

- 后端生产代码：`src/main/java/com/shanshui/apmserver/` 下的包结构、依赖方向、领域模型、应用服务、Web 适配器、存储端口与 ClickHouse 实现。
- 后端测试：测试包结构、构造方式、替身仓储和架构验证测试；既有行为测试断言原则上不变。
- 配置与资源：配置类按模块归属调整，但现有 `application.properties` 配置键、JSON Schema、PostgreSQL Flyway 迁移和 ClickHouse 表结构不变。
- 构建依赖：可能增加仅用于架构验证的测试依赖；不改变当前单 Gradle 项目和 `bootJar` 产物。
- 文档：同步更新知识库中的总体架构、当前实现边界、测试与质量保障、架构决策记录；由于外部契约不变，API 文档和客户端接入文档只做影响检查，不改写协议内容。
- 前端：本变更不调整 Vue 工程结构或页面行为；后端模块化完成后可另立变更进行前端 feature-first 整理。
