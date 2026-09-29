# Agent 查询链路本地与云端验收记录

本记录对应 `add-agent-query-mcp` 的本地及云端调试验收，时间为 2026-09-29。本地验收使用隔离的 PostgreSQL 16、ClickHouse 26.7.3.19、Spring Boot 后端、TypeScript MCP、Vite 前端和 MCP Inspector CLI 2.8.0；应用、账号和事件均为临时合成数据。本机代理使用回环端口；云端另行部署并完成入口冒烟。当前 MCP 允许 HTTP 调试接入，云端结果不等同于生产验收。

## 真实数据链路

通过 Session、CSRF 管理 API 创建两个测试应用，并用各自 `X-App-Key` 上报 Crash、卡顿和内存事件。每个应用首批接受 15 条，重复事件返回 `duplicate=1`。随后为两个应用分别创建 90 天查询 Token，调用 Agent HTTP 的当前应用、Crash 总览及问题、卡顿总览/FPS/挂起、内存概览和内存异常列表。请求均只返回 Token 绑定应用的数据；A 应用 Token 查询 B 应用 Crash 事件返回 404。内存异常无数据返回正常空结果。

官方 MCP SDK 真实连接两个应用并发现 19 个工具，完成 Crash、卡顿、内存查询及详情分片；Inspector CLI 2.8.0 使用手工 Bearer 对真实 MCP 服务执行工具发现、当前应用、Crash 总览与事件详情、FPS、内存概览及无数据内存异常查询。详情按 section 和 continuation 获取，客户端重组后与 SHA-256 摘要核对。缺失事件以工具错误返回；触发 Token 频率上限时返回 429。停止 MCP 服务后，Inspector 的连接失败；撤销 Token 后，新的 MCP 连接及 Agent HTTP 查询均被拒绝，重复撤销返回 204。所有本次创建的查询 Token 均已撤销。

真实浏览器以测试管理员进入应用设置页，并在用户确认后点击创建 30 天查询 Token。页面只在创建结果中展示一次完整值；从页面复制的同一 Token 经本地 Nginx 代理完成 MCP 初始化及应用、Crash 总览、FPS、内存概览工具调用，四个结果均指向当前应用，FPS/内存为 `ok`。同一 Token 直接调用 Agent HTTP 当前应用返回 200，查询另一测试应用的 Crash 事件返回 404。随后在网页确认撤销，列表显示 `REVOKED`，该 Token 经代理 MCP 初始化和直连 Agent HTTP 均返回 401。另一测试应用也已用独立 Key 写入四条合成事件，用于隔离验证。浏览器页面使用本地 Vite 到真实后端的代理；无真实生产数据。

另将仓库 Nginx 配置仅替换本地上游地址后，在隔离容器运行 HTTP 代理冒烟：有效 Token 的 MCP 初始化返回 200；非法 Origin 和 Host 均返回 403；允许响应的 `Cache-Control` 包含 `no-store`。代理所用测试 Token 重复撤销均为 204，随后直连 Agent HTTP 与代理 MCP 均返回 401。该冒烟不覆盖外层 HTTPS 证书、真实域名或公网网关。

## 自动化与边界

- MCP 测试覆盖 19 工具路由映射、输入白名单、双应用隔离、字段语义、详情分片与摘要、输出上限、错误/取消、Host/Origin、HTTP 调试接入和撤销，15 个用例通过；TypeScript 类型检查和构建通过。
- 云端部署脚本的最小 MCP 打包内容另行复制到隔离临时目录，并从该目录成功构建 Docker 镜像；脚本语法检查通过。历史默认私钥 `~/.ssh/apm_cloud` 不在本机；用户授权新 Mac 公钥后，已用 `~/.ssh/apm_cloud_mac` 严格校验主机密钥并完成云端部署，详情见[SSH 说明](cloud-ssh.md)和下文云端冒烟。
- 前端 42 个测试文件、106 个用例通过；类型检查和生产构建通过。组件和模拟浏览器结果不能代替真实浏览器创建 Token。
- 后端新功能的定向测试及 ClickHouse 真实查询测试通过。全量测试执行 239 个用例，1 个与本变更无关的 Rhea fat JAR 本地摘要断言失败，3 个跳过；失败原因和测试入口见[后端测试与质量保障](../backend/docs/knowledge-base/06-测试与质量保障.md)。
- Crash 使用 1 万、10 万、100 万合成事件测量；百万级宽查询触发明确资源限制，窄时间窗按下推条件扫描，具体环境与数值见[Crash 查询基线](../backend/docs/knowledge-base/crash-query-performance-baseline.md)。
- 尚未验证真实 HTTPS 反向代理、实际 Android 卡顿 ZIP 全链路、生产容量及跨浏览器行为。云端仅完成有限的部署和无凭据入口冒烟，不能据此宣称完整云端查询链路已验收。

