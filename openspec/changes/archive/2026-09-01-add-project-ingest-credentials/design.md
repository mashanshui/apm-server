## Context

当前 apm-server 已实现由用户填写 `projectId` 与 `appPackageName`、系统生成 `apm_pk_` 项目 Key 的模型，并在 PostgreSQL、ClickHouse、管理 API、Vue 路由、JSON 事件和卡顿 ZIP 中传播 `projectId/appId/X-Project-Key` 三套容易混淆的身份。当前应用创建页面只提交包名；本次修订恢复名称和描述字段，但仍只把包名作为必填身份字段。参见 [proposal.md](proposal.md) 了解本次收敛为应用身份模型的动机，行为契约以本变更的 delta specs 为准。

本地数据均为测试数据，用户明确不要求旧项目、旧 Key、旧 API、旧 JSON 事件或旧卡顿 manifest 兼容。已发布的 PostgreSQL V1/V2 和 ClickHouse 001–003 不得改写；新结构必须通过后续版本化脚本建立，并在破坏性重建前验证旧业务数据为空。

卡顿 ZIP 当前仍由 apm-server 构建文件中的 `io.github.mashanshui:rhea-trace-processor:1.0.0` 严格校验；用户已提供升级候选 `io.github.mashanshui:rhea-trace-processor:1.0.1`。同时已提供真实 fixture `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip`，只读核验得到 11,189 字节、SHA-256 为 `1445628B2FA6D4ED055FDEDE218F950B1B44D20FFE6AE990F05869ACEB6C16FE`，manifest 为 `schemaVersion=3`、`artifactType=RHEA_JANK`、`packageName=rhea.sample.android` 且不含 `appId`。Android 生产端与 processor 源码本次不在 apm-server 仓库内修改；本设计把 1.0.1 制品解析能力、真实 fixture 集成结果和端到端验证作为门禁，不在服务端增加旧 manifest 适配层。

## Goals / Non-Goals

**Goals:**

- 让用户创建应用时填写名称、描述和一个全平台唯一、不可修改的小写 Android 包名，其中只有包名必填；日常页面使用名称作为主要展示文本。
- 由服务端原子生成公开 `appId`、Owner 成员关系和永久 App Key，使 `appId`、`packageName`、`appKey` 各自只有一种含义。
- 在 PostgreSQL、ClickHouse、Java、HTTP、Vue、测试、配置和文档中统一应用术语，删除旧项目身份回退。
- 将 JSON 公共事件升级为 v2、卡顿 manifest 升级为 v3，并让两条上报链路只通过 `X-App-Key` 确定 `appId`。
- 保持 Key 鉴权先于正文读取、成员隐藏、敏感凭据最小暴露、整请求包名拒绝、幂等和可重试故障语义。
- 为 Android/processor 后续单独修改提供可执行清单和明确的服务端接入门禁。

**Non-Goals:**

- 不迁移或兼容旧项目、旧 Key、旧路由、旧字段、JSON v1、卡顿 manifest v2 或旧 ClickHouse 数据。
- 不在 apm-server 中修改 Android SDK、ZIP 生产端或 processor 源码，也不虚构尚未发布的 processor 版本。
- 不实现 App Key 轮换、撤销、过期、停用、多个 Key、宽限期或应用删除。
- 不使用包名或 App Key 证明 APK 真实性；签名证书、Play Integrity 和设备证明仍不在本期。
- 不增加鉴权缓存、消息队列、对象存储、异步解析、成员管理或 mapping 管理。

## Decisions

### 1. 使用系统生成 UUID 作为唯一应用主键

服务端在创建事务内使用 UUID v4 生成 `appId`。PostgreSQL 使用原生 `UUID`，Java 领域和服务层使用 `UUID`，HTTP JSON 和 Vue 路由使用标准 UUID 字符串；ClickHouse 使用 `UUID` 类型的 `app_id`。`appId` 是公开、不可修改的数据隔离标识，不是鉴权凭据，也不从包名推导。

选择随机 UUID 而不是包名作为主键，可以避免在所有外键、URL、mapping 目录和分析表中传播业务包名，并保持身份与展示元数据分离。选择 UUID v4 而不是自定义短 ID，可以直接复用 JDK/PostgreSQL/ClickHouse 原生能力，不增加编码和冲突处理协议。

### 2. 包名是必填身份字段，名称和描述是创建元数据

