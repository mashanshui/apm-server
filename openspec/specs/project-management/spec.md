# project-management Specification

## Purpose

为已登录用户提供按项目成员关系隔离的项目工作区，使其能够发现自己可访问的项目、创建新项目并在权限允许时维护项目基础信息。

## Requirements

### Requirement: 用户只能列出有成员关系的应用

系统 MUST 只返回当前登录用户拥有有效成员关系的应用，并为每个应用返回系统生成的 `appId`、唯一 `packageName`、显示名称、描述、当前用户角色和更新时间。应用列表和应用切换器 MUST 使用显示名称 `name` 作为主要展示文本，并可将 `packageName` 作为辅助技术信息展示。应用列表 MUST 支持按显示名称、包名或 `appId` 搜索，并明确区分加载中、有结果、无搜索结果、没有任何应用和请求失败状态。

#### Scenario: 查看有权限的项目列表

- **WHEN** 已登录用户打开应用列表
- **THEN** 页面展示该用户有成员关系的应用及其角色，不包含其他应用

#### Scenario: 首次使用且没有项目

- **WHEN** 已登录用户没有任何应用成员关系
- **THEN** 页面显示创建首个应用的空状态和明确的创建入口

#### Scenario: 搜索项目

- **WHEN** 用户输入显示名称、包名或 `appId` 的部分文本
- **THEN** 页面只展示当前用户可访问且与搜索条件匹配的应用，并在无匹配项时保留清除搜索的入口

### Requirement: 已登录用户创建应用

系统 MUST 允许已登录用户使用 `name`、`description` 和 `packageName` 创建应用，其中只有 `packageName` 必填。`packageName` MUST 去除首尾空白、使用小写 Android application ID 格式、至少包含两个点分段、每段以小写字母开头且后续只允许小写字母、数字或下划线，并 MUST 在平台内全局唯一且创建后不可修改。`name` 和 `description` MUST 为可选字段；名称去除首尾空白后 MUST 不超过 100 个字符，缺失或为空时 MUST 使用 `packageName` 作为默认显示名称；描述缺失或为空时 MUST 保存为空，非空描述 MUST 不超过 500 个字符。系统 MUST 生成公开、不可修改的 `appId`，并在同一事务中保存应用、将创建者设为该应用的 `Owner`、生成该应用唯一的 App Key。

#### Scenario: 成功创建项目

- **WHEN** 用户提交合法且尚未注册的唯一 `packageName`，并填写可选的 `name` 和 `description`
- **THEN** 系统原子保存提交的名称、描述、应用、`Owner` 成员关系和 App Key，返回系统生成的 `appId`，并将用户引导到新应用的设置页面

#### Scenario: 只填写包名创建应用

- **WHEN** 用户只提交合法且尚未注册的唯一 `packageName`
- **THEN** 系统使用包名作为 `name`、将 `description` 保存为空，并原子创建应用、`Owner` 成员关系和 App Key

#### Scenario: 项目标识重复

- **WHEN** 用户提交已被任意应用使用的包名，或并发请求竞争创建同一包名
- **THEN** 系统返回 `409 PACKAGE_NAME_CONFLICT`，不创建第二个应用、成员关系或 App Key，并在包名字段显示冲突提示

#### Scenario: 创建表单校验失败

- **WHEN** 用户提交空包名、包含大写字母、只有一个分段或包含不允许字符的包名
- **THEN** 页面和服务端返回可理解的包名校验信息，不创建应用、成员关系或 App Key

#### Scenario: 创建请求失败

- **WHEN** 合法创建请求因临时服务错误、应用 ID 生成或 App Key 持久化失败而失败
- **THEN** 页面保留名称、描述和包名，显示可重试错误，并且服务端不留下应用、Owner 成员关系或凭据中的任何半成品

### Requirement: 按角色查看和修改应用基础信息

系统 MUST 允许所有应用成员查看只读 `appId`、只读 `packageName`、显示名称、描述、当前用户角色、创建时间和更新时间。只有 `Owner` 与 `Admin` MUST 能修改显示名称和描述；`appId` 与 `packageName` MUST 对所有角色保持只读；`Developer` 与 `Viewer` MUST 看到只读状态，且服务端必须独立拒绝其修改请求。


