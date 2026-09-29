# 应用查询 Token 管理 API

应用查询 Token 是供 MCP 和其他 Agent 查询客户端复用的单应用只读凭据，固定权限为 `apm:read`。管理接口使用网页 Session；`POST` 与 `DELETE` 仍需 `X-XSRF-TOKEN`。完整 Token 仅在创建成功的响应中出现。Android 上报的 `X-App-Key` 和网页 Session 均不能替代该凭据。Agent 查询入口的已实现边界见[当前实现与验证边界](../knowledge-base/00-当前实现与验证边界.md)。

## 管理接口

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| `POST` | `/api/v1/apps/{appId}/query-tokens` | JSON `name` 必填，去首尾空白后 1—100 字符；`expiresInDays` 可选，只支持 30/90/365，默认 90 | `201`，`metadata` 与唯一一次 `token`；`Cache-Control: no-store, private` |
| `GET` | `/api/v1/apps/{appId}/query-tokens?page=0&size=20` | `page` 从 0 开始；`size` 为 1—100，默认 20 | `200`，`items`、`page`、`size`、`totalItems`、`totalPages`；仅元数据 |
| `DELETE` | `/api/v1/apps/{appId}/query-tokens/{tokenId}` | 无请求体 | `204`；同一应用重复撤销幂等 |

三个接口只允许目标应用的 `OWNER`/`ADMIN`。`DEVELOPER`/`VIEWER` 返回 `403 FORBIDDEN`；非成员返回 `404 APP_NOT_FOUND`；跨应用或不存在的 `tokenId` 返回相同的 `404 QUERY_TOKEN_NOT_FOUND`。未登录返回 `401 AUTH_REQUIRED`，无效 CSRF 返回 `403`。

创建示例（占位符不是真实凭据）：

```http
POST /api/v1/apps/550e8400-e29b-41d4-a716-446655440000/query-tokens HTTP/1.1
Content-Type: application/json
X-XSRF-TOKEN: <网页会话的 CSRF Token>

{"name":"值班分析","expiresInDays":90}
```

```json
{
  "metadata": {
    "id": "7dfeb682-e7ea-4cab-9c4e-06f0421680f2",
    "name": "值班分析",
    "displayPrefix": "apm_qt_<短前缀>",
    "scope": "apm:read",
    "createdBy": "08f0b595-a725-4509-a07c-dd5f84030e0b",
    "createdAt": "2026-09-28T00:00:00Z",
    "expiresAt": "2026-12-27T00:00:00Z",
    "revokedAt": null,
    "status": "ACTIVE"
  },
  "token": "apm_qt_<仅本次可见的随机值>"
}
```

列表项目包含上述 `metadata` 字段而不含 `token`、`token_digest` 或密文。状态由读取时刻计算：`ACTIVE`、`EXPIRED`、`REVOKED`。按 `createdAt` 和 `id` 倒序，默认 20 条。到期时间以服务端创建时间加对应天数计算；到期不可续期，只能另建 Token。每个应用最多 20 个尚未撤销且未到期的 Token，超出返回 `409 QUERY_TOKEN_LIMIT`，该值可用 `apm.query-token.max-active` 调整。

参数错误返回 `400 INVALID_QUERY_TOKEN_NAME`、`INVALID_QUERY_TOKEN_EXPIRY` 或 `INVALID_QUERY_TOKEN_PAGE`。创建请求没有自动重试；若服务端已创建但客户端丢失响应，刷新列表找到并撤销不确定项，再新建凭据。完整值无法找回。

每次 Agent 请求从数据库重新验证 Token；撤销提交后才开始鉴权的请求返回 `401 QUERY_TOKEN_INVALID`，此前已通过鉴权的在途请求允许完成。认证数据库故障返回 `503 QUERY_AUTH_UNAVAILABLE` 和 `Retry-After: 30`，不会伪装成失效。Token 的创建者后续角色变化不改变该凭据的应用绑定；只有管理操作继续检查当前角色。
