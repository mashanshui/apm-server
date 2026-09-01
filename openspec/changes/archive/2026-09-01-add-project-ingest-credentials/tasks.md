## 1. 数据与实施前置检查

- [x] 1.1 检查脚本状态文件、后端/前端进程、PostgreSQL/ClickHouse/Grafana 容器和相关端口归属，记录本次实施前运行状态
- [x] 1.2 查询 PostgreSQL 旧项目、成员与凭据记录以及 ClickHouse 全部事实、详情、聚合表行数，确认仅包含可删除测试数据并保存精确清理目标
- [x] 1.3 按服务归属停止 apm-server 本地服务，在执行破坏性数据库操作前再次验证目标数据库、表和容器均属于当前项目
- [x] 1.4 清理已确认的 PostgreSQL 与 ClickHouse 测试业务数据，不删除数据库卷、用户账号或未知数据，并验证受影响业务表为空
- [x] 1.5 更新实施与验证脚本，使 PostgreSQL V3 和 ClickHouse 004 在旧业务数据非空时明确失败，不执行静默删除或半迁移

## 2. PostgreSQL 应用身份模型

- [x] 2.1 新增 `V3__application_identity.sql`，保持 V1/V2 不变，在空数据前提下按外键顺序移除旧业务表并创建 `apm_app`、`app_member`、`app_ingest_credential`
- [x] 2.2 为 `apm_app.app_id` 使用 UUID 主键，为 `package_name` 增加小写 Android 包名检查和全平台唯一约束，并保留显示名称、描述、创建者和时间字段
- [x] 2.3 为 `app_member` 建立 `app_id + user_id` 联合主键、级联外键、角色检查和用户应用列表索引
- [x] 2.4 为 `app_ingest_credential` 建立一对一级联外键、32 字节摘要唯一约束、密文、12 字节 nonce 和创建时间约束，不重复保存包名
- [x] 2.5 将项目 JPA 实体、成员 ID、凭据实体、投影和 Repository 改为应用语义与 UUID `appId`，确保普通应用查询不加载敏感凭据
- [x] 2.6 扩展 PostgreSQL/Testcontainers 测试，覆盖 V3 非空保护、表结构、UUID、包名格式/唯一性、外键级联、摘要唯一性和 JPA schema 校验

## 3. App ID、App Key 与原子创建

- [x] 3.1 将 `packageName` 设为创建请求唯一必填的身份字段，去除首尾空白并拒绝大写、单分段、非法字符和超长包名，不执行静默小写转换
- [x] 3.2 使用 UUID v4 生成公开且不可修改的 `appId`，并覆盖系统生成与响应序列化测试
- [x] 3.3 将 Key 格式改为 `apm_ak_` 加 32 字节 `SecureRandom` Base64URL，保留 SHA-256 摘要和有限冲突重试并覆盖格式、熵与唯一性测试
- [x] 3.4 将 AES-256-GCM AAD 改为 `appId + packageName`，覆盖篡改、错误主密钥、跨应用密文移动和跨重启恢复测试
- [x] 3.5 将保护配置改为无默认值的 `APM_APP_KEY_ENCRYPTION_KEY`，同步测试配置和启动前校验，明确拒绝旧 `APM_PROJECT_KEY_ENCRYPTION_KEY` 回退
- [x] 3.6 在单个事务中原子创建应用、Owner 成员关系和 App Key，覆盖 UUID/包名/凭据任一步失败全部回滚及并发重复包名只成功一次
- [x] 3.7 更新敏感信息测试，确保创建、解密、鉴权和失败路径的数据库明文、日志、异常、HTTP 普通响应和构建产物均不包含完整 App Key
- [x] 3.8 扩展创建 DTO 和原子创建事务，接收可选 `name`/`description`，名称为空时回退为 `packageName`、描述为空时保存为空，并覆盖字段校验、默认值和响应测试

## 4. 应用管理 API、成员授权与错误语义

- [x] 4.1 将管理路由改为 `GET/POST /api/v1/apps` 与 `GET/PATCH /api/v1/apps/{appId}`，使用 UUID 路径参数并删除 `/api/v1/projects/*` 映射
- [x] 4.2 将普通请求/响应 DTO 改为 `appId/packageName/name/description/role/createdAt/updatedAt`，严格拒绝旧 `projectId/appPackageName/projectKey` 和未知字段
- [x] 4.3 实现按名称、包名或 `appId` 搜索当前用户应用，并将并发唯一约束冲突稳定映射为 `409 PACKAGE_NAME_CONFLICT`
- [x] 4.4 将成员服务和授权服务改为 UUID `appId`，保持所有角色可读、Owner/Admin 可编辑、Developer/Viewer 只读和非成员隐藏语义
- [x] 4.5 将凭据接口改为 `GET /api/v1/apps/{appId}/ingest-credential` 和 `appId/packageName/appKey` 响应，保留角色矩阵、`no-store/private`、`Pragma: no-cache` 和局部解密边界
- [x] 4.6 将身份相关错误统一为 `PACKAGE_NAME_CONFLICT`、`APP_NOT_FOUND`、`INVALID_APP_KEY`、`APP_AUTH_UNAVAILABLE`、`PACKAGE_NAME_MISMATCH`，删除旧错误码返回分支
- [x] 4.7 增加 API 集成测试，覆盖创建唯一包名、UUID ID、更新身份字段拒绝、成员权限、旧路由 404、旧字段 400、凭据缓存头和普通响应不泄密
- [x] 4.8 补充创建 API 的名称、描述、包名组合测试，验证只有 `packageName` 必填、名称/描述默认值、名称展示字段和已有重复包名/原子回滚语义不回归