创建请求接受名称、描述和包名：

```json
{
  "name": "测试应用",
  "description": "用于测试",
  "packageName": "com.example.app"
}
```

只有 `packageName` 必须提供。服务端先去除首尾空白，再要求输入本身为小写 Android application ID：至少两个点分段，每段以小写字母开头，后续只允许小写字母、数字或下划线。服务端不静默把大写包名转为小写，避免把用户输入改成另一个实际 application ID。

`packageName` 在数据库中建立全局唯一约束，应用层预检只用于友好反馈，数据库约束负责并发竞争的最终正确性。重复创建统一映射为 `409 PACKAGE_NAME_CONFLICT`。同一个包名不再创建生产、预发布等多个应用；这些差异由事件现有 `environment`、`channel` 维度表达。

`name` 和 `description` 是可选创建字段。服务端去除名称首尾空白后按已有名称校验规则限制为非空且不超过 100 个字符；名称缺失或为空时使用 `packageName` 作为可展示的默认名称。描述缺失或为空时保存为空，并继续执行不超过 500 个字符的校验。创建响应、应用列表、应用切换器、面包屑和应用工作区标题均以 `name` 作为主要展示文本，`packageName` 作为辅助技术信息；创建后 `OWNER`/`ADMIN` 仍可修改名称和描述，但不能修改 `appId`、`packageName` 或 App Key。

选择可选名称/描述而不是强制填写，是为了保留现有创建页的用户习惯，同时满足“只有包名必填”；选择空名称回退包名而不是保存空字符串，是为了保证所有应用始终有稳定的可读展示名称。

### 3. 使用 V3 重建 PostgreSQL 应用领域表

新增 `V3__application_identity.sql`，不得修改 V1/V2。V3 首先检查 `project`、`project_member` 和 `project_ingest_credential` 均无业务记录；任一非空即明确失败，不执行删除或半迁移。检查通过后按外键依赖顺序删除旧的空业务表，并创建：

| 表 | 关键字段与约束 |
|---|---|
| `apm_app` | `app_id UUID PRIMARY KEY`、`package_name VARCHAR(255) UNIQUE NOT NULL`、显示名称、描述、创建者和时间 |
| `app_member` | `app_id + user_id` 联合主键、级联外键、`OWNER/ADMIN/DEVELOPER/VIEWER` 角色 |
| `app_ingest_credential` | `app_id` 一对一主键和级联外键、32 字节摘要唯一约束、AES-GCM 密文、12 字节 nonce、创建时间 |

包名只存放在 `apm_app`，不在凭据表重复保存。普通应用查询直接读取非敏感应用实体；App Key 鉴权使用摘要唯一索引查询凭据并关联应用，只投影 `appId + packageName`，不解密完整 Key。敏感凭据查询才加载密文并解密。

应用、Owner 和凭据由一个 `@Transactional` 服务方法创建；UUID 生成、唯一包名约束、Key 摘要冲突或任意持久化失败都会回滚整个创建结果。

### 4. App Key 保持摘要索引和可恢复加密

App Key 使用 JDK `SecureRandom` 生成 32 个随机字节，外部格式为 `apm_ak_` 加无填充 Base64URL。`SHA-256(UTF-8 完整 App Key)` 用作固定长度唯一查询摘要；AES-256-GCM 使用每条记录独立的 12 字节 nonce，并把 `appId + packageName` 的稳定组合写入 AAD，防止密文被移动到其他应用后仍可解密。

部署主密钥配置改为无默认值的 `APM_APP_KEY_ENCRYPTION_KEY`，必须是 Base64 编码的 32 字节值。旧 `APM_PROJECT_KEY_ENCRYPTION_KEY` 不作为别名或回退；启动脚本、测试配置和部署文档同步改名。主密钥必须稳定备份，丢失后摘要鉴权可能仍工作，但完整 App Key 无法恢复，因此属于阻断性部署故障。

每个应用恰有一个永久、不可修改、不可轮换且不过期的 App Key。凭据查询仍使用独立的 `GET /api/v1/apps/{appId}/ingest-credential`，仅 `OWNER`/`ADMIN` 可读，响应包含 `Cache-Control: no-store, private` 与 `Pragma: no-cache`；普通应用响应和日志不包含完整 Key、摘要、密文或 nonce。

