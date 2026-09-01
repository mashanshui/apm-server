## Purpose

为每个 Android 应用提供与全局唯一包名绑定的永久 App Key，使服务端能够把 `X-App-Key` 安全映射为系统生成的 `appId` 与包名，并允许有管理权限的成员随时查看同一完整凭据。

## ADDED Requirements

### Requirement: 应用创建时生成唯一永久 App Key

系统 MUST 在创建应用时生成一个不可预测、平台内唯一且不由 `appId`、显示名称、包名或时间推导的 App Key。每个应用 MUST 恰好拥有一个 `apm_ak_` 前缀的 App Key；该 Key 在应用生命周期内永久有效、不可修改、不可轮换且不过期。应用、Owner 成员关系和 App Key MUST 作为同一事务的不可分割结果保存。

#### Scenario: 成功创建应用凭据

- **WHEN** 已登录用户成功创建包含合法唯一包名的新应用
- **THEN** 系统生成唯一 `appId` 和 App Key，并原子保存应用、Owner 成员关系和凭据

#### Scenario: App Key 生成失败

- **WHEN** 包名合法且未重复，但 App Key 生成或持久化失败
- **THEN** 系统拒绝整个创建请求，不留下应用、Owner 成员关系或不完整凭据

#### Scenario: 修改应用基础信息

- **WHEN** `OWNER` 或 `ADMIN` 修改应用显示名称或描述
- **THEN** 原 `appId`、`packageName` 和 App Key 保持不变

### Requirement: 永久 App Key 可恢复且不得明文落库

系统 MUST 保存支持精确鉴权查询的不可逆 App Key 摘要，并 MUST 以受保护的可恢复形式保存完整 App Key，以便授权用户在服务重启后仍能查看同一个值。完整 App Key MUST NOT 以可直接读取的数据库明文、配置默认值、日志、异常或普通应用响应形式保存或暴露。

#### Scenario: 服务重启后查看 App Key

- **WHEN** App Key 已创建且服务完成重启，具有权限的成员再次查询该凭据
- **THEN** 系统返回与创建时完全相同的完整 App Key

#### Scenario: 检查持久化和日志

- **WHEN** 应用完成创建、凭据查询和上报鉴权
- **THEN** 数据库只包含 Key 摘要和受保护的可恢复值，应用日志与错误响应不包含完整 App Key

#### Scenario: 缺少凭据保护配置

- **WHEN** 服务无法使用必需的 App Key 保护配置安全生成或恢复完整凭据
- **THEN** 服务明确拒绝相关启动或凭据操作，不得退回明文存储或生成不可恢复的半成品凭据

### Requirement: 仅应用管理员可以查看完整 App Key

系统 MUST 提供独立的应用上报凭据查询接口。只有目标应用的 `OWNER` 与 `ADMIN` 可以查看完整 App Key 和绑定包名；`DEVELOPER` 与 `VIEWER` MUST 被拒绝，非应用成员的响应 MUST 与应用不存在一致。普通应用列表和普通应用详情 MUST NOT 包含完整 App Key，凭据响应 MUST 禁止共享缓存。

#### Scenario: Owner 查看完整 App Key

- **WHEN** 应用 `OWNER` 查询 `/api/v1/apps/{appId}/ingest-credential`
- **THEN** 系统返回只读 `appId`、`packageName` 和完整 `appKey`，并使用禁止缓存的响应策略

#### Scenario: Admin 查看完整 App Key

- **WHEN** 应用 `ADMIN` 查询该应用凭据
- **THEN** 系统返回与 Owner 可见内容一致的凭据信息

#### Scenario: Developer 查询完整 App Key

- **WHEN** 应用 `DEVELOPER` 或 `VIEWER` 直接调用凭据查询接口
- **THEN** 系统返回 `403 FORBIDDEN`，且响应不包含完整或部分 App Key

#### Scenario: 非成员枚举凭据

- **WHEN** 已登录用户查询一个没有成员关系的应用凭据
- **THEN** 系统返回 `404 APP_NOT_FOUND`，不泄露应用、包名或 App Key 是否存在

