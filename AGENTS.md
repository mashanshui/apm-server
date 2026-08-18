# Repository Guidelines

## 项目结构与模块组织

本仓库是基于 Java 17 和 Spring Boot 4.1 的单模块服务。生产代码位于 `src/main/java/com/shanshui/apmserver/`，配置与后续 Flyway 脚本放在 `src/main/resources/`；测试代码按相同包结构放在 `src/test/java/`。`build.gradle.kts` 管理插件与依赖，`gradle/wrapper/` 和 `gradlew*` 保证构建环境一致。`Android_APM服务端与Dashboard技术方案.md` 是架构与功能设计参考。不要提交 `build/`、`.gradle/` 或 IDE 本地文件。

## 项目知识库

`docs/knowledge-base/` 是从 `Android_APM服务端与Dashboard技术方案.md` 整理出的中文 Markdown 知识库，覆盖项目目标、架构、协议、数据、安全、测试和实施决策。总入口为 `docs/knowledge-base/README.md`，开发前后以总览导航为准。实际代码、数据库迁移和已发布 API 代表当前实现；如果与知识库不一致，必须记录差异并确认，不能静默覆盖。新增知识库文档必须使用中文，并从总入口维护链接。

## 构建、测试与本地开发

优先使用仓库自带的 Gradle Wrapper；Windows 使用以下命令：

- `./gradlew.bat clean build`：清理产物、编译并执行全部测试。
- `./gradlew.bat test`：运行 JUnit Platform 测试。
- `./gradlew.bat bootRun`：本地启动 Spring Boot 服务。
- `./gradlew.bat bootJar`：生成可执行 JAR，输出到 `build/libs/`。

提交前至少运行 `./gradlew.bat test`；涉及构建配置或依赖时运行完整 `clean build`。

## 编码风格与命名约定

Java 代码使用 4 空格缩进、UTF-8 编码，并保持现有 `com.shanshui.apmserver` 根包。类型使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。按职责组织包，例如 `controller`、`service`、`repository`、`domain` 和 `config`。控制器只处理协议与校验，业务逻辑放入服务层；依赖优先通过构造器注入。仓库尚未配置专用格式化器或静态检查器，修改时遵循 IDE 默认 Java 格式并清理无用导入。

## 测试指南

测试基于 JUnit 5 和 Spring Boot Test。测试类以 `Tests` 结尾，测试方法应描述行为，例如 `rejectsEventWithoutDeviceId()`。纯业务逻辑优先写快速单元测试；仅在需要完整容器时使用 `@SpringBootTest`。新增功能应覆盖正常路径、参数校验和关键失败分支。涉及 PostgreSQL 或 Flyway 的测试必须说明所需环境与初始化方式。

## 提交与拉取请求

当前历史仅采用简短中文主题（如 `项目初始化`）。继续使用单一目的、祈使式或结果式主题，例如 `新增事件批量上报接口`；避免将格式化与功能修改混在同一提交。拉取请求需说明背景、主要变更、验证命令及结果，并关联相关 Issue。API 或配置变化应附请求示例、兼容性说明；Dashboard 可见变化应附截图。

## 安全与配置

禁止提交数据库密码、令牌或真实设备数据。敏感值通过环境变量或未跟踪的本地配置注入；提交示例配置时仅保留安全占位符。数据库结构变更应新增版本化 Flyway 迁移，不得改写已发布迁移。