### 5. 管理和查询 API 全面使用应用路由

管理 API 改为：

```text
GET    /api/v1/apps
POST   /api/v1/apps
GET    /api/v1/apps/{appId}
PATCH  /api/v1/apps/{appId}
GET    /api/v1/apps/{appId}/ingest-credential
```

Crash、卡顿和卡顿指标查询统一挂在 `/api/v1/apps/{appId}/...`。普通响应使用 `appId`、`packageName`、`name`、`description`、角色和时间字段；凭据响应使用 `appId`、`packageName`、`appKey`。旧 `/api/v1/projects/*` 不保留重定向，旧 `projectId/appPackageName/projectKey` JSON 字段由严格 DTO 作为未知字段拒绝。

成员关系继续使用 Session 登录主体和服务端 `app_member` 授权。非成员访问返回 `404 APP_NOT_FOUND`，角色不足返回 `403 FORBIDDEN`。`X-App-Id`、旧 `X-Project-Id` 和 `X-User-Project-Ids` 均不能证明身份或成员关系。

### 6. JSON v2 只接受 packageName 与 X-App-Key

`POST /ingest/v1/batches` 的入口地址和媒体类型保持不变，但请求头只接受 `X-App-Key`，公共事件必须使用 `schemaVersion=2` 和非空 `packageName`。旧 JSON v1、`appId` 包名字段、`X-Project-Key` 和 `apm_pk_` Key 均返回不可重试的版本、字段或 `401 INVALID_APP_KEY` 错误，不进入部分接受和存储流程。

鉴权流程为：

```text
X-App-Key
  → 头部存在性、前缀和合理长度检查
  → SHA-256 摘要
  → PostgreSQL 唯一索引查询
  → AuthenticatedApp(appId, packageName)
```

PostgreSQL/JPA 故障返回带 `Retry-After: 30` 的 `503 APP_AUTH_UNAVAILABLE`。完成反序列化后，服务端在事件级部分接受前遍历整个批次；任一事件缺少包名或与绑定值不一致时返回 `403 PACKAGE_NAME_MISMATCH` 且零写入。客户端不能在正文中声明系统 `appId`，存储只使用鉴权结果中的 `appId`。

### 7. 卡顿 manifest v3 以 processor 1.0.1 和真实 fixture 作为门禁

卡顿入口和媒体类型保持不变，但只接受 `X-App-Key` 与 `sourceManifest.schemaVersion=3`。v3 manifest 使用 `packageName`，不得包含旧 `appId` 或系统 `appId`。服务端只读取 processor 报告中的 `sourceManifest.packageName`，不提供 `appId` 回退。

包名校验必须发生在读取业务 mapping、生成指纹、归一化或写入分析存储之前。校验通过后，mapping 只按 `<mapping-root>/<appId>/<buildId>.txt` 解析，并继续执行规范化路径与 `toRealPath()` 包含校验。

当前仓库仍固定 processor 1.0.0；本次目标将依赖升级为用户提供的 `1.0.1`，并先确认该制品严格支持 v3 `packageName`。真实 fixture 已提供并通过 ZIP 条目、文件大小、SHA-256、manifest 版本、类型、包名和旧 `appId` 缺失的预检；仍必须使用 1.0.1 实际运行 processor，核对声明文件与 Sampling 字节/报告，再完成服务端首次接受、重复、mapping、错误包名和重启验证。不得通过改写 ZIP、为旧字段添加别名、跳过 processor 严格校验或伪造版本号绕过该门禁。

### 8. ClickHouse 使用 004 脚本重建空测试结构

ClickHouse 初始化脚本不是应用自动迁移；新增 `004_application_identity_schema.sql`，不修改 001–003。执行前由受控验证脚本查询全部受影响表必须为空；非空时停止并要求人工确认清理，004 本身不承担保留旧数据的迁移。

在空数据前提下，004 按物化视图、聚合表、详情表、事实表的依赖顺序删除旧对象，再以新结构重建：原 `project_id String` 改为 `app_id UUID`，原来保存 Android 包名的 `app_id` 改为 `package_name LowCardinality(String)`。排序键、物化视图、聚合、查询过滤、指纹输入和幂等查询全部使用 `app_id`。

所有 JSON、卡顿事实、详情和聚合的幂等键统一为 `appId + eventId`。mapping 目录、固定数据集、Grafana 变量和查询 API 响应同步改为应用身份。应用包名保留为可查询维度，但不承担租户隔离。

