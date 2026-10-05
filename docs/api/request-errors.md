# 框架请求错误

本文维护已通过所属安全过滤链并进入 MVC 的请求失败契约。登录、[应用管理](app-api.md)、[Agent 查询](agent-query-api.md)及[分析 Worker](analysis-api.md)使用同一 JSON 形状；安全链或领域已定义的专项错误保持原语义。

| 类别 | HTTP | code | 说明 |
|---|---|---|---|
| 正文缺失、JSON 语法或类型解码失败 | 400 | INVALID_REQUEST_BODY | 固定说明，不输出解析器异常或未知属性 |
| 路径、查询、头类型错误；缺少必填 query/header/part | 400 | INVALID_PARAMETER | 可定位时提供安全声明字段 |
| 已解码后的 Bean/方法输入约束失败 | 400 | VALIDATION_FAILED | 固定字段说明；绑定类型失败仍为 INVALID_PARAMETER |
| 不支持路由方法 | 405 | METHOD_NOT_ALLOWED | 保留 Allow 头 |
| 框架媒体类型不支持 | 415 | UNSUPPORTED_MEDIA_TYPE | 保留框架协商头 |

返回值校验属于内部失败，返回 500 INTERNAL_ERROR，不能归为调用方 400；其他未显式映射的内部异常沿用框架处理。框架错误 retryable=false，requestId 可为 null，errors 最多 50 项，timestamp 为 UTC 时刻。字段 errors 只包含 field/code/message，不包含 rejectedValue、密码、Authorization、Token、租约、正文、堆栈或数据库内容。无法安全定位的复杂字段使用 request，不回显客户端集合键。

示例：已认证的 `GET /api/v1/apps/invalid-uuid` 返回 400：

```json
{
  "code": "INVALID_PARAMETER",
  "message": "请求参数格式错误",
  "retryable": false,
  "requestId": null,
  "errors": [{ "field": "appId", "code": "TYPE_MISMATCH", "message": "参数类型错误" }],
  "timestamp": "2026-10-04T12:00:00Z"
}
```

## 保持的专项边界

- 未认证网页请求、CSRF 拒绝、Agent/Worker 凭据失败由原安全链处理，未到达 MVC 的请求不保证使用上述框架 code。Agent 非 GET 请求保持 `405 AGENT_READ_ONLY`。
- APP_NOT_FOUND、角色拒绝、领域 413/415、查询预算和存储失败保留原 code、status、retryable、Retry-After。
- SDK 批次部分接受仍在批次响应中返回事件级错误，不转换为整个请求失败。
- 内存报告接收入口自行检查 metadata/report，因此缺失 part 仍返回其专项业务错误；框架声明的必填 part 缺失才映射为 INVALID_PARAMETER。上传格式、持久队列和重试步骤未改变。

2026-10-04 正式 MockMvc 与真实 PostgreSQL/HTTP 验证边界见[后端测试记录](../../backend/docs/knowledge-base/06-测试与质量保障.md#2026-10-04-框架错误响应)。本轮未改变 Nginx 或云端部署。
