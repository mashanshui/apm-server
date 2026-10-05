# 应用部分更新规格增量

## MODIFIED Requirements

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