### 9. Vue 控制台只暴露清晰的应用身份

网页路由统一为 `/apps/:appId`，页面和组件改为“我的应用、创建应用、应用设置、应用切换”。创建页展示 `name`、`description`、`packageName` 三个输入，其中只有 `packageName` 带必填校验；应用列表、应用切换器、面包屑和工作区标题优先展示 `name`，同时展示包名、角色和更新时间；设置页展示只读 `appId/packageName`，并允许有权限用户维护显示名称和描述。

完整 App Key 默认不请求，触发查看后只保存在设置页组件局部内存，不进入 Pinia、URL、路由状态、Web Storage、日志或通用请求缓存。组件卸载、应用参数变化、凭据请求失败和会话失效时清空；过期请求不得把旧应用 Key 写入当前页面。

旧 `/projects/*` 前端路由显示未找到状态，不推断对应新 `appId`。所有 Crash、卡顿、筛选、Issue、事件详情和凭据状态在应用切换时重新加载或清除。

### 10. 错误码和配置名称不保留旧别名

身份相关稳定错误统一为：

| 错误码 | HTTP | 语义 |
|---|---:|---|
| `PACKAGE_NAME_CONFLICT` | 409 | 创建应用时包名已存在 |
| `APP_NOT_FOUND` | 404 | 应用不存在或当前用户无成员关系 |
| `INVALID_APP_KEY` | 401 | `X-App-Key` 缺失、格式错误、旧前缀或未知 |
| `APP_AUTH_UNAVAILABLE` | 503 | PostgreSQL App Key 鉴权暂不可用，带 `Retry-After: 30` |
| `PACKAGE_NAME_MISMATCH` | 403 | 正文包名与 App Key 绑定值不同，整请求零写入 |

旧 `PROJECT_ID_CONFLICT`、`PROJECT_NOT_FOUND`、`INVALID_PROJECT_KEY`、`PROJECT_AUTH_UNAVAILABLE` 和 `APP_PACKAGE_MISMATCH` 不作为 API 别名返回。错误响应不得包含完整 App Key、包名归属、数据库异常、ZIP 内容或服务端路径。

## Android/processor 后续修改清单

以下项目只生成清单，本次不修改 Android/processor 仓库：

