# 登录与应用管理 API

本文记录当前服务端已经实现的网页管理接口。接口默认由 Spring Security Session 保护；浏览器与后端应通过同源地址访问，状态变更请求必须携带 CSRF 请求头。

## 首次管理员、配置与数据库

正常启动需要 PostgreSQL。通过受保护的环境变量注入：

```text
APM_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/apm
APM_DATABASE_USERNAME=apm
APM_DATABASE_PASSWORD=<数据库密码>
APM_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
APM_BOOTSTRAP_ADMIN_PASSWORD=<一次性设置的管理员密码>
APM_BOOTSTRAP_ADMIN_DISPLAY_NAME=平台管理员
APM_APP_KEY_ENCRYPTION_KEY=<Base64 编码的 32 字节随机值>
```

Flyway 按顺序执行当前 V1～V14。V3 在移除旧项目表并创建应用表前，会要求 `project`、`project_member`、`project_ingest_credential` 为空；非空时明确失败，不做兼容迁移或静默删除。V4 新增应用级符号表当前记录和替换审计表，按 `appId + buildId` 唯一定位 mapping；V5 新增[应用查询 Token](query-token-api.md)表；V6～V13 建立[单事件本地分析](analysis-api.md)的历史结构；V14 在无活动旧任务且停止已核验后移除分析源码登记、精简任务与应用 Worker，并增加 Run 快照身份，原终态 JSON 与审计保留。测试环境可以先清空确认过的业务数据，再重新创建应用。已发布迁移文件不改写。

`APM_APP_KEY_ENCRYPTION_KEY` 没有旧变量回退，必须稳定注入并备份；丢失后已保存的 appKey 无法解密查看。生产环境不得使用仓库示例值，应通过密钥服务或受保护的环境变量注入，并将 `APM_SESSION_COOKIE_SECURE=true`。

macOS 本地全栈推荐使用 `scripts/start-dev.sh`。脚本读取仓库根目录下被 Git 忽略的 `.env.local`，通过 Docker Compose 注入配置；停止服务使用 `scripts/stop-dev.sh`。

## 会话与 CSRF

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/session` | 可选 | 已登录返回当前用户；未登录返回 `401 AUTH_REQUIRED`，同时可下发 `XSRF-TOKEN` Cookie |
| `POST` | `/api/v1/auth/login` | CSRF | JSON `{ "email": "...", "password": "..." }`；成功建立服务端 Session |
| `POST` | `/api/v1/auth/logout` | Session + CSRF | 使当前 Session 失效，返回 `204` |

前端应先请求 `/api/v1/session`，读取 `XSRF-TOKEN` Cookie，并在登录、退出、创建和修改请求中发送 `X-XSRF-TOKEN`。Session Cookie 为 HttpOnly；SameSite、Secure、超时可通过 `APM_SESSION_SAME_SITE`、`APM_SESSION_COOKIE_SECURE` 和 `APM_SESSION_TIMEOUT` 配置。网页请求不得把 Session、密码或 CSRF Token 写入 `localStorage`、`sessionStorage` 或 URL。

认证失败统一返回：

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "邮箱或密码错误",
  "retryable": false
}
```

不存在、停用和密码错误使用相同语义；用户无权读取应用使用 `404 APP_NOT_FOUND`，避免枚举其他应用。

## 应用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/apps?query=` | 只返回当前用户有成员关系的应用；按显示名称、包名或 UUID `appId` 搜索 |
| `POST` | `/api/v1/apps` | 接收可选名称/描述和必填包名；同一事务创建应用、`OWNER` 成员和永久 appKey |
| `GET` | `/api/v1/apps/{appId}` | 查看应用基础信息、当前角色和时间元数据 |
| `PATCH` | `/api/v1/apps/{appId}` | `OWNER`/`ADMIN` 修改显示名称和描述；身份字段不可修改 |
| `GET` | `/api/v1/apps/{appId}/ingest-credential` | `OWNER`/`ADMIN` 按需查看包名和完整 appKey；禁止缓存 |

创建请求保留三个字段，其中只有 `packageName` 必填：

```json
{
  "name": "测试 Demo",
  "description": "用于联调的 Android 应用",
  "packageName": "com.example.app"
}
```

服务端去除包名首尾空白，但不静默改大小写；包名必须是 255 个字符以内、至少两个点分段、每段以小写字母开头，后续只允许小写字母、数字和下划线。全局重复返回 `409 PACKAGE_NAME_CONFLICT`，参数错误返回 `400`。

`name` 和 `description` 可缺失或为空；名称去除首尾空白后为空时使用 `packageName`，描述为空时保存为 `null`。服务端生成公开不可变的 UUID v4 `appId`。创建响应、列表和详情字段为 `appId`、`packageName`、`name`、`description`、`role`、`createdAt`、`updatedAt`，不包含完整 appKey、摘要、密文或 nonce：

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "com.example.app",
  "description": null,
  "packageName": "com.example.app",
  "role": "OWNER",
  "createdAt": "2026-09-01T02:00:00Z",
  "updatedAt": "2026-09-01T02:00:00Z"
}
```

凭据查询成功响应为：

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "packageName": "com.example.app",
  "appKey": "apm_ak_<Base64URL 32 字节随机值>"
}
```

凭据响应包含 `Cache-Control: no-store, private` 与 `Pragma: no-cache`。`DEVELOPER`/`VIEWER` 查询凭据返回 `403 FORBIDDEN`，非成员返回 `404 APP_NOT_FOUND`，未登录返回 `401`。appKey 永久有效、不可轮换、不可撤销、不过期，并且只能通过删除应用后重新创建来终止；当前未提供应用删除接口。

## 权限与兼容边界

所有成员可读取应用和查询数据，只有 `OWNER`/`ADMIN` 可修改名称、描述和查看 appKey。`/api/v1/projects/*` 不再映射到新接口；旧 `projectId`、`appPackageName`、`projectKey` 及未知字段均按请求错误处理。

Crash、卡顿和指标查询统一使用 `/api/v1/apps/{appId}/...`，网页只依赖 Session 与 `app_member` 成员关系。`X-App-Id`、`X-User-App-Ids` 等请求头不参与授权；浏览器也不会发送 Android 上报用的 `X-App-Key`。