#### Scenario: 普通应用接口返回数据

- **WHEN** 用户请求应用列表、创建响应或普通应用详情
- **THEN** 响应不包含完整 App Key、Key 摘要、密文、nonce 或其他可用于推断凭据的字段

### Requirement: App Key 鉴权映射到应用和绑定包名

系统 MUST 对 `X-App-Key` 执行精确匹配，并将合法 App Key 映射为唯一的 `appId` 和 `packageName`。服务端 MUST 使用该映射结果作为上报应用身份，MUST NOT 接受客户端提供的 `appId`，也 MUST NOT 接受旧 `X-Project-Key`、`apm_pk_` Key、全局项目 ID、全局 Key 或默认包名回退。

#### Scenario: 合法 App Key

- **WHEN** 上报请求携带数据库中存在的完整 `X-App-Key`
- **THEN** 系统在读取业务正文前得到该 Key 对应的唯一 `appId` 和绑定包名，并继续处理请求

#### Scenario: App Key 缺失或不存在

- **WHEN** `X-App-Key` 缺失、为空、格式错误、使用旧前缀或摘要不存在
- **THEN** 系统返回不可重试的 `401 INVALID_APP_KEY`，不读取或保存业务正文，也不泄露失败原因差异

#### Scenario: 使用旧项目请求头

- **WHEN** 上报请求只携带 `X-Project-Key` 或旧 `apm_pk_` Key
- **THEN** 系统按缺少合法 App Key 返回 `401 INVALID_APP_KEY`，不执行兼容别名或回退

#### Scenario: 鉴权存储暂时不可用

- **WHEN** App Key 查询因 PostgreSQL 暂时不可用而无法完成
- **THEN** 系统返回可重试的 `503 APP_AUTH_UNAVAILABLE`，不得将基础设施故障伪装成 Key 无效

### Requirement: 上报包名必须匹配 App Key 绑定包名

JSON v2 批次中每个事件和 v3 卡顿 manifest MUST 包含非空 `packageName`，并 MUST 与 App Key 映射得到的绑定包名完全一致。客户端 MUST NOT 在正文中声明系统生成的 `appId`。发现任何包名不匹配时，系统 MUST 以不可重试的 `403 PACKAGE_NAME_MISMATCH` 拒绝整个请求，且 MUST NOT 写入该请求中的任何事件、事实或详情。

#### Scenario: 批次内包名全部匹配

- **WHEN** 合法 App Key 绑定 `com.example.app`，且批次中每个事件的 `packageName` 都是 `com.example.app`
- **THEN** 系统按该 App Key 映射得到的 `appId` 继续执行事件校验和存储

#### Scenario: 批次包含不匹配包名

- **WHEN** 合法 App Key 绑定 `com.example.app`，但批次中至少一个事件的 `packageName` 不同
- **THEN** 系统返回 `403 PACKAGE_NAME_MISMATCH` 并拒绝整个批次，不部分接受其他事件

#### Scenario: 卡顿产物包名不匹配

- **WHEN** 合法 App Key 的绑定包名与卡顿 manifest `packageName` 不同
- **THEN** 系统返回 `403 PACKAGE_NAME_MISMATCH`，不生成指纹、不读取业务 mapping、不写入原始事实、卡顿事实或详情

### Requirement: 包名与 App Key 均不可修改

系统 MUST 把应用 `packageName` 和 App Key 视为创建后不可修改的身份字段。一个包名 MUST 只属于一个应用；不同包名的应用变体 MUST 分别创建应用和凭据，测试、预发布和生产差异 MUST 使用事件的环境或渠道维度表达。

#### Scenario: 尝试修改包名或 App Key

- **WHEN** 用户通过应用更新接口提交 `packageName`、`appKey` 或等价字段
- **THEN** 系统返回明确错误，已保存的包名与 App Key 保持不变

#### Scenario: 使用相同包名创建第二个应用

- **WHEN** 用户尝试使用已注册包名创建另一个应用
- **THEN** 系统返回 `409 PACKAGE_NAME_CONFLICT`，且不会生成第二个 `appId` 或 App Key
