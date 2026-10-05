# 接口正确性修复云端部署与验收（2026-10-05）

用户在完成本地 Rhea 制品核验和摘要更新后，明确授权部署云端测试。`fix-backend-api-correctness` 的后端、前端与 MCP 已发布到现有 `124.221.252.121` HTTP 调试环境；真实网关、PostgreSQL/JDBC 会话、ClickHouse、MCP 和登录后的 Edge 页面验证通过。本记录补充此前“本轮未部署云端”的历史记录，不扩大生产验收结论。

## 发布制品与备份

SSH 使用既有 Mac 私钥和严格主机密钥校验，连接参数见[SSH 说明](cloud-ssh.md)。发布前确认已有五个容器健康、磁盘空间足够，保留服务器权限为 `600` 的 `.env.cloud`、数据库卷及既有应用。未修改密钥、密码、查询或分析开关。

| 项目 | 核验值 |
|---|---|
| 本地构建 | 后端 Java 21 `clean build` 通过；290 项中 287 通过、0 失败、3 条件跳过；前端 `npm run build` 通过 |
| processor | 保持 `1.0.2`；固定 SHA-256 为 `0c1ac6952ab92526bec24355523048b5e10b14a6ec8a85c9c03070cc67ddb64f` |
| 发布包 | `build/cloud-deploy/apm-server-cloud-20261005-142045.zip` |
| 发布包 SHA-256 | `c2c21024055396b788bfdba7f2375bcafa75be412d602cc62ef564e6a9981440` |
| Boot JAR SHA-256 | `1be41e58fb6a863efa4bfbb34e847593e486bdf0223a71db0161dc233e03e59a`，与云端 `/app/app.jar` 一致 |
| Web index SHA-256 | `bc7180fb591cb6f6743d39f295b29738c9221e4d224a5e26e20727aa51d4e84b`，与云端 Nginx 文件一致 |
| 备份目录 | `/home/ubuntu/apm-server-backups/20261005-142009-api-correctness`，权限 `700` |
| 备份内容 | PostgreSQL 自定义格式 `postgres.dump`、旧运行材料 `runtime-files.tar.gz` 均为 `600`；保留旧 backend/web/mcp 镜像备份标签 |

执行 `bash scripts/deploy-cloud.sh --skip-build --identity-file "$HOME/.ssh/apm_cloud_mac"`，后端、Web、MCP 镜像重建并更新成功；PostgreSQL 和 ClickHouse 容器及数据卷保持原位。最终五容器均为 `running healthy`，Nginx `-t` 通过，`/healthz` 返回 `UP`。Flyway 发布前后均为 V14 成功，本变更没有新增迁移。备份创建已验证，本轮没有恢复演练，也没有创建 ClickHouse 全量备份。

## 样本与测试方式

接口脚本在服务器通过 Nginx 的 `127.0.0.1` HTTP 入口及正式 Host 请求，避免把测试凭据发送到公网链路；数据库验证使用容器内认证。既有登录配置和临时 Token 只在进程内使用，结果不记录密码、App Key、Token、Session 或 CSRF 值。用户自行在 Edge 登录云端，页面通过公网 Nginx 请求正式后端，没有使用模拟 API。

保留两个专用验收应用：

- 主应用：`8c13a72c-d446-47ca-8a9e-1fedcc351667`，包名 `com.example.apmcorrectness20261005142826`，名称“云端接口正确性验收 2026-10-05（已验证）”。
- 空隔离应用：`0d9996c2-2184-4048-8327-2cfb9844f551`，用于不同应用查询 Token 的资源隔离验证。

本轮 120 个卡顿 ZIP 来自仓库历史二进制夹具的受控改写：保留主线程采样，替换为合成 UUID v4 进程身份、事件/会话/设备标识及场景，更新 manifest、二进制记录数和摘要。每个 ZIP 均经正式 processor 解析和存储。该样本不是新 Android 真机采集，不能用于证明设备 SDK 生命周期。另上传 3 份合成内存报告，路径组合为 `[A,A,B]`、`[A]`、`[A]`，覆盖同报告重复路径及三个版本。

