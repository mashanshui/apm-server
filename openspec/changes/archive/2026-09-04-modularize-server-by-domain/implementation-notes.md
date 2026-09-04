# 实施记录

## 2026-09-03 基线

- 构建命令：仓库内 `GRADLE_USER_HOME=.gradle-local`，显式使用 Maven Local，运行 `gradlew.bat test bootJar --no-daemon`。
- 构建结果：成功。
- 自动化测试：39 个测试类，131 个测试，0 失败，0 错误，1 跳过。
- 可执行产物：`build/libs/apm-server-0.0.1-SNAPSHOT.jar`。
- OpenSpec：`openspec validate modularize-server-by-domain --strict --no-interactive` 通过。
- 环境说明：默认 `E:\AndroidSDK\.gradle` Wrapper 锁文件无访问权限，因此使用仓库内缓存；这不修改项目运行配置。

## 架构门禁选型

- Spring Modulith 官方稳定兼容信息未清晰覆盖当前 Spring Boot 4.1.0；2.2 文档仍标记为里程碑版本。
- 选择 ArchUnit 1.5.0，仅作为测试依赖，不引入生产运行时能力。
- 迁移期间先保留已知旧分层违规断言，新模块从创建起执行目标依赖白名单和 `internal` 隔离规则；收口阶段删除旧违规断言并转为全量强制规则。

## 2026-09-04 收口验证

- 完整构建：使用仓库内 `GRADLE_USER_HOME=.gradle-local` 和显式 Maven Local 执行 `gradlew.bat clean build --no-daemon`，45 个测试类、145 个测试、0 失败、0 错误、0 跳过；可执行 JAR 为 `build/libs/apm-server-0.0.1-SNAPSHOT.jar`。相较重构前 39 个测试类、131 个测试、1 个跳过，覆盖未减少。
- 架构边界：生产代码已从旧 `domain/service/repository/web/config/security` 顶层包迁空；ArchUnit 验证七个模块的依赖白名单、跨模块 `internal` 隔离、反向分层依赖和无环。
- 内存模式启动：以 `APM_STORAGE_MODE=memory`、关闭 ClickHouse/Flyway并使用仅限本次进程的 H2 启动构建产物，`GET /actuator/health` 返回 200/UP；认证、接收和 Crash 查询入口分别返回预期 401/403/401。验证后停止进程并确认 18080 端口释放。该结果只证明单进程装配与既有配置键有效，不证明生产 PostgreSQL 或长期稳定性。
- ClickHouse 固定数据集：临时启动仓库已有 PostgreSQL 与 ClickHouse 容器，`scripts/validate-jank-clickhouse.ps1` 在 ClickHouse 26.7.3.19 上通过；输入 11 条、唯一事件 9 条，原始表 `FINAL` 9 行，卡顿事实/详情各 3 行，Issue、FPS、挂起率和趋势均符合期望。完成后将两个容器恢复为原先停止状态。
- 真实 ZIP：`RheaStackAnalyzerIntegrationTests` 与 `StackArtifactParseServiceTests` 通过，覆盖 processor 报告关键字段、mapping、采样时间片、调用树和解析落库服务边界。
- 外部验证边界：未覆盖生产容量、并发 p95/p99、TTL 长期行为、故障恢复、Android 真机、生产 PostgreSQL 高可用或 processor 正式制品仓库。
- 文档同步：更新知识库总入口、当前实现边界、总体架构、存储、上报、测试、ADR 和 ClickHouse/Grafana 运维说明；检查 `docs/api/`、`docs/client-integration/` 与前端知识库，因 HTTP 契约、客户端流程和前端接口均未变化，无需修改其专项文档。Markdown 本地链接检查覆盖 203 个链接，断链 0 个。
- 最终门禁：`openspec validate modularize-server-by-domain --strict --no-interactive` 通过；`openspec validate --all --strict --no-interactive` 共 10 项通过、0 失败；`git diff --check` 通过，仅输出工作区既有的 LF/CRLF 转换提示。
