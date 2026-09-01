## Context

当前后端只有 Crash 接收与查询能力，没有 Spring Security、关系数据库或用户/项目管理模型；`ProjectAuthorizationService` 通过浏览器可自行构造的项目请求头做开发期校验。前端是 Vue 3 单页应用，路由直接进入 `/projects/:projectId/crashes`，共享状态以路由和页面 composable 为主，全局样式是深色且包含移动端媒体查询。参见 `proposal.md` 的变更动机，以及本变更的认证、项目管理、桌面控制台和 Crash 前端规格。

本设计跨越数据库、服务端安全、管理 API、前端路由和视觉系统，并会让 PostgreSQL 成为正常运行环境的必需依赖。现有 ClickHouse Crash 事件仍以字符串 `projectId` 作为数据隔离键，不能在迁移中改写已有事件。

## Goals / Non-Goals

**Goals:**

- 建立可用于实际授权的服务端身份与项目成员关系，并让所有网页项目 API 共用一条授权路径。
- 让登录、项目工作区、项目设置和既有 Crash 页面形成完整的 PC 产品路径。
- 使用一组可复用的浅色设计变量和表单、面板、表格、状态组件，确保 1280 至 1920 像素宽度下布局稳定。
- 让迁移、测试和本地启动方式明确可复现，避免把静态页面误认为已完成安全闭环。

**Non-Goals:**

- 不实现开放注册、找回密码、多因素认证、OIDC 或跨服务 JWT。
- 不实现组织/团队管理、成员邀请、角色编辑、App、上报 Key、mapping 或项目删除。
- 不为手机、平板或小于 1280 像素的窄屏设计响应式导航和内容重排。
- 不改变 Crash 统计公式、查询响应数据结构或 ClickHouse 表结构。

## Decisions

### 1. PostgreSQL 同时承载管理数据和持久化 Session

引入 PostgreSQL、Flyway、Spring Data JPA 和 Spring Session JDBC。Flyway 创建 `apm_user`、`project`、`project_member` 以及 Spring Session 所需表，JPA 只做运行时映射并使用 schema 校验，不自动修改表结构。

核心模型如下：

```text
apm_user
  id UUID PK
  email_normalized VARCHAR UNIQUE
  display_name VARCHAR
  password_hash VARCHAR
  status VARCHAR
  created_at / updated_at

project
  project_id VARCHAR(40) PK       # 公开且不可变，与 ClickHouse projectId 对齐
  name VARCHAR(100)
  description VARCHAR
  created_by UUID FK apm_user
  created_at / updated_at

project_member
  project_id VARCHAR FK project
  user_id UUID FK apm_user
  role VARCHAR                    # OWNER / ADMIN / DEVELOPER / VIEWER
  created_at
  PK(project_id, user_id)
```

选择 PostgreSQL 而不是新增内存管理仓库，是因为登录与项目管理必须跨重启保持一致，且项目知识库已经将用户、项目和权限关系定位在 PostgreSQL。选择 JDBC Session 而不是单机内存 `HttpSession`，是为了避免后续多实例时产生会话漂移。代价是正常启动新增数据库依赖，因此启动脚本、配置说明和回滚步骤必须同步调整。

### 2. 使用 Spring Security Session 与 SPA CSRF 模式

认证使用标准 Spring Security 认证主体、BCrypt 密码哈希和服务端 Session。Session Cookie 使用 HttpOnly；生产环境启用 Secure 与 SameSite，默认会话时长可配置。前端启动时先调用会话接口，同时取得可读的 CSRF Token Cookie；所有登录、退出、创建和修改请求通过请求头回传 CSRF Token。

认证接口采用 JSON，而不是框架默认 HTML 表单跳转：

```http
GET  /api/v1/session             # 当前用户；未登录返回 401，并可下发 CSRF Token
POST /api/v1/auth/login          # email, password
POST /api/v1/auth/logout         # 失效 Session，返回 204
```

所有认证失败统一返回稳定错误码和安全文案。前端只保存当前用户展示信息，不持久化密码、Token 或 Session 标识。选择服务端 Session 而不是 JWT，是因为当前是同源单体 Web 应用，Session 更容易撤销并能直接使用成熟的 CSRF 防护；OIDC/JWT 等到多服务或第三方登录成为明确需求后再引入。

由于本期没有注册页面，首次账号通过显式配置的引导管理员创建流程提供：仅当管理员邮箱和密码均由外部安全配置给出且目标邮箱不存在时创建账号，密码只参与哈希且不得进入日志；仓库不提供可工作的默认生产密码。

### 3. 项目管理 API 使用成员关系作为唯一授权事实

新增接口：

```http
GET   /api/v1/projects?query=
POST  /api/v1/projects
GET   /api/v1/projects/{projectId}
PATCH /api/v1/projects/{projectId}
```

项目创建在同一数据库事务内写入项目和 `OWNER` 成员关系。`projectId` 由用户输入，统一转为小写后按规格校验，创建后不可修改；冲突返回 `409 PROJECT_ID_CONFLICT`。列表查询在数据库层按当前用户成员关系过滤，不能先读取全部项目再由前端过滤。