验收文档只保留脱敏统计，不记录完整 Token、应用 Key 或实际密钥。临时脚本将创建响应保存在权限为 600 的本地临时文件中，测试结束后撤销 Token 并删除文件。

## OpenSpec 场景核对

| 规格 | 已验证证据 | 尚未覆盖 |
|---|---|---|
| 应用查询 Token | 角色/CSRF/期限/一次展示/到期/撤销/配额自动化，真实网页创建与撤销、同一凭据 MCP 与 Agent HTTP 复用、跨应用 404 | 多浏览器和正式运维轮换 |
| APM MCP 查询 | 19 工具、双应用隔离、分片与资源限额、错误/取消自动化；Inspector CLI 2.8.0 和本地真实三类数据；HTTP 代理和浏览器端到端；云端五容器健康及无凭据入口冒烟 | 云端有效 Token 的工具查询、受信 HTTPS 网关与生产容量 |
| Crash 查询 | PostgreSQL/ClickHouse 固定集、游标、去重、预算失败和 1 万/10 万/100 万合成事件基线；本地真实网页与 MCP Crash 查询 | 生产数据规模与长期并发负载 |

后端全量回归的 Rhea 制品摘要断言仍失败，故不将全量测试写成通过。云端有效 Token 查询、受信 HTTPS 与生产容量验收仍待完成；云端部署和无凭据入口冒烟记录如下。

## 2026-09-29 本地启动复验

在未跟踪的 `.env.local` 中设置 `MCP_ENABLED=true` 和 `APM_AGENT_QUERY_ENABLED=true` 后，执行 `bash scripts/start-dev.sh --build`：后端 `bootJar`、前端生产构建和三个运行镜像构建成功，PostgreSQL、ClickHouse、后端、MCP、Web 五个容器均为 `running healthy`。本机 Nginx `/login` 返回 200，未携带 Token 的 `/mcp` 返回 401，表明 MCP 入口已启用且仍要求查询 Token；此前默认关闭时返回 503。该复验没有创建新 Token。

## 2026-09-29 云端部署与入口冒烟

用户通过腾讯云轻量应用服务器 OrcaTerm 将新 Mac 公钥追加到 `ubuntu` 的 `authorized_keys` 后，本机以严格主机密钥校验、免交互方式登录 `124.221.252.121`。部署前云端只有 PostgreSQL、ClickHouse、后端和 Web 四个健康容器；`.env.cloud` 权限为 `600`，没有配置两个查询开关。仅在该文件中设置 `MCP_ENABLED=true`、`APM_AGENT_QUERY_ENABLED=true`，并将当前服务器 IP 与回环地址加入 MCP Host/Origin 白名单，保留原有其他配置。

本地此前已用 `bash scripts/start-dev.sh --build` 构建当前 JAR 和前端产物，随后运行 `bash scripts/deploy-cloud.sh --skip-build --identity-file "$HOME/.ssh/apm_cloud_mac"`。部署脚本上传最小包、在云端重建后端/Web/MCP 镜像并更新 Compose；PostgreSQL、ClickHouse、后端、MCP、Web 五个容器全部达到 `running healthy`。云端 PostgreSQL 的 Flyway V5 迁移记录为 `success=true`。从本机访问公网 HTTP：`/healthz` 返回 200 和 `UP`，`/login` 返回 200；MCP 初始化请求未携带 Token 或携带无效 Token 均返回 401，Agent HTTP `/api/agent/v1/application` 未携带 Token 返回 401；非法 Origin 或 Host 的 MCP 请求返回 403，响应携带 `Cache-Control: no-store`。

本次未在云端创建真实查询 Token，因此没有验证有效 Token 的 MCP 工具调用、应用隔离或撤销；这些行为的真实数据端到端证据仅来自上文本地验收。云端仍只有 HTTP 入口，受信 HTTPS 网关及生产容量尚未验证。云端未生成或记录测试 Token，未删除数据库卷或既有业务数据。
