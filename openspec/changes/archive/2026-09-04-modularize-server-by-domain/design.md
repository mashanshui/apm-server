## Context

参见 [proposal.md](proposal.md) 的动机说明。当前工程是单 Gradle 项目和单 Spring Boot 进程，管理数据由 PostgreSQL/JPA/Flyway 保存，事件与指标由内存或 ClickHouse 适配器处理，前端作为独立 Vue 工程通过 HTTP API 访问后端。

当前顶层 Java 包按技术层划分，主要结构性耦合包括：

- 查询应用服务直接接收 `web.QueryParams`，应用层依赖传输层。
- Repository 引用 Service 中的查询异常和质量指标，适配器依赖上层实现。
- `CurrentUserResponse` 引用安全实现类型，响应模型依赖认证适配器。
- `EventEnvelope` 和 `StoredEvent` 同时承载 Crash、结构化卡顿、FPS 与挂起率载荷。
- `CrashEventProcessor`、`CrashEventValidator` 和 `CrashQualityMetrics` 已实际承担多个信号的职责。
- `ClickHouseEventRepository` 同时负责 HTTP 传输、SQL、编解码、通用写入、Crash、卡顿、指标查询和多表修复。

本次重构必须保持所有已发布 API、客户端协议、错误码、鉴权、配置键、数据库 schema、幂等语义和统计口径不变。项目仍处于首版阶段，可以直接整理内部代码，不保留仅服务旧内部构造签名的兼容层。

## Goals / Non-Goals

**Goals:**

- 形成可以通过自动化测试验证的无环模块依赖图。
- 让每个业务模块拥有自己的领域模型、应用服务、Web 适配器、存储端口和存储适配器。
- 将跨模块调用限制在小型公开 API，禁止其他模块直接引用内部包、JPA 实体和具体存储实现。
- 将公共接收协议与信号专属处理分离，使新增信号不再修改 Crash 核心类。
- 将 ClickHouse 低层传输能力与 Crash、卡顿、指标业务 SQL 分离。
- 采用可逐步验证、可逐批回退的迁移顺序，避免一次移动全部代码后集中修复。

**Non-Goals:**

- 不改变 HTTP 路由、请求响应、媒体类型、Schema 版本、错误语义或鉴权策略。
- 不修改 PostgreSQL Flyway 迁移、ClickHouse 表结构、数据保留策略或已有数据。
- 不拆 Gradle 子项目、独立进程或微服务，不引入 Kafka、对象存储、RPC 或异步事件总线。
- 不同时重构 Vue 前端目录，不引入 OpenAPI 代码生成。
- 不抽象尚未实现的 ANR、Native Crash、网络、告警和后台任务插件框架。

## Decisions

### 1. 先建设逻辑模块，再决定物理模块

后端保持一个 Gradle 项目和一个 `bootJar`，以 Java 包和自动化规则形成模块边界。目标结构如下：

```text
com.shanshui.apmserver
├─ ApmServerApplication                 # 组合根
├─ bootstrap.internal                  # 跨模块 Spring 装配
├─ identity
│  ├─ api                              # 应用身份、权限和 App Key 用例
│  └─ internal                         # auth/app/domain/web/persistence/config
├─ telemetry
│  ├─ api                              # 稳定公共事件元数据和写入结果
│  └─ internal                         # 不承载信号专属载荷
├─ ingest
│  └─ internal                         # web/protocol/application/config
├─ crash
│  ├─ api                              # 接收模块可调用的 Crash/app_start 用例
│  └─ internal                         # domain/application/web/persistence/config
├─ jank
│  ├─ api                              # 接收模块可调用的指标用例
│  └─ internal                         # artifact/metrics/query/domain/web/persistence/config
└─ platform
   └─ internal                         # web/clickhouse/observability 等技术能力
```