脱敏结果在本机被忽略的 `build/cloud-validation/result.json` 和服务器 `/home/ubuntu/apm-server-validation/20261005-api-correctness/result.json`；服务器目录权限 `700`。记录共有 110 项成功检查，包含请求状态、断言及重复分页请求，不等于 110 个独立 JUnit 用例。

## 实际结果

| 范围 | 通过的云端行为 |
|---|---|
| JDBC 登录 | 无旧会话登录成功；数据库有认证会话且超时 28,800 秒；再次登录轮换 Session/CSRF，旧数据库会话消失，旧 Cookie 返回 401 |
| CSRF 与 PATCH | 新 Cookie 搭配旧 CSRF 返回 403；新 CSRF 的首个 PATCH 成功；省略 description 保留、null 清空、未知字段拒绝；空对象不改变已持久化 updatedAt |
| 框架错误 | 非法 UUID 为 400/INVALID_PARAMETER，坏 JSON 为 400/INVALID_REQUEST_BODY，405 保留 Allow，415 为 UNSUPPORTED_MEDIA_TYPE；Worker 缺少名称为 400/VALIDATION_FAILED |
| 卡顿解析与幂等 | 120 个正式 ZIP 首次接受，同字节重试 duplicate；详情能读取精确/估算耗时与 454 个已解析采样 |
| 卡顿完整统计 | limit=1/20/50 总览均为 120 事件、7 会话、3 设备、120 可归组事件；精确 P50/P90/P99 均为 2303.973958 ms，dataSource=clickhouse |
| 卡顿分页 | 遍历得到 60 个不同 Issue；最大 Issue 为 61 个事件，全部分页不重不漏；默认时间窗续页保持，旧游标为 400/INVALID_CURSOR |
| 内存报告 | 首次接受、重试 duplicate；问题 A=3、B=1，总问题 2、总次数 4；A 的版本集合为 v0/v1/v2；pageSize=1 不改变总计，超末页保留总计，趋势总次数 4 |
| Agent 与 MCP | Agent 统计与网页一致，显式不同 appId 拒绝；MCP initialize/tools/list 成功并发现 19 个工具，总览/报告一致、60 Issue 完整续页、INVALID_CURSOR 正确传播；不同应用 Token 读取主应用详情返回 404/EVENT_NOT_FOUND |
| 重启与退出 | 实际重启 backend 容器后原 JDBC Cookie 恢复身份，首个 PATCH 成功；退出 204，之后原会话为 401；用户的浏览器登录态在重启后刷新也保持 |

PATCH 时间比较以更新前 GET 的数据库持久化值为基准；初版验收脚本错误地将响应中的纳秒时间和 PostgreSQL 回读的微秒时间比较，修正脚本后通过，没有因此修改产品代码。最后独立只读查询确认应用从 5 个增至 7 个、用户仍为 1 个、两个专用应用的未撤销测试 Token 为 0；没有删除原应用或账号。

## 真实页面与截图

Edge 实际登录后，卡顿页显示 ClickHouse 来源与完整 120 事件；点击“加载更多”从 50 个 Issue 到 60 个并显示全部；下钻最大 Issue，从 50 个事件加载到 61 个并显示全部，详情展示采样证据。内存报告页显示 2 个问题、总次数 4、A=3/B=1 与完整版本集合。以下是正式云端页面截图，未使用模拟 API：

- [卡顿总览](../frontend/docs/knowledge-base/evidence/cloud-jank-overview-20261005.png)
- [卡顿详情](../frontend/docs/knowledge-base/evidence/cloud-jank-detail-20261005.png)
- [内存报告](../frontend/docs/knowledge-base/evidence/cloud-memory-reports-20261005.png)

