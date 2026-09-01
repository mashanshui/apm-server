## Why

当前由用户填写的 `projectId` 与 Android 包名重复表达应用身份，并与事件中的 `appId`、上报使用的项目 Key 形成多套容易混淆的标识。需要把产品模型收敛为“用户提供唯一包名，系统生成应用 ID 与应用 Key”，同时保留应用名称和描述作为创建时的展示元数据，减少创建和客户端配置错误。

## What Changes

- **BREAKING**：产品对外统一使用“应用”语义；网页路由和管理 API 从 `/projects/{projectId}` 改为 `/apps/{appId}`，响应字段从 `projectId` 改为系统生成、公开且不可修改的 `appId`。
- **BREAKING**：创建应用时继续填写 `name`、`description` 和全平台唯一、规范化为小写且不可修改的 `packageName`，其中只有 `packageName` 必填；服务端在同一事务中生成 UUID v4 `appId`、Owner 成员关系和应用上报凭据。`name` 是日常展示名称，未填写时默认使用包名，`description` 未填写时为空，后续仍可修改名称和描述。
- **BREAKING**：`appPackageName` 统一改名为 `packageName`；重复包名返回 `409 PACKAGE_NAME_CONFLICT`，同一包名不再允许创建多个应用，测试、预发布和生产差异使用现有 `environment`、`channel` 等事件维度表达。
- **BREAKING**：项目 Key 统一改为 App Key：字段 `projectKey` 改为 `appKey`，请求头 `X-Project-Key` 改为 `X-App-Key`，外部格式前缀从 `apm_pk_` 改为 `apm_ak_`。
- **BREAKING**：JSON 公共事件协议从 `schemaVersion=1` 升级到 v2，卡顿 manifest 从 `schemaVersion=2` 升级到 v3；原来表示包名的 `appId` 改为 `packageName`，避免与系统生成的内部应用 ID 同名。旧版本和旧字段均按不可重试契约错误拒绝，上报包名必须与 App Key 绑定包名完全一致。
- 幂等隔离键从 `projectId + eventId` 改为 `appId + eventId`，mapping 路径从 `<mapping-root>/<projectId>/<buildId>.txt` 改为 `<mapping-root>/<appId>/<buildId>.txt`，所有查询和分析数据按 `appId` 隔离。
- App Key 继续使用高熵随机生成、摘要索引和受保护的可恢复密文；每个应用恰有一个永久、不可修改、不可轮换且可由 `OWNER`/`ADMIN` 随时查看的 App Key。
- 普通应用列表和详情不得返回 App Key；浏览器不得持久化完整 App Key，上报鉴权继续先于正文读取，包名不匹配继续整请求拒绝且零写入。
- **BREAKING**：不兼容旧数据库记录、旧管理路由、旧请求/响应字段、`X-Project-Key`、`apm_pk_` Key 或旧事件字段；实施前清空 PostgreSQL 管理数据和 ClickHouse 测试数据，不提供双写、别名或回退。
- Android 生产端与 `rhea-trace-processor` 源码不在本仓库内修改；本变更必须生成完整后续修改清单。apm-server 目标升级到 `io.github.mashanshui:rhea-trace-processor:1.0.1`，使用已提供的真实 v3 ZIP 完成严格解析、字节和报告验证，不在服务端增加旧 manifest 适配层。

## Capabilities

### New Capabilities

- `project-ingest-credentials`：调整为应用级 App Key 的生成、加密保存、权限查询、唯一包名绑定、鉴权映射和稳定失败语义。

### Modified Capabilities

- `project-management`：对外改为应用管理；创建时保留名称和描述填写，仅包名必填，系统生成 `appId`，并与 Owner 关系及 App Key 原子创建；日常展示使用名称。
- `jvm-crash-monitoring`：批量上报升级为 JSON v2，改用 `X-App-Key` 映射应用，并强制事件 `packageName` 与绑定包名一致，数据按 `appId` 隔离。
- `jank-stack-artifact-ingestion`：卡顿 manifest 升级为 v3，改用 `X-App-Key` 映射应用，并强制 `packageName` 与绑定包名一致，mapping 和幂等键使用 `appId`。
- `desktop-web-console`：项目页面和路由改为应用语义；创建页保留名称、描述和包名字段，仅包名必填，列表和工作区优先展示名称，设置页展示只读 `appId`、包名和受权限保护的 App Key。

## Impact

- PostgreSQL/Flyway：新增版本化迁移，在空测试数据前提下把项目主键、成员关系和凭据模型改为应用语义，将包名提升为应用唯一属性并增加唯一约束。
- ClickHouse：事件、卡顿事实、详情和聚合表的隔离字段从 `project_id` 改为 `app_id`；固定数据集、查询 SQL、幂等和指纹输入同步调整。
- 服务端：调整应用创建事务、创建 DTO 中的名称/描述可选字段、实体、Repository、权限服务、管理与查询路由、App Key 鉴权、mapping 解析、错误码及日志安全边界。
- 前端：调整类型、Store、路由、创建页的名称/描述/包名表单、应用列表和工作区名称展示、应用设置、Crash/卡顿页面、API 客户端和测试中的全部项目术语与字段。
- Android 上报契约：客户端改用 `X-App-Key`，JSON 事件升级到 v2，卡顿 manifest 升级到 v3，并改发 `packageName`；旧客户端不能继续上报。本仓库只维护迁移清单与服务端契约，Android/processor 实现后续单独修改。
- 配置与安全：保留现有 App Key 加密主密钥要求，但更新 Key 前缀、AAD 身份字段、敏感响应和安全检查。
- 文档：同步知识库、API、客户端接入、manifest、错误码、部署、本地联调和前端知识库，确保 `appId`、`packageName` 与 `appKey` 各自只有一种含义。
- 测试：重建 PostgreSQL/ClickHouse 夹具，并覆盖名称/描述可选创建、名称展示、系统生成 ID、包名全局唯一、并发冲突、App Key 权限与恢复、旧契约拒绝、包名不匹配零写入、processor 1.0.1 真实 ZIP 和完整端到端流程。