`bootstrap` 只负责组合各模块和全局 Spring Security/Web 装配，不包含业务逻辑。`telemetry` 是窄共享内核，只允许保存已经稳定且被多个信号共同使用的事件元数据、应用标识引用和写入结果，禁止放入 Crash、卡顿或响应 DTO。`platform` 只提供技术能力，不包含业务状态和业务分支。

选择逻辑模块而不是立即拆 Gradle 子项目，是为了先暴露和消除现有循环，在行为测试持续通过的条件下稳定 API；等逻辑依赖稳定后，物理拆分只应是机械迁移。备选方案是直接创建多个 Gradle 项目，但当前反向依赖和联合模型会迫使引入过大的 `core` 模块，暂不采用。

### 2. 固定允许的模块依赖图

允许的编译期依赖如下：

```text
bootstrap --> identity
bootstrap --> ingest
bootstrap --> crash
bootstrap --> jank
bootstrap --> telemetry
bootstrap --> platform

ingest ----> identity.api
ingest ----> crash.api
ingest ----> jank.api
ingest ----> telemetry.api
ingest ----> platform

crash -----> identity.api
crash -----> telemetry.api
crash -----> platform

jank ------> identity.api
jank ------> telemetry.api
jank ------> platform

identity --> platform
telemetry -> platform
platform --> no business module
```

跨模块只能引用目标模块的 `api` 类型。`internal` 包之间禁止直接依赖；JPA 实体、ClickHouse 行模型、Spring Controller、配置属性类和第三方 processor 类型均不得作为公开 API。

Crash 和卡顿查询 Controller 需要应用权限时调用 `identity.api.AppAccessControl`；接收入口调用 `identity.api.AppKeyAuthentication` 获得不含持久化实现的应用身份。备选方案是在每个 Controller 中直接引用成员仓储，但会复制授权规则并泄漏 Identity 内部模型，不采用。

### 3. Web DTO 在适配器边界转换为应用命令

`QueryParams`、批次 JSON 和 ZIP 请求信息保留为 Web/协议适配器模型，不再传入应用服务。Controller 将其映射为 `CrashQuery`、`JankQuery`、`MetricQuery` 或接收命令；应用服务返回模块公开的结果 DTO，再由 Controller 返回 HTTP 响应。

查询时间范围、分页、游标和维度校验可以复用无 Spring/Web 依赖的值对象或小型策略，但不建立覆盖所有未来查询的通用框架。Crash 与卡顿统计语义继续分别归属各自模块。

这样可以消除 `service -> web` 反向依赖，也避免应用服务修改可变的 Web 参数对象。备选方案是只把 `QueryParams` 移到 `common`，虽然能隐藏反向依赖，但仍会让传输模型成为跨域 API，不采用。

### 4. 公共接收只负责编排，信号模块负责专属规则

`ingest` 模块继续拥有 `/ingest/v1/batches`、gzip/请求大小限制、JSON 解析、批次级 packageName 校验、事件级错误汇总和 accepted/rejected/duplicate 响应编排。它根据 `eventType` 显式调用：

- `crash.api`：处理 `crash` 与作为 Crash 分母的 `app_start`。
- `jank.api`：处理 `frame_scene_summary` 与 `foreground_suspension_summary`。
- 旧 `eventType=jank`：仍返回现有 `JANK_ARTIFACT_REQUIRED` 永久错误。

信号专属 Schema/字段/算法版本校验、脱敏、指纹、统计和领域映射分别迁入 Crash 或 Jank。批次服务负责汇总结果和一次仓储提交的现有语义；实现时不得把部分成功、重复计数或失败重试语义悄然改成逐事件事务。

备选方案是通过反射、自动扫描或通用插件注册器分派事件。当前事件类型固定且数量很少，显式分派更清晰，也符合首版不提前建设插件框架的原则。

### 5. 联合事件模型拆为公共元数据和信号专属记录

先提取不含信号载荷的 `EventMetadata`，包含 `appId`、`packageName`、事件标识、发生/接收时间、Schema、版本、构建、环境、设备匿名维度和网络等稳定字段。信号模块分别拥有：