读取项目需要任一成员角色；修改名称和描述需要 `OWNER` 或 `ADMIN`。无成员关系按 404 处理，已有成员但角色不足按 403 处理。现有 Crash Controller 继续从路径取得 `projectId`，但 `ProjectAuthorizationService` 改为读取认证主体并查询成员关系；`X-Project-Id` 和 `X-User-Project-Ids` 不再参与网页查询授权。Android 上报仍继续使用独立的 `X-Project-Key` 机制，本变更不把用户 Session 引入上报端。

### 4. 前端使用轻量全局 Session/Project Store

引入 Pinia 管理跨路由的当前用户、会话初始化状态、可访问项目列表和当前项目；Crash 查询结果仍保留在现有页面 composable 中。集中 API 客户端统一设置同源凭据、CSRF 请求头，并将 401 转换为单一的会话过期流程，避免各页面重复处理。

路由结构调整为：

```text
/login
/projects
/projects/new
/projects/:projectId/settings
/projects/:projectId/crashes
/projects/:projectId/crashes/issues/:fingerprint
/projects/:projectId/crashes/events/:eventId
/:pathMatch(.*)* -> NotFound
```

全局路由守卫只在首次导航时恢复一次会话。`redirect` 只接受同源站内路径，防止开放重定向。项目切换始终进入目标项目 Crash 总览，不把旧项目的事件 ID、问题指纹或筛选条件带入新项目。

### 5. 登录页与控制台使用两套共享外壳

登录页使用无侧栏的浅色认证外壳：左侧产品价值与平台能力摘要，右侧固定宽度登录卡片。受保护页面使用固定 PC 控制台外壳：浅色侧栏、顶部项目切换与用户菜单、主内容区。项目列表和创建页不选中具体项目导航；项目设置与 Crash 页面明确展示当前项目。

全局 CSS 改为浅色语义变量，例如页面背景、表面、边框、主文本、次要文本、主色、危险色、成功色和焦点环。移除现有移动端媒体查询，不设计汉堡菜单或卡片堆叠；页面设置 1280 像素 PC 设计基线，并在 1280、1440、1920 三个宽度完成视觉验收。表单标签、键盘焦点、错误关联和颜色对比仍按桌面端可访问性要求实现。

### 6. 测试分层覆盖安全与页面行为

- 后端单元测试覆盖凭据校验、项目标识规则和角色矩阵。
- Spring MVC 安全测试覆盖登录、CSRF、Session 恢复/失效、401/403/404 语义和伪造请求头。
- PostgreSQL 集成测试使用 Testcontainers 执行 Flyway、唯一约束、项目创建事务和成员过滤；文档明确 Docker 前置条件。
- 前端 Vitest 覆盖 Session Store、路由守卫、401 过期流程、项目表单和项目切换清理。
- 前端构建与 PC 视口人工截图验收覆盖登录、项目空状态、项目列表、创建和设置页面；截图只证明视觉状态，不替代后端安全测试。

## Risks / Trade-offs

- [PostgreSQL 成为启动硬依赖，现有本地脚本会失效] → 在同一变更中补充数据库配置、启动检查和迁移说明，并在启动前给出明确错误。
- [引导管理员密码通过环境配置暴露或长期保留] → 不提供默认密码、不记录原值、只在用户不存在时消费配置，并要求生产使用密钥管理注入。
- [Session 与 CSRF 配置错误导致登录循环或写请求被拒绝] → 统一凭据和 CSRF 客户端，增加真实 Session 生命周期集成测试，并保持前后端同源部署目标。
- [项目字符串标识与已有 ClickHouse 数据不一致] → `projectId` 创建后不可修改，授权只围绕同一标识建立，不迁移或重写 ClickHouse 事件。
- [一次性替换深色全局样式造成既有 Crash 页面视觉回归] → 先建立浅色 Token 与共享组件，再逐页迁移并在三个 PC 宽度回归现有 Crash 页面。
- [不适配窄屏会限制部分用户] → 在产品说明和验收中明确 1280 像素最低设计宽度，不加入未经验证的半响应式规则。

## Migration Plan

1. 增加依赖、数据库配置和 Flyway 迁移，在测试 PostgreSQL 上验证建表与回滚前置备份。
2. 上线用户引导、认证和 Session 接口，但暂不切换 Crash 查询授权；验证管理员可登录且会话持久化。
3. 上线项目模型与管理 API，创建或导入需要访问现有 Crash 数据的项目及成员关系，确保 `projectId` 与已有事件一致。
4. 切换 Crash 查询授权并发布前端路由、浅色控制台和项目页面；同时移除开发请求头授权和 `demo-project` 回退。
5. 完成跨项目、会话过期、CSRF、PC 视觉和既有 Crash 回归验证后再开放给用户。

若上线后必须回滚，先回滚前端和 Crash 授权切换，使旧页面恢复开发期访问方式；保留 PostgreSQL 表和已经创建的用户、项目数据，不删除或改写 ClickHouse 事件。该回滚仅适用于受控开发环境，生产环境不得恢复可伪造请求头作为长期安全边界。
