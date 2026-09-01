# API、数据模型与状态管理

## 当前调用的查询 API

认证和应用管理接口：

```http
GET   /api/v1/session
POST  /api/v1/auth/login
POST  /api/v1/auth/logout
GET   /api/v1/apps?query=
POST  /api/v1/apps
GET   /api/v1/apps/{appId}
PATCH /api/v1/apps/{appId}
GET   /api/v1/apps/{appId}/ingest-credential
```

```http
GET /api/v1/apps/{appId}/crashes/overview
GET /api/v1/apps/{appId}/crashes/trend?interval=hour|day
GET /api/v1/apps/{appId}/crashes/issues
GET /api/v1/apps/{appId}/crashes/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/crashes/events/{eventId}
```

卡顿页面已接入以下服务端接口：

```http
GET /api/v1/apps/{appId}/janks/overview
GET /api/v1/apps/{appId}/janks/trend?interval=hour|day
GET /api/v1/apps/{appId}/janks/issues
GET /api/v1/apps/{appId}/janks/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/janks/events/{eventId}
GET /api/v1/apps/{appId}/jank-metrics/fps
GET /api/v1/apps/{appId}/jank-metrics/suspension-rate
GET /api/v1/apps/{appId}/jank-metrics/trend?metric=fps|suspension_rate&interval=hour|day
GET /api/v1/apps/{appId}/jank-metrics/dimensions?metric=fps&dimension=scene
```

已发布契约、统计公式和服务端限制以[服务端 API 文档](../../../docs/api/README.md)、[Crash API 文档](../../../docs/api/crash-api.md)、[卡顿服务端 API](../../../docs/api/jank-server-api.md)及[平台查询与 Dashboard](../../../docs/knowledge-base/05-查询与Dashboard.md)为准；本页只记录前端消费方式。

## 请求约定

- API 基地址来自 `VITE_API_BASE_URL`，未配置时使用当前站点相对路径。
- 应用 ID、指纹和事件 ID 等路径段统一使用 `encodeURIComponent`。
- 所有请求发送 `Accept: application/json`。
- 默认 `credentials: include` 携带同源 Session Cookie。
- `POST`/`PATCH` 等状态变更请求从 `XSRF-TOKEN` Cookie 读取 Token，并发送 `X-XSRF-TOKEN`；登录、退出和应用写请求不绕过 CSRF。
- Crash 与卡顿请求只在 URL 路径绑定当前应用，不发送 `X-App-Id` 或 `X-User-App-Ids`。
- 前端绝不发送 Android 上报使用的 `X-App-Key`。
- `App` 和 Pinia 应用 Store 只包含只读 `packageName`，不包含 `appKey`；独立凭据响应只由设置页局部状态消费。
- 创建请求类型为可选 `name`、可选 `description` 和必填 `packageName`；空名称/描述由服务端分别默认到包名/null。
- 空字符串、`undefined` 和 `null` 不进入查询参数。
- Crash 查询参数集中在 `crashApi.ts`；卡顿公共参数与指标参数分别由 `jankApi.ts` 的白名单构造器维护，指标请求不接受指纹或游标。

## 类型边界

`src/types/crash.ts` 定义 Crash 契约；`src/types/jank.ts` 定义卡顿总览、趋势、Issue、事件、采样片段、调用树、堆栈字典、FPS、挂起率、多维、统一指标趋势和筛选类型。卡顿详情采样质量只使用服务端派生的 `expectedSampleCount`、`parsedSampleCount` 和 `missingSampleCount`，不保留 attempted/successful/dropped。统一趋势点只填充当前指标对应的 FPS 或秒/小时前台时长字段；挂起率点的 `validRecords` 表示有效设备日数。后端响应字段变化时，应在同一改动中：

1. 核对后端 API 文档和 JSON 语义。
2. 更新 TypeScript 类型。
3. 更新 API 客户端或页面消费逻辑。
4. 补充或调整测试。
5. 同步本知识库中受影响的主题页。

当前类型是手工维护，并非由 OpenAPI 自动生成；仓库当前也没有已发布 OpenAPI 契约。因此不能只改类型而不做运行时验证。

## 错误模型

`ApiError` 保存 HTTP 状态、服务端错误码、用户可读消息和是否建议重试；`CrashApiError`、`JankApiError` 是兼容导出别名。

| 场景 | 当前行为 |
|---|---|
| 网络不可达 | `NETWORK_ERROR`，提示检查后端是否启动，可重试 |
| 404 | 使用安全兜底文案，不区分不存在和无权访问 |
| 401 | 归一为会话过期事件；清理 Session/App Store 并跳转 `/login`，保留站内回跳 |
| 403 | 保留服务端角色不足提示，应用设置页显示只读 |
| 409 | `PACKAGE_NAME_CONFLICT` 绑定到包名字段 |
| 5xx 或 408 | 默认标记为可重试 |
| 非 JSON 错误体 | 使用 HTTP 状态生成兜底消息 |
| 成功响应不是有效 JSON | `INVALID_RESPONSE` |
| 主动取消 | 保留 `AbortError`，不显示为普通失败 |

## 查询状态管理

Pinia 只管理跨页面的认证和应用状态；路由查询参数保存可分享的 Crash/卡顿筛选状态，composable 保存单页请求状态：

- `useSessionStore` 管理用户、初始化、登录、退出、401 过期清理，不持久化密码、Session 或 Token。
- `useAppStore` 管理应用列表、搜索、当前应用、创建、更新和会话失效清理。

- `useCrashOverview` 管理总览、趋势、问题排行和问题分页。
- `useCrashIssueEvents` 管理某一指纹的事件列表和事件分页。
- 详情页直接管理单事件请求状态。
- `jankQuery.ts` 维护卡顿公共/指标筛选的最近 24 小时默认值、白名单和 URL 往返；切换到挂起率时强制 `day`、清空 `scene`，并把 `scene` 维度回退到 `deviceModel`。
- `useJankQuery.ts` 提供独立区域的加载/错误/数据状态、`AbortController`、请求令牌和游标按稳定键去重；`useJankIssues.ts`、`useJankIssueEvents.ts` 和 `useJankMetrics.ts` 分别组合问题、Issue 和指标页面请求。

当前不把 Crash 或卡顿查询结果写入全局 Store；如果未来需要跨页面缓存、预取或失效策略，再评估专用请求缓存库。

凭据响应受服务端 `no-store, private` 保护。前端不把完整 Key 写入 Pinia、URL、路由 state、`localStorage`、`sessionStorage`、日志或错误对象；会话失效事件会立即清理设置页局部 Key。

## 数据展示约束

- `null` 比例表示不可计算，不能格式化为 0。
- 合法的数值 0 必须正常显示。
- 页面只展示服务端返回的脱敏数据，不在浏览器尝试恢复敏感原文。
- `dataSource` 只是联调标识，不是权限或数据正确性的证明。