- `CrashEvent` 与 `AppStartEvent`。
- `JankEvent`、`FrameSceneSummary` 与 `ForegroundSuspensionSummary`。

`EventEnvelope` 只作为 ingest 内部传输 DTO。`StoredEvent` 在迁移期间可以短暂作为旧适配器桥接类型，但不得新增兼容构造器；所有消费者迁移完成后删除。共享 `measurements`、`attributes` 是否保留由现有契约决定，但不得借本次重构新增字段或改变脱敏。

备选方案是保留一个带多个可空载荷的统一记录。它简化当前存取，却会让新增任一信号都修改所有模块和仓储，因此不采用。

### 6. 存储端口由业务模块拥有，ClickHouse Client 只处理技术传输

目标端口按用例拆分：

- Crash：接收写入、原始/详情读取和 Crash 查询端口。
- Jank：产物事实/详情写入、Issue/事件查询和指标聚合查询端口。
- Telemetry：确有跨信号需求时，仅提供通用原始事件行或幂等结果的窄端口。

`platform.internal.clickhouse` 提供连接参数、HTTP 执行、认证、超时、响应错误映射和 JSONEachRow 基础编解码，不包含表名、事件类型判断或业务 SQL。Crash 与 Jank 模块各自拥有表 SQL、行映射和结果转换。

卡顿写入需要同时维护原始事件、事实、详情和既有修复逻辑，因此由 `JankWriteCoordinator` 在 Jank 模块内统一控制顺序与质量指标；不能把多表写入拆成互不知情的 Bean。当前 ClickHouse 不具备跨表事务，本次只保持已有失败与修复语义，不宣称获得原子性。

内存实现与 ClickHouse 实现都实现同一业务端口，通过 Spring 条件装配选择。应用服务不得自行 `new InMemory...Repository`，非 Spring 测试改为直接注入端口替身或测试工厂，从而删除只为旧测试签名存在的重载构造器。

### 7. 配置和指标按所有者拆分，外部键保持不变

内部配置类拆为公共接收限制、Crash 接收策略、Jank 接收策略、Jank 指标策略、Stack Parser、查询限制、Identity/Auth 和 ClickHouse 连接配置。为避免客户端及部署变更，已有 `apm.ingest.*`、`apm.query.*`、`apm.stack-parser.*` 等外部配置键保持不变，可通过多个 `@ConfigurationProperties` 绑定同一前缀的不同字段或由 bootstrap 映射为模块配置。

`CrashQualityMetrics` 拆为公共接收、Crash 和 Jank 指标记录器；Repository 依赖模块定义的观测端口，不依赖 Service 中的具体 Micrometer 组件。Micrometer 实现放在模块内部或 platform 适配器中，指标名称和标签保持不变。

### 8. 架构门禁使用可验证规则，而不是只依赖目录约定

实现阶段先验证 Spring Modulith 与 Spring Boot 4.1 的正式兼容组合。兼容且不引入多余运行时能力时，使用其模块模型验证无环依赖、公开 API 访问和允许依赖；否则使用测试作用域 ArchUnit 编写同等规则。无论选用哪种工具，以下门禁必须进入 `gradlew test`：

- 业务模块依赖图无环且符合本设计白名单。
- 跨业务模块只能访问 `..api..`。
- `..internal..` 不允许被其他模块引用。
- 应用/领域包不得依赖 `..web..`。
- 持久化适配器不得依赖 Web 或具体应用服务实现。
- `platform` 与 `telemetry` 不得依赖 Crash、Jank、Ingest 或 Identity 内部包。

工具选择不改变模块结构和任务边界。Spring Modulith 不是生产运行时必需能力，不能为了采用它而引入事件发布、事件持久化等本次不需要的机制。

### 9. 测试按模块归属迁移，行为契约作为回归基线

单元测试跟随对应模块包移动；HTTP 集成测试、数据库集成测试和固定数据集测试保留在能够启动完整应用的测试范围。测试不得继续通过兼容构造器跨过模块 API，应使用公开用例、端口替身或模块测试工厂。