`PATCH /api/v1/apps/{appId}` MUST 按字段存在性执行部分更新：缺失 name 或 description MUST 保留对应原值；显式提交的 name MUST 为去除首尾空白后长度 1～100 的字符串，null、空白或错误类型 MUST 返回 400；description MUST 接受字符串或 null，null 及去除首尾空白后的空字符串表示清空，非空长度不得超过 500。未知字段和身份字段 MUST 拒绝。所有提交字段 MUST 校验成功后在同一事务中保存，任一字段非法不得部分更新。空对象或规范化后与当前值相同的请求 MUST 返回当前应用，且不改变 updatedAt，仍须执行修改权限与 CSRF 校验。

#### Scenario: Owner 修改项目

- **WHEN** 应用 `Owner` 提交合法的新显示名称或描述
- **THEN** 系统保存变更、更新修改时间并向页面返回明确的成功反馈，`appId`、`packageName` 和 App Key 保持不变

#### Scenario: Viewer 尝试修改项目

- **WHEN** `Viewer` 绕过前端只读状态直接提交修改请求
- **THEN** 系统返回 `403 FORBIDDEN` 且应用数据保持不变

#### Scenario: 尝试修改身份字段

- **WHEN** 任意角色通过应用修改请求提交 `appId`、`packageName`、`appKey` 或等价字段
- **THEN** 系统返回明确的字段不可修改或请求字段非法响应，已保存身份字段保持不变

#### Scenario: 表单存在未保存修改

- **WHEN** 用户修改了可编辑字段但尚未保存并尝试离开页面
- **THEN** 页面提示存在未保存内容，允许用户继续编辑或确认放弃修改

#### Scenario: 只修改名称
- **WHEN** Owner 或 Admin 对已有描述的应用只提交合法 name
- **THEN** 系统更新名称并保留原 description

#### Scenario: 只修改描述
- **WHEN** Owner 或 Admin 只提交合法 description
- **THEN** 系统更新描述并保留原 name，不要求重复提交名称

#### Scenario: 显式清空描述
- **WHEN** 有权限用户提交 description=null 或空白字符串
- **THEN** 系统清空描述并保留未提交的名称

#### Scenario: 拒绝空名称和错误类型
- **WHEN** 有权限用户显式提交 name=null、空白名称、非字符串名称或非字符串且非 null 的 description
- **THEN** 系统返回 400，名称、描述和修改时间均不变

#### Scenario: 多字段更新原子性
- **WHEN** 用户同时提交合法名称和超过 500 字符的描述
- **THEN** 整个请求失败，合法名称也不被保存

#### Scenario: 空更新或重复更新
- **WHEN** 有权限用户提交空对象，或重复提交规范化后与现值相同的字段
- **THEN** 返回当前应用，updatedAt 不改变，不修改身份或凭据字段

### Requirement: 应用资源访问不得泄露其他租户信息

系统 MUST 在服务端依据当前登录用户的应用成员关系和角色校验所有 `/api/v1/apps/{appId}` 读取与修改请求。未认证请求 MUST 返回未认证响应；已认证但无成员关系的请求 MUST 使用与应用不存在一致的 `404 APP_NOT_FOUND`，不得泄露目标应用、包名或 App Key 是否存在。客户端提供的应用标识请求头 MUST NOT 作为身份或成员关系证明。

#### Scenario: 跨项目读取

- **WHEN** 已登录用户请求一个没有成员关系的 `appId`
- **THEN** 系统返回 `404 APP_NOT_FOUND` 且不返回应用元数据

#### Scenario: 伪造项目请求头

- **WHEN** 用户为无权访问的应用添加 `X-App-Id`、旧 `X-Project-Id` 或 `X-User-Project-Ids` 请求头
- **THEN** 系统忽略这些请求头作为身份或成员关系证明，并按当前服务端会话拒绝访问
