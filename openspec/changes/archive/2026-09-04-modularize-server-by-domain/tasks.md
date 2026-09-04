## 1. 基线与架构门禁

- [x] 1.1 运行并记录当前 `./gradlew.bat test`、`./gradlew.bat bootJar` 与 `openspec validate modularize-server-by-domain --strict --no-interactive` 基线，确认重构前全部通过并保留测试数量与产物路径作为后续对照。
- [x] 1.2 核对 Spring Modulith 与当前 Spring Boot 4.1 的正式兼容性和最小依赖；若不能确认兼容则选用测试作用域 ArchUnit，并通过一个能扫描 `com.shanshui.apmserver` 的冒烟测试验证工具可执行。
- [x] 1.3 建立模块依赖、公开 API、`internal` 隔离、应用层禁止依赖 Web、持久化禁止依赖 Web/应用实现的架构测试，先生成并核对当前违规清单，确认规则能识别已知 `service -> web` 和 `repository -> service` 反向依赖。

## 2. Platform、Telemetry 与组合根

- [x] 2.1 创建 `platform`、`telemetry` 和 `bootstrap` 包骨架及包级说明，明确公开/内部边界，并通过架构测试确认 `platform` 不依赖业务模块、`telemetry` 不包含信号专属载荷。
- [x] 2.2 提取不依赖 Spring、Jackson、JPA 或 processor 的 `telemetry.api.EventMetadata` 和写入结果类型，补充值对象单元测试并验证现有事件公共字段能够无损映射。
- [x] 2.3 将全局 Spring 装配迁入组合根，保证业务逻辑不进入 bootstrap；运行 `ApmServerApplicationTests` 验证应用上下文仍能启动。

## 3. Identity 模块迁移

- [x] 3.1 定义 `identity.api` 的应用身份、App Key 鉴权和应用访问控制接口及最小 DTO，确保接口不暴露 JPA 实体、Repository、Spring Security Principal 或密钥持久化字段，并通过 API 包依赖测试验证。
- [x] 3.2 将用户、应用、成员、上报凭据、加解密与 PostgreSQL Repository 迁入 `identity.internal`，删除仅为旧包结构保留的内部兼容构造器，并运行 `AppInputValidatorTests`、`AppKeyAuthenticatorTests`、`AppKeyCryptoTests`。
- [x] 3.3 将登录、应用管理、Session 用户加载和授权实现迁入 Identity，改由 Identity API 向其他模块提供能力；运行 `AuthenticationApiIntegrationTests`、`AppAuthorizationTests`、`AppApiIntegrationTests`。
- [x] 3.4 将用户和应用响应映射与安全 Principal 解耦，确保领域/响应类型不再引用 `security` 实现，并通过架构测试及 `BootstrapAdminInitializerTests` 验证。
- [x] 3.5 验证应用创建事务、包名唯一性、Owner 成员和 App Key 同事务语义未变化，运行 `AppCreationAtomicityIntegrationTests` 与 `ManagementSchemaIntegrationTests`。

## 4. Crash 模块迁移

- [x] 4.1 创建 Crash 专属接收命令、`CrashEvent`、`AppStartEvent`、查询条件和响应模型，将 Crash/app_start 字段从联合模型映射到专属类型，并通过 `CrashValidationServiceTests` 与新增映射测试验证字段、空值和时间语义不变。
- [x] 4.2 将 Crash 校验、脱敏、指纹和质量统计迁入 `crash.internal`，移除对卡顿分析、卡顿指纹和卡顿算法配置的依赖，并运行 `CrashFingerprintServiceTests`、`CrashSanitizerTests`、`CrashReliabilityTests`。
- [x] 4.3 定义 Crash 写入、查询和详情存储端口，使应用服务只依赖端口而不依赖具体 ClickHouse/内存实现，并通过端口契约测试验证 accepted/duplicate、存储失败和事件查询语义。
- [x] 4.4 将 Crash 查询参数转换放到 Crash Web 适配器，应用服务接收不可变查询命令且不修改 Web DTO；运行 `CrashApiIntegrationTests` 和 `CrashDatasetStatisticsTests` 验证总览、趋势、Issue、分页、详情及分母口径。
- [x] 4.5 将 Crash Controller、错误映射和相关测试迁入 Crash 模块包，通过 MockMvc 回归确认现有路径、状态码和 JSON 响应完全不变。

## 5. Jank 模块迁移

- [x] 5.1 创建 `JankEvent`、`FrameSceneSummary`、`ForegroundSuspensionSummary`、卡顿查询命令和指标查询命令，将对应载荷迁入 `jank.internal.domain`，运行 `JankSchemaContractTests` 与新增无损映射测试。
- [x] 5.2 将 FPS、设备日挂起率、多维趋势和查询白名单迁入 Jank Metrics 边界，移除应用服务对 ClickHouse SQL 构造器及 Web `QueryParams` 的引用，运行 `JankMetricsQueryServiceTests`、`JankMetricsQuerySqlTests`、`JankMetricsPerformanceTests`。
- [x] 5.3 将卡顿 Issue、事件、详情和统计查询迁入 Jank Query 边界，改为依赖 Jank 查询端口和不可变命令；运行 `JankQueryServiceTests`、`JankQuerySqlTests`、`JankApiIntegrationTests`。
- [x] 5.4 将 ZIP v3 预检、processor 调用、mapping、报告映射、指纹和采样证据迁入 `jank.internal.artifact`，保持一次解析、Semaphore、临时文件清理和 packageName 前置拒绝，运行 `StackArtifactParseServiceTests`、`JankArtifactReportMapperTests`、`AppStackMappingResolverTests`。
- [x] 5.5 建立 `JankWriteCoordinator` 统一协调原始事件、事实、详情、重复检测和修复，运行 `JankIngestionTests`、`JankIngestionApiIntegrationTests`、`JankQualityMetricsTests` 验证多表失败与修复语义。
- [x] 5.6 将卡顿 Controller、指标 Controller、ZIP Controller 和错误映射迁入 Jank 模块，通过 `StackArtifactApiIntegrationTests`、`JankMetricsApiIntegrationTests`、`JankRequestLimitApiIntegrationTests`、`JankSecurityBoundaryTests` 验证公开契约不变。
- [x] 5.7 使用真实测试 fixture 运行 `RheaStackAnalyzerIntegrationTests`，确认 processor 报告关键字段、mapping、采样时间片、调用树和 accepted/duplicate 结果保持一致。