当前已收到的真实验证输入：processor 候选版本为 `io.github.mashanshui:rhea-trace-processor:1.0.1`；fixture 为 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip`，其 manifest 包名为 `rhea.sample.android`，SHA-256 为 `1445628B2FA6D4ED055FDEDE218F950B1B44D20FFE6AE990F05869ACEB6C16FE`。以下清单仍以实际 processor 解析和服务端端到端结果为完成依据。

- [ ] Android 公共事件模型把 `schemaVersion` 从 1 升级到 2。
- [ ] 公共事件中的包名字段从 `appId` 改为 `packageName`，删除旧字段序列化和读取回退。
- [ ] JSON 批量上报请求头从 `X-Project-Key` 改为 `X-App-Key`，本地配置字段改为 `appKey`，示例 Key 前缀改为 `apm_ak_`。
- [ ] 卡顿 ZIP manifest 把 `schemaVersion` 从 2 升级到 3，并把包名字段从 `appId` 改为 `packageName`。
- [ ] ZIP producer、manifest 模型和 processor 严格 schema 同步拒绝 v2、旧 `appId` 和未知字段。
- [ ] processor 报告的 `sourceManifest` 输出 `packageName`，所有应用帧判定继续使用真实包名，不误用系统 UUID `appId`。
- [ ] 重新生成真实 v3 `.rheajank.zip` fixture，验证 ZIP 条目、声明大小、SHA-256、Sampling 数据和解析报告保持一致。
- [ ] 保持原始 `eventId`、原 ZIP 字节、应用内持久队列、重复上传和 `accepted/duplicate` 确认语义；不因协议改名引入新的队列框架。
- [ ] 将 `INVALID_APP_KEY`、版本/字段错误和 `PACKAGE_NAME_MISMATCH` 视为永久失败，将 `APP_AUTH_UNAVAILABLE` 与既有临时存储/繁忙错误视为可重试并遵守 `Retry-After`。
- [ ] 确保 App Key 不进入日志、异常、URL、版本库、ZIP、事件正文或持久诊断文件，只从应用安全本地配置注入请求头。
- [ ] 确认 `io.github.mashanshui:rhea-trace-processor:1.0.1` 支持 manifest v3，记录制品校验和与发布位置，并将 apm-server 依赖升级到该版本。
- [ ] 使用已提供的 `demo-jank-2308233515248871.rheajank.zip` 验证 ZIP 条目、声明大小、SHA-256、Sampling 数据和 processor 解析报告保持一致。
- [ ] 使用新 App Key 完成 JSON v2 与卡顿 v3 的首次接受、重复、错误 Key、错误包名、网络重试和服务重启联调。

## Risks / Trade-offs

- [同一包名全平台唯一后不能建立同包名多应用] → 使用 `environment`、`channel` 区分发布环境；若未来确需多租户复用包名，必须重新定义唯一域和授权模型。
- [重命名覆盖数据库、API、前端和分析表，遗漏一个字段会造成身份错位] → 使用禁止旧术语的静态扫描、严格 DTO、固定数据集、全链路测试和文档链接检查共同验收。
- [V3/004 需要删除旧空业务结构] → 两端都先执行非空前置检查；只允许在用户明确授权的测试环境清理，不对未知或生产数据执行破坏性动作。
- [主密钥变量改名可能导致启动失败] → 启动脚本在拉起数据库和进程前校验 `APM_APP_KEY_ENCRYPTION_KEY`；文档要求稳定迁移本地安全值，不回退旧变量。
- [processor 1.0.1 的 v3 支持或真实 ZIP 结果仍可能不符合预期] → 先锁定 1.0.1 并记录制品信息，再用已提供 fixture 完成字节、报告和端到端门禁；服务端不通过兼容适配掩盖外部不一致。
- [UUID 进入 URL 和 ClickHouse 后增加字段宽度] → 使用数据库原生 UUID 类型；首版规模下可读性和存储开销可接受，并换取明确稳定的内部身份。
- [包名可由攻击者伪造] → 继续把包名匹配定位为误配置和跨应用串数据保护，不宣传为 APK 身份证明。
- [永久且可查看的 App Key 泄露后不能原地处置] → 维持最小暴露、禁止缓存和日志的边界；轮换、撤销和删除仍作为后续独立能力。

## Migration Plan

1. 停止 apm-server、前端及测试上报，确认环境仅含可删除测试数据；记录 PostgreSQL、ClickHouse 和 processor 当前状态。
2. 清空 PostgreSQL 旧项目/凭据数据与 ClickHouse 事件、卡顿、详情和聚合测试数据；不按端口猜测服务归属，不删除数据库卷。
3. 将稳定主密钥配置名迁移为 `APM_APP_KEY_ENCRYPTION_KEY`，验证 Base64 解码后正好 32 字节，并删除旧变量依赖。
4. 执行 PostgreSQL V3，让旧空业务表重建为 `apm_app/app_member/app_ingest_credential`；验证外键、UUID、包名唯一约束和 JPA schema。
5. 执行 ClickHouse 004 前置空表检查，再重建应用身份结构与物化视图；验证不存在旧 `project_id` 和包名语义的 `app_id`。
6. 部署后端和前端应用语义改动，通过网页填写名称、描述和唯一包名创建应用，验证名称优先展示、可选字段默认值、系统 `appId` 和 App Key，以及角色、缓存和敏感信息边界。
7. 使用 JSON v2 完成首次接受、重复、旧 v1 拒绝、错误 Key、包名不匹配和鉴权库故障冒烟。
8. 将 apm-server processor 依赖升级到 1.0.1，使用已提供的真实 v3 fixture 运行 ZIP 解析、mapping、首次接受、重复上传、错误包名和重启验证；任一字节或报告不一致时保持门禁未完成。
9. 执行后端、前端、PostgreSQL、ClickHouse、浏览器、日志/产物泄密、文档链接和 OpenSpec 严格验证。只有所有门禁通过后才能标记任务完成或进入归档确认。

回滚不恢复已清空的测试数据。代码回滚需要同时恢复旧 V1/V2 应用版本、旧主密钥变量和旧客户端协议；V3/004 已执行后不能仅回滚 JAR，必须在停机状态按旧结构重新初始化空测试数据库。由于本次不承诺兼容或数据迁移，生产环境不得套用该回滚流程。
