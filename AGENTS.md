# Repository Guidelines

## 项目结构与模块组织

本仓库采用前后端并列工程：`backend/` 是基于 Java 21 和 Spring Boot 4.1 的 Gradle 单模块服务，`frontend/` 是独立 Vue 工程。生产代码位于 `backend/src/main/java/com/shanshui/apmserver/`，配置与后续 Flyway 脚本放在 `backend/src/main/resources/`；测试代码按相同包结构放在 `backend/src/test/java/`。`backend/build.gradle.kts` 管理插件与依赖，`backend/gradle/wrapper/` 和 `backend/gradlew*` 保证构建环境一致。`Android_APM服务端与Dashboard技术方案.md` 是架构与功能设计参考。不要提交 `build/`、`.gradle/` 或 IDE 本地文件。

## 首版实施原则

- 第一版以尽快打通可运行、可验证的端到端最小功能闭环为首要目标，优先实现明确的当前需求，不为尚未确认的未来场景提前增加复杂度。
- 优先复用现有模块、数据模型和同步处理链路；除非当前验收目标无法满足，不提前引入消息队列、缓存、对象存储、微服务拆分、通用插件框架或多层抽象。
- API、事件和配置只保留当前功能必需的信息；能够由服务端可靠计算、从已有数据推导或由固定协议确定的字段，不要求调用方重复提供。诊断和扩展字段默认作为后续增强，不阻塞首版交付。
- 简化范围不能牺牲数据正确性、幂等、基本安全、关键失败处理和必要自动化测试。未进入第一版的增强项应明确记录到路线图或待确认事项，不在首版实现中隐式预埋。

## 项目知识库

`docs/knowledge-base/` 是从 `Android_APM服务端与Dashboard技术方案.md` 整理出的中文 Markdown 知识库，覆盖项目目标、架构、协议、数据、安全、测试和实施决策。总入口为 `docs/knowledge-base/README.md`，当前实现与验证层级统一查看 `docs/knowledge-base/00-当前实现与验证边界.md`。开发前后以总览导航为准。实际代码、数据库迁移和已发布 API 代表当前实现；如果与知识库不一致，必须记录差异并确认，不能静默覆盖。新增知识库文档必须使用中文，并从总入口维护链接。

### 文档分层

- `docs/knowledge-base/`：维护平台目标、架构边界、领域模型、可靠性、安全、测试、路线图、决策和待确认事项，不重复展开完整接口示例或客户端操作步骤。
- `backend/docs/knowledge-base/`：维护后端内部实现、模块边界、存储适配器、构建配置、安全实现、测试与后端专项性能基线；入口为 `backend/docs/knowledge-base/README.md`。整体架构与跨端约定仍由根知识库维护。
- `docs/api/`：统一维护服务端已经发布的 HTTP API 契约、请求与响应字段、统计口径和错误语义；入口为 `docs/api/README.md`。禁止在 `docs/` 根目录新增零散 API 文档。
- `docs/client-integration/`：统一维护面向 Android SDK、客户端开发和联调人员的事件构造、持久化、批量上传、重试和接入说明；入口为 `docs/client-integration/README.md`。禁止在 `docs/` 根目录新增零散客户端对接文档。
- `frontend/docs/knowledge-base/`：维护前端工程的页面、路由、状态管理、联调、测试和部署边界；平台正式 API 仍以 `docs/api/` 为准。
- `docs/` 根目录保留跨端固定数据集、共享数据库初始化、全栈部署、灰度和端到端验收材料；后端专属性能与数据库专项证据归后端知识库；通过上述入口页或相关主题页建立索引。

服务端 API 文档和客户端接入文档必须相互链接，但各自只维护所属视角，避免复制同一契约形成多个事实来源。移动或重命名文档时，必须同步更新知识库、前端知识库及其他 Markdown 引用，并检查本地链接目标不存在断链。

后续凡是改动影响已有实现、API、事件或数据模型、前端页面、配置、启动方式、测试验收、安全边界或部署运维，都视为可能影响知识库的改动，必须在同一任务中自动检查知识库归属：后端内部变更同步 `backend/docs/knowledge-base/`，跨端或平台架构变更同步 `docs/knowledge-base/`，前端内部变更同步 `frontend/docs/knowledge-base/`，不等待用户另行提醒。执行要求如下：

- 开始修改前阅读知识库总入口及相关主题页，确认当前实现与目标架构的差异。
- 完成代码或配置改动后，更新受影响的主题页、示例、测试说明和待确认事项；新增主题必须补充到所属知识库入口的导航；根 `docs/knowledge-base/README.md` 继续作为全仓库总入口。
- API 路由、请求响应、错误码或统计口径变化时，同步更新 `docs/api/`、相关测试和知识库主题页；客户端字段、队列、批量、重试或接入流程变化时，同步更新 `docs/client-integration/`。
- 更新知识库时只记录已经验证的事实；明确区分代码已实现、外部环境冒烟验证、自动化测试覆盖和目标/待确认事项。
- 最终汇报必须分别列出根知识库、后端知识库、前端知识库、API 文档和客户端接入文档的同步文件；如果判断某一类文档不受影响，也要明确说明检查结论。

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

## 提交与拉取请求

当前历史仅采用简短中文主题（如 `项目初始化`）。继续使用单一目的、祈使式或结果式主题，例如 `新增事件批量上报接口`；避免将格式化与功能修改混在同一提交。拉取请求需说明背景、主要变更、验证命令及结果，并关联相关 Issue。API 或配置变化应附请求示例、兼容性说明；Dashboard 可见变化应附截图。

## 安全与配置

禁止提交数据库密码、令牌或真实设备数据。敏感值通过环境变量或未跟踪的本地配置注入；提交示例配置时仅保留安全占位符。数据库结构变更应新增版本化 Flyway 迁移，不得改写已发布迁移。

## 云服务器 SSH 连接

用户已授权：后续项目任务需要访问此服务器时，Agent 可直接使用以下 SSH 配置连接，无需重复询问连接许可；远程操作应限定在当前任务已授权的范围内。

- 地址：`124.221.252.121`，端口：`22`，用户：`ubuntu`。
- 本机私钥路径：`C:\Users\shanshui\.ssh\apm_cloud`（PowerShell 中使用 `$env:USERPROFILE\.ssh\apm_cloud`）。私钥仅保存在本机，不得复制到仓库或输出其内容。
- ED25519 主机指纹：`SHA256:1lXqfjFvy8p/wDN7mHfRBZRH0wN5u2hecNjZ990nnfs`。
- 客户端公钥指纹：`SHA256:Ravp2ttYoJzC3yicVwT4LbqwL0DIsuci5ddMtaOV5T0`。
- 主机密钥已保存在本机 `~/.ssh/known_hosts`；必须保持主机密钥校验，指纹不匹配时停止连接并核实原因。

从本机 PowerShell 执行远程命令的示例：

```powershell
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -i "$env:USERPROFILE\.ssh\apm_cloud" ubuntu@124.221.252.121 "id; hostname"
```

2026-09-12 已验证免交互密钥登录成功，远程用户为 `ubuntu`，主机名为 `VM-0-4-ubuntu`。该验证仅覆盖 SSH 登录及基本系统信息读取，不代表项目已经部署或服务已验收；`sudo` 能力尚未验证。更换本机环境后，先检查私钥是否存在和主机信任记录是否已配置，不要自动覆盖或重新生成现有密钥。
