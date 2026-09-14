# 后端协作规则

本文件适用于 backend/ 及其子目录，与[根目录规则](../AGENTS.md)共同生效。共用开发原则、注释要求、提交规范、安全和 SSH 约定由根文件统一维护。下文工程路径相对仓库根目录。

## 项目结构与模块组织

后端是基于 Java 21 和 Spring Boot 4.1 的 Gradle 单模块服务。生产代码位于 `backend/src/main/java/com/shanshui/apmserver/`，配置与 Flyway 脚本位于 `backend/src/main/resources/`，测试代码位于 `backend/src/test/java/` 并保持相同包结构。`backend/build.gradle.kts` 管理插件与依赖，`backend/gradle/wrapper/` 和 `backend/gradlew*` 保证构建环境一致。

## 构建、测试与本地开发

优先使用后端自带的 Gradle Wrapper；以下 Windows 命令从仓库根目录执行：

- `./backend/gradlew.bat -p backend clean build`：清理产物、编译并执行全部测试。
- `./backend/gradlew.bat -p backend test`：运行 JUnit Platform 测试。
- `./backend/gradlew.bat -p backend bootRun`：本地启动 Spring Boot 服务。
- `./backend/gradlew.bat -p backend bootJar`：生成可执行 JAR，输出到 `backend/build/libs/`。

提交前至少运行 `./backend/gradlew.bat -p backend test`；涉及构建配置或依赖时运行完整 `clean build`。

## 编码风格与命名约定

Java 代码使用 4 空格缩进、UTF-8 编码，并保持现有 `com.shanshui.apmserver` 根包。类型使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。按职责组织包，例如 `controller`、`service`、`repository`、`domain` 和 `config`。控制器只处理协议与校验，业务逻辑放入服务层；依赖优先通过构造器注入。仓库尚未配置专用格式化器或静态检查器，修改时遵循 IDE 默认 Java 格式并清理无用导入。

## 测试指南

测试基于 JUnit 5 和 Spring Boot Test。测试类以 `Tests` 结尾，测试方法应描述行为，例如 `rejectsEventWithoutDeviceId()`。纯业务逻辑优先写快速单元测试；仅在需要完整容器时使用 `@SpringBootTest`。新增功能应覆盖正常路径、参数校验和关键失败分支。涉及 PostgreSQL 或 Flyway 的测试必须说明所需环境与初始化方式。

## 数据库迁移

数据库结构变更应新增版本化 Flyway 迁移，不得改写已发布迁移。

## 文档维护

开发前阅读[平台知识库](../docs/knowledge-base/README.md)与[后端知识库](docs/knowledge-base/README.md)及相关主题页。后端内部实现、构建、存储、安全和测试变更同步后端知识库；平台架构与跨端约定仍由根知识库维护。

HTTP 契约变化同步[API 文档](../docs/api/README.md)，客户端字段或接入流程变化同步[客户端接入文档](../docs/client-integration/README.md)，并按根规则检查其他受影响文档。
