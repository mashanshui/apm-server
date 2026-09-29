# 应用查询 Token 规范

## Purpose

为应用管理员提供可到期、可撤销且仅授权本应用数据查询的凭据，让 MCP 与其他 Agent 查询客户端复用同一身份边界，同时保持网页会话、客户端上报及管理权限彼此隔离。

## Requirements

### Requirement: 管理应用查询 Token
系统 MUST 仅允许已登录且属于目标应用的 OWNER/ADMIN 创建、列出和撤销查询 Token，状态变更 MUST 校验 CSRF。每个应用 MUST 支持多个具名 Token，权限固定为 apm:read。有效期 MUST 仅接受 30、90、365 天，缺省为 90 天，以服务端创建时间计算，不允许永久有效或续期修改原 Token。

#### Scenario: 创建和展示
- **WHEN** 管理员提交名称并省略有效期
- **THEN** 系统创建绑定当前应用、90 天到期的 Token，仅本次成功响应包含完整值，并返回其标识及到期时间

#### Scenario: 拒绝越权和非法期限
- **WHEN** Developer/Viewer 创建 Token，或管理员提交永久有效、0 或其他非法期限
- **THEN** 系统分别返回 403 或 400，不创建凭据；非成员管理请求返回与应用不存在一致的 404

#### Scenario: 撤销生效
- **WHEN** 管理员撤销 Token 并收到成功响应
- **THEN** 撤销提交后开始的认证均拒绝该 Token，重复撤销同一应用同一 Token 成功且不恢复权限；已通过认证的在途请求可以完成

### Requirement: 凭据只展示一次并保护生命周期
系统 MUST 生成不可预测的随机 Token，只持久化摘要与必要管理信息，列表不得返回完整值、摘要或可恢复密文。创建响应和敏感界面 MUST 禁止缓存；完整值不得进入 URL、日志、浏览器持久存储或通用全局状态。

#### Scenario: 离开创建结果
- **WHEN** 用户关闭结果区域、切换应用、离开页面或会话失效
- **THEN** 页面清除完整 Token，迟到的旧应用请求不得重新显示它；后续无法再次获取完整值

#### Scenario: 创建响应丢失
- **WHEN** 服务端已创建 Token 但客户端未收到完整结果
- **THEN** 管理员可刷新列表识别并撤销该项，再创建新 Token；客户端不得自动重复创建或声称可找回原值

### Requirement: 统一应用查询身份
系统 MUST 在独立的 /api/agent/v1 查询入口通过 Authorization Bearer 校验应用 Token，推导唯一 appId 和 apm:read 权限，MCP 与其他 Agent 使用同一认证能力。Token MUST 作为应用凭据独立于创建者后续成员角色存在，创建者信息仅供审计。每次请求 MUST 校验有效期与撤销状态，缺失、未知、撤销或到期统一返回 401 QUERY_TOKEN_INVALID，凭据存储不可用返回 503 QUERY_AUTH_UNAVAILABLE，不得误报凭据失效。

#### Scenario: 直接查询和 MCP 查询一致
- **WHEN** 同一有效 Token 分别经 MCP 和 Agent HTTP API 查询相同条件
- **THEN** 两者只能访问相同应用，返回等价业务统计；不存在由客户端传入应用 ID 扩大权限的方式

#### Scenario: 查询其他应用的事件
- **WHEN** Token A 查询仅属于应用 B 的事件 ID 或问题指纹
- **THEN** 返回 A 范围内的不存在或空结果，不泄露 B 数据；显式 appId 参数不被 Agent 入口接受

#### Scenario: 凭据与权限不得混用
- **WHEN** 查询 Token 用于修改应用、读取 App Key、上传数据、管理 Token 或符号表，或仅以 Session/App Key 访问 Agent 入口
- **THEN** 系统拒绝访问，不降级到其他认证方式；同时提供 Cookie 和 Bearer 时也不合并权限

### Requirement: 有界查询与审计
系统 MUST 对 Agent 入口实施按 Token 和应用的请求频率、并发限制，并保存无敏感正文的调用日志。后续未加入查询白名单的接口 MUST 默认拒绝。审计 MUST 包含请求标识、Token 标识、应用、操作、耗时及结果，不包含完整凭据或堆栈。

#### Scenario: 直接调用不能绕过配额
- **WHEN** 客户端跳过 MCP 直接访问 Agent HTTP API 并超过限额
- **THEN** 后端仍返回 429 和重试等待信息，不执行超额数据库查询