## 6. 公共 Ingest 编排迁移

- [x] 6.1 将批次请求、响应、事件错误、JSON Schema 预检、gzip 与受限输入流迁入 `ingest.internal.protocol/web`，运行接收请求大小、媒体类型、Schema 和 gzip 相关集成测试验证 HTTP 边界不变。
- [x] 6.2 实现公共批次编排，由 Controller 完成 App Key 鉴权和 packageName 整批校验，并显式调用 Crash 或 Jank API；通过混合有效/无效事件测试验证 accepted、rejected、duplicate 与事件索引保持一致。
- [x] 6.3 保留结构化 `eventType=jank` 的 `JANK_ARTIFACT_REQUIRED` 永久错误，将判断从 Crash Validator 移入 Ingest 分派边界，并运行对应客户端契约集成测试确认不落库。
- [x] 6.4 删除 `CrashIngestionService`、`CrashEventProcessor`、`CrashEventValidator` 中已经迁出的跨信号职责，通过编译、架构测试和全部接收链路测试确认不存在 Crash 对 Jank 内部包的依赖。

## 7. ClickHouse 与内存存储拆分

- [x] 7.1 从 `ClickHouseEventRepository` 提取无业务表名和事件判断的 ClickHouse HTTP Client、认证、超时及 JSONEachRow 基础能力，使用 HTTP 成功、非 2xx、超时和无效响应单元测试验证错误映射。
- [x] 7.2 将 Crash 原始事件、详情和查询 SQL/映射迁入 Crash ClickHouse 适配器，运行 `ClickHouseSchemaTests`、`CrashDatasetStatisticsTests` 和 Crash API 集成测试验证数据源标识与统计不变。
- [x] 7.3 将卡顿事实、详情、Issue/事件查询 SQL 和修复逻辑迁入 Jank ClickHouse 适配器，运行 `ClickHouseJankRepositoryTests`、`JankQuerySqlTests` 和卡顿固定数据集测试验证 `FINAL`、筛选、重复和修复。
- [x] 7.4 将 FPS、挂起率、趋势和多维 SQL/映射迁入 Jank Metrics ClickHouse 适配器，运行 `JankMetricsQuerySqlTests`、`JankDatasetStatisticsTests` 与指标 API 测试验证算法隔离和状态口径。
- [x] 7.5 将内存仓储改为直接实现对应 Crash/Jank 业务端口，删除具体类型判断、运行时回退和服务层 `new InMemory...`；运行全部内存模式查询与固定数据集测试。
- [x] 7.6 删除旧 `EventRepository`/`ClickHouseEventRepository` 联合实现及 `StoredEvent` 桥接类型，使用 `rg` 确认无生产代码引用旧类型，并运行 `./gradlew.bat test` 验证完整回归。

## 8. 配置、可观测性与异常边界

- [x] 8.1 将 `IngestProperties` 拆成公共接收、Crash、Jank 与指标内部配置，同时保持现有外部配置键和值不变；新增配置绑定测试并运行 `StackParserPropertiesTests` 验证。
- [x] 8.2 将 `CrashQualityMetrics` 拆为公共接收、Crash 和 Jank 指标端口及 Micrometer 实现，保持现有 meter 名称和标签；使用 MeterRegistry 单元测试逐项核对计数器和计时器。
- [x] 8.3 将查询超时、存储不可用和业务异常放入所属模块 API/内部边界，拆分或组合全局异常翻译器，使 Repository 不再引用 Service/Web 类型；运行全部异常状态码集成测试并通过架构规则。

## 9. 收口与全量验证

- [x] 9.1 将生产和测试代码从旧 `domain/service/repository/web/config/security` 顶层包迁空，使用 `rg --files` 和架构测试确认无残留跨层入口、无未授权内部引用且模块依赖图无环。
- [x] 9.2 运行 `./gradlew.bat clean build`，确认全部单元/集成测试、架构门禁和 `bootJar` 通过，并核对测试数量未因迁移被意外减少。
- [x] 9.3 以 `APM_STORAGE_MODE=memory` 启动一次应用并检查健康端点和主要认证/接收/查询上下文装配，确认单进程启动方式及已有配置键有效。
- [x] 9.4 在可用的本地 PostgreSQL/ClickHouse 环境运行现有固定数据集与真实 ZIP 冒烟脚本，明确记录外部环境验证结果以及未覆盖的生产容量、并发、TTL 和故障恢复边界。
- [x] 9.5 更新 `docs/knowledge-base/02-总体架构与模块.md`、`00-当前实现与验证边界.md`、`08-测试与质量保障.md`、`10-架构决策记录.md` 及导航引用；逐项检查 `docs/api/`、`docs/client-integration/` 和前端知识库，确认无契约变化或同步必要说明，并运行 Markdown 本地链接检查。
- [x] 9.6 运行 `openspec validate modularize-server-by-domain --strict --no-interactive`、`openspec validate --all --strict --no-interactive` 和 `git diff --check`，确认规划、实现、文档与仓库格式检查全部通过后再提交归档评审。