每个迁移批次先保持既有测试通过，再删除旧包和桥接代码。重点回归：

- JSON/gzip 批次部分成功、错误聚合、重复和存储失败。
- App Key、packageName 绑定、Session、CSRF 和成员授权。
- Crash 分母、趋势、Issue、详情和数据源状态。
- 卡顿 ZIP v3、mapping、processor、accepted/duplicate、事实/详情修复。
- FPS、设备日挂起率、多维查询、算法隔离和查询限制。
- PostgreSQL schema、ClickHouse SQL 资源和完整应用启动。

## Risks / Trade-offs

- [大规模移动导致评审困难或遗漏 Spring Bean] → 按模块和垂直链路分批迁移，每批运行定向测试及完整测试，禁止最后一次性修复所有包。
- [为消除联合模型而改变存储或协议语义] → 先建立特征测试和端口契约测试，模型转换保持现有字段、空值、顺序、去重和错误行为。
- [共享内核再次膨胀为 `common`] → `telemetry.api` 只接受至少两个信号已经共同使用且稳定的类型；任何信号专属字段留在所属模块。
- [拆分 Repository 后破坏卡顿多表一致性修复] → 保留单一 `JankWriteCoordinator`，用现有失败分支和真实固定数据集验证写入顺序与修复。
- [架构工具与 Spring Boot 版本不兼容] → 架构规则是交付目标，Spring Modulith 只是可选实现；不兼容时使用 ArchUnit，不阻塞重构。
- [测试为了包移动增加大量公开 API] → 测试优先走业务公开用例或包内测试工厂，不因测试把内部类暴露为跨模块 API。
- [单进程模块仍共享 JVM 和数据库资源] → 接受当前部署简化；资源隔离和独立扩缩容继续由容量数据触发后续服务拆分。

## Migration Plan

1. 建立架构特征测试和当前依赖基线；加入允许依赖规则时先以待修复违规清单验证规则准确性，再转为强制失败门禁。
2. 创建 `platform`、`telemetry` 与 `identity.api` 的最小边界，迁移 Identity/Auth/App 管理垂直链路及其测试，清除领域响应对安全实现的依赖。
3. 创建 Crash 模块，迁移 Crash/app_start 模型、校验、脱敏、指纹、统计、查询、Web 和存储端口；保持现有 ClickHouse 适配器桥接。
4. 创建 Jank 模块，先迁移指标查询，再迁移 ZIP 解析、mapping、证据映射、指纹和写入协调；保留 processor 一次解析和 Semaphore 行为。
5. 将批次 Controller、协议 DTO、请求限制和结果汇总迁移到 Ingest；以显式分派调用 Crash/Jank API，删除 `CrashEventProcessor` 对卡顿职责。
6. 提取无业务 SQL 的 ClickHouse Client，分离 Crash/Jank/指标存储适配器和 SQL；迁移内存适配器，删除具体类型判断与测试回退构造器。
7. 拆分配置和指标所有权，在完整测试与配置绑定测试通过后删除旧 `domain/service/repository/web/config/security` 包中的残留类型。
8. 更新知识库、架构决策和测试边界，检查 API 与客户端接入文档无契约变化；运行完整构建、严格 OpenSpec 校验和 Markdown 链接检查。

本次只有代码结构和测试依赖变化，不涉及数据库迁移或分阶段线上数据切换。每一步均应保持可构建；若某批迁移失败，回退该批包移动和适配器桥接即可，不需要回滚数据库或客户端。部署仍使用同一 JAR，发布策略沿用当前方式。

## Open Questions

- 架构门禁最终采用 Spring Modulith 还是 ArchUnit，在实现第一步通过正式兼容性和最小依赖验证确定；两者必须满足相同验收规则。
- 逻辑模块稳定并经历至少一次新信号接入后，再评估是否另立变更拆成 Gradle 多模块。