## 5. JSON v2 上报协议

- [x] 5.1 将公共事件模型、严格 schema 和固定测试构造从 `schemaVersion=1/appId` 改为 `schemaVersion=2/packageName`，删除旧字段与默认包名补值
- [x] 5.2 将上报鉴权请求头改为 `X-App-Key`，按 `apm_ak_` 完整 Key 摘要查询并返回不可变 `AuthenticatedApp(appId, packageName)`
- [x] 5.3 对缺失、空值、旧前缀、旧 `X-Project-Key` 和未知 App Key 在正文读取前统一返回 `401 INVALID_APP_KEY`
- [x] 5.4 将 PostgreSQL/JPA 鉴权故障映射为带 `Retry-After: 30` 的 `503 APP_AUTH_UNAVAILABLE`，不得误映射为 401 或事件存储错误
- [x] 5.5 在事件级部分接受前校验整个 JSON v2 批次的 `packageName`，任一缺失或不匹配返回不可重试错误且整批零写入
- [x] 5.6 更新 Crash/Jank JSON 固定数据集和集成测试，覆盖 v2 成功、重复、v1 拒绝、正文旧 `appId` 拒绝、错误 Key、包名不匹配和鉴权库故障

## 6. 卡顿 manifest v3 与 processor 门禁

- [x] 6.1 将卡顿服务身份对象、报告映射和测试报告改为 `appId/packageName`，只读取 `sourceManifest.schemaVersion=3` 与 `sourceManifest.packageName`，不回退旧 `appId`
- [x] 6.2 在读取业务 mapping、生成指纹、归一化和写入前完成 v3 包名匹配，缺失或不匹配返回契约错误或 `403 PACKAGE_NAME_MISMATCH` 且零业务读取/写入
- [x] 6.3 将 mapping 解析器改为 `<mapping-root>/<appId>/<buildId>.txt`，继续执行规范化与 `toRealPath()` 包含校验并覆盖跨应用和符号链接逃逸测试
- [x] 6.4 将卡顿幂等、指纹、事实和详情身份改为 `appId + eventId`，覆盖首次接受、重复、部分写入修复、错误 Key、v2 拒绝和旧字段拒绝
- [x] 6.5 使用模拟 parser 补充 v3 `packageName`、attempted/expected/parsed/missing 采样数量、主线程缺失、mapping 存在/缺失和稳定错误测试，不把模拟结果声明为真实 ZIP 兼容证明
- [x] 6.6 将 apm-server 的 processor 依赖从 `1.0.0` 升级到 `io.github.mashanshui:rhea-trace-processor:1.0.1`，确认制品坐标、发布位置和校验信息，并验证其支持 v3 `packageName` manifest
- [x] 6.7 使用 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip` 运行 processor 集成测试和字节/报告检查；已知该 ZIP 为 v3、`RHEA_JANK`、`rhea.sample.android` 且 SHA-256 为 `1445628B2FA6D4ED055FDEDE218F950B1B44D20FFE6AE990F05869ACEB6C16FE`，但实际解析通过前不得勾选

## 7. ClickHouse 应用身份结构与查询

- [x] 7.1 新增 `004_application_identity_schema.sql`，保持 001–003 不变，并提供受控空表预检与按依赖顺序重建物化视图、聚合表、详情表和事实表的流程
- [x] 7.2 将租户隔离列从 `project_id String` 改为 `app_id UUID`，将原包名列 `app_id` 改为 `package_name LowCardinality(String)`，同步排序键、TTL、物化视图和聚合状态
- [x] 7.3 将 ClickHouse 写入、读取、幂等、Crash/卡顿查询、指标聚合、指纹输入和数据源投影全部改为 UUID `appId` 与独立 `packageName`
- [x] 7.4 更新 ClickHouse 固定数据集、验证脚本和 Grafana Dashboard 变量/SQL，禁止旧 `project_id` 和包名语义 `app_id` 残留
- [x] 7.5 使用真实 ClickHouse 执行 004、写入、查询、重复、聚合和空表保护验证，并确认旧表结构与测试数据均未残留

## 8. Vue 应用工作区

- [x] 8.1 将前端项目类型、Store、API 客户端和错误处理改为应用语义与 `appId/packageName/appKey`，确保通用 App 类型不包含 App Key
- [x] 8.2 将路由从 `/projects/:projectId` 全部改为 `/apps/:appId`，同步 Crash、卡顿指标、Issue、事件详情和设置导航，并让旧路由进入未找到状态
- [x] 8.3 将“我的项目、创建项目、项目设置、项目切换”等页面和组件统一改为应用术语，创建页保留名称、描述和小写包名输入，仅包名必填并提供唯一冲突提示
- [x] 8.4 在应用列表展示显示名称、包名、角色和更新时间，在设置页展示只读 `appId/packageName`，保留 Owner/Admin 修改名称和描述能力
- [x] 8.5 将凭据交互改为按需查看、隐藏和复制 `appKey`，继续使用页面局部状态并在卸载、应用切换、请求失败和会话失效时清空
- [x] 8.6 更新前端测试，覆盖包名校验/冲突、UUID 路由、角色矩阵、旧路由、切换清理、过期请求隔离及 URL/Web Storage/Store 不包含 App Key
- [x] 8.7 更新创建表单、类型、Store 和测试，覆盖名称/描述可选、包名必填、空值默认规则，并确认应用列表、切换器和工作区始终以 `name` 为主要展示文本

## 9. 文档与 Android/processor 后续清单

- [x] 9.1 更新知识库 `00`–`13` 受影响主题和总入口，统一应用身份模型、UUID `appId`、唯一 `packageName`、永久 `appKey`、JSON v2、manifest v3 和验证边界
- [x] 9.2 更新 `docs/api/` 的认证、应用管理、Crash、卡顿、堆栈产物和错误码契约，删除旧项目路由、字段、请求头、Key 前缀和错误码示例
- [x] 9.3 更新 `docs/client-integration/` 的 JSON、卡顿监控、ZIP 上传和 manifest 文档，明确 `X-App-Key`、`packageName`、版本升级、永久/可重试错误和无兼容策略
- [x] 9.4 在客户端接入文档中落地 design 的 Android/processor 后续修改清单，覆盖事件模型、ZIP producer、processor 严格 schema、真实 fixture、发布坐标、持久重试和 Key 安全
- [x] 9.5 更新前端知识库、部署、本地启动、ClickHouse/Grafana、数据库初始化和环境变量说明，记录 `APM_APP_KEY_ENCRYPTION_KEY` 与 processor v3 部署门禁
- [x] 9.6 搜索并检查全部 Markdown 链接和术语，确保对外文档中 `appId` 只表示系统 UUID、`packageName` 只表示 Android 包名、`appKey` 只表示上报凭据
- [x] 9.7 同步 API、客户端接入、知识库和前端知识库，明确创建请求保留 `name`/`description`、仅 `packageName` 必填、名称优先展示，并登记 1.0.1 与真实 ZIP 验证输入

## 10. 验证与交付

- [x] 10.1 运行后端单元与集成测试以及 `./gradlew.bat clean build`，记录测试数量、失败和跳过情况
- [x] 10.2 运行前端单元测试、类型检查和生产构建，并在 1280/1440/1920 PC 视口检查创建、列表、设置、角色只读、App Key 和错误状态
- [x] 10.3 使用真实 PostgreSQL 验证 V3、唯一包名、原子创建、重启恢复、权限和非空迁移保护
- [x] 10.4 使用真实 ClickHouse 验证 004、JSON v2 首次接受/重复、查询聚合、旧结构清理和 PostgreSQL 鉴权故障 503
- [x] 10.5 使用 processor `1.0.1` 和 `F:/AndroidStudioProjects/btrace/btrace-android/build/test-stack/demo-jank-2308233515248871.rheajank.zip` 完成卡顿 ZIP 首次接受、重复、mapping、错误 Key、错误包名、v2 拒绝和重启验证；未实际通过时不得勾选
- [x] 10.6 扫描数据库、HTTP 响应、服务端日志、浏览器状态、Web Storage、URL 和构建产物，确认不包含完整 App Key 或旧身份误映射
- [x] 10.7 对生产代码、测试、配置和文档执行旧术语静态扫描，只允许迁移脚本、拒绝旧契约测试和历史说明中的明确旧术语
- [x] 10.8 在创建字段和展示语义实现后重新运行后端测试/构建、前端测试/类型检查/生产构建及真实 API 冒烟，确认名称、描述、包名必填与名称优先展示不回归
- [x] 10.9 运行 `openspec validate add-project-ingest-credentials --strict`、Markdown 链接检查和 `git diff --check`，确认全部任务与真实验证门禁完成后再请求归档选择