[打开保留的验收页面](http://124.221.252.121/apps/8c13a72c-d446-47ca-8a9e-1fedcc351667/janks?from=2026-10-05T06%3A20%3A18.965Z&to=2026-10-05T06%3A40%3A18.965Z)。默认最近 24 小时到期后应使用这个固定时间窗查看样本。本轮没有人为制造浏览器失效游标；其页面恢复操作仍以[本地模拟 API 验收](../frontend/docs/knowledge-base/06-测试与质量保障.md#2026-10-04-卡顿分页最终回归)为证，云端错误传播由 HTTP/MCP 直接验证。

## 仍未覆盖与文档归属

生产 HTTPS、Secure=true、数据库网络收敛、多实例/并发容量、长期稳定性、备份恢复、真实 release mapping 专项和 Android 完整生命周期仍需各自验收。现有三个条件跳过项及启动条件见[后端最新构建记录](../backend/docs/knowledge-base/06-测试与质量保障.md#2026-10-05-rhea-制品校验与摘要更新)。本轮有限样本不能替代[本地查询资源基线](../backend/docs/knowledge-base/backend-api-query-validation.md)或生产性能证明。

本轮同步文件如下，并从 OpenSpec 任务记录关联本页：

- 根知识库：[README](knowledge-base/README.md)、[00-当前实现与验证边界](knowledge-base/00-当前实现与验证边界.md)、[07-部署与运维](knowledge-base/07-部署与运维.md)、[08-测试与质量保障](knowledge-base/08-测试与质量保障.md)。
- 后端知识库：[00-当前实现与验证边界](../backend/docs/knowledge-base/00-当前实现与验证边界.md)、[05-构建配置与本地运行](../backend/docs/knowledge-base/05-构建配置与本地运行.md)、[06-测试与质量保障](../backend/docs/knowledge-base/06-测试与质量保障.md)。
- 前端知识库：[06-测试与质量保障](../frontend/docs/knowledge-base/06-测试与质量保障.md)、[07-部署安全与运行边界](../frontend/docs/knowledge-base/07-部署安全与运行边界.md)，以及三张页面截图。
- API 文档：此前实现已同步的契约继续有效；本次部署不新增 HTTP 契约，无需新增改动。
- 客户端文档：上传、队列及重试步骤不变，无需新增改动。

最终 41 篇修改/新增 Markdown 的本地链接、锚点及围栏检查通过，`git diff --check` 和 `openspec validate fix-backend-api-correctness --strict` 通过；OpenSpec apply 状态为 all_done，任务 31/31 完成，未归档。

## 2026-10-05 后续同步归档

用户随后授权同步归档。六个能力的增量已同步至正式规范，19 个正式规范严格校验通过；本变更连同全部规划材料和 31/31 任务移至[2026-10-05 归档](../openspec/changes/archive/2026-10-05-fix-backend-api-correctness/tasks.md)。前文“未归档”为云端验收结束时的历史状态；本次仅同步规范和维护文档入口，没有再次部署或改变接口/客户端契约。

归档后复核六份增量均与正式规范对应、`.openspec.yaml` 保留、原活动目录消失；`openspec list --json` 没有活动变更。48 篇修改/新增 Markdown 的链接、锚点和围栏检查通过，`git diff --check` 通过。归档任务校验中本变更有效；CLI 的 `--archived` 会检查全部归档，仍因三份历史变更各有一项未完成任务而整体失败：`2026-08-26-add-auth-project-workspace`、`2026-08-27-add-jank-monitoring-server`、`2026-09-13-add-memory-leak-reports`。这些既有记录本次未修改，不能将全库归档校验记为全绿。

归档阶段的知识库同步文件为根 [README](knowledge-base/README.md)/[09-实施路线图](knowledge-base/09-实施路线图.md)、后端 [README](../backend/docs/knowledge-base/README.md)、前端 [README](../frontend/docs/knowledge-base/README.md)及本页；API 与客户端文档没有新增改动。
