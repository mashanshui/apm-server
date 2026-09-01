## Context

现有 `/ingest/v1/batches` 在内存中把 JSON 读取为树，再绑定领域对象和执行单事件大小校验；它适合结构化事件，却不适合直接承接 btrace 已生成的 `.rheatrace.zip`。btrace 的 `StackParser` 能从 `InputStream` 同步解析产物并返回 `RHEA_STACK_REPORT` JSON，但 HTTP、项目鉴权、mapping 选择、并发、错误码和持久化均由业务服务负责。

当前项目没有对象存储、任务队列或 mapping 注册中心。本次采用同步解析闭环，结果只返回给调用方，不写入现有卡顿事实与详情表。

## Goals / Non-Goals

**Goals:**

- 直接复用 btrace 已验证的 ZIP、manifest、哈希和 Sampling 解码逻辑，不在本仓库复制二进制协议实现。
- 为上传、mapping 和同步解析提供项目隔离、稳定错误及明确的资源边界。
- 保持 processor 原始报告不变，并在外层响应中补充项目和 mapping 处理状态。
- 让真实解析器依赖和上传适配能够由自动化测试验证。

**Non-Goals:**

- 不把 `RHEA_STACK_REPORT` 转换为现有 `JankPayload`，不改变卡顿指纹、统计和查询口径。
- 不新增原始产物对象存储、异步任务、状态查询或报告持久化。
- 不实现 mapping 上传和生命周期管理；只读取运维预置的项目级 mapping 文件。
- 不承诺取消请求后能中断 processor 内部解析，也不把 JVM 测试等同于生产容量验证。

## Decisions

### 1. 使用独立原始二进制入口而非 multipart 或扩展 JSON 批次

入口采用 `POST /ingest/v1/stack-artifacts:parse`，`Content-Type` 固定为 `application/vnd.shanshui.rheatrace+zip`，请求体直接是 `.rheatrace.zip` 字节，可选 mapping 标识放入 `X-Mapping-Id`，并复用 `X-Project-Key`。二进制文件不做 Base64、不进入 `events[]`，从而避免额外 33% 编码膨胀及破坏现有部分接受语义。

不使用通用 `application/octet-stream`，因为版本化厂商媒体类型能在读取前拒绝错误协议。也不使用 multipart：当前只有一个产物，mapping 只是受限标识符；multipart 会增加容器解析、临时落盘和独立大小配置，而 processor 本身还会复制到隔离临时文件。额外事件元数据继续由产物 manifest 提供。

### 2. 外层响应保存业务状态，内层报告保持 processor 原样

响应包含 `projectId`、`mappingStatus` 和 `report`。`report` 必须是 Schema 1、`artifactType=RHEA_STACK_REPORT` 的 JSON 对象。这样前端或后续存储层可以稳定取得业务状态，而无需修改 processor 报告或依赖 HTTP 响应头。

`mappingStatus` 首期只有 `not_requested` 和 `applied`。未提供 mapping 时仍可解析，但不能假设符号已经解混淆。

### 3. mapping 使用固定项目目录和受限 ID

配置 `apm.stack-parser.mapping-root` 指向运维管理的根目录；mapping 文件解析为 `<root>/<projectId>/<mappingId>.txt`。`projectId` 来自项目 Key，不从请求接收；`mappingId` 只允许字母、数字、点、下划线和连字符，并校验规范化路径仍位于项目目录内。

找不到 mapping、文件不可读或报告 mapping 不一致统一返回不泄露路径的业务错误。客户端不能上传 mapping，也不能指定绝对或相对路径。

### 4. 业务与 processor 双重限制，使用信号量保护并发

业务 `max-artifact-bytes` 默认 64 MiB。控制器先检查 `Content-Length`，再用受限输入流包裹 Servlet 请求流，因此分块传输、缺少长度或错误长度仍不能绕过上限。processor 继续执行其自身压缩输入、解压总量、条目和校验和限制。

同步解析前使用公平 `Semaphore` 进行非阻塞准入，默认并发 2。满载返回可重试 503，不让无界 Servlet 请求同时进入临时文件、解压和调用树构建。外部网关/Servlet 超时负责请求时间上限；本次不实现无法可靠终止内部工作的 `Future.cancel` 伪超时。

### 5. 使用 processor 公共接口，依赖版本固定为已验证本地构件

服务端依赖 `io.github.mashanshui:rhea-trace-processor:1.0.0` 并启用 `mavenLocal()`，与参考文档和本机已发布构件一致。该版本是包含 Commons IO、Commons Lang、Protobuf 和 org.json 的 fat JAR，因此服务端以非传递依赖接入，避免 POM/Gradle Metadata 再次引入重复运行时类。构建文档明确正式环境必须将同坐标版本发布到受控制品库；Maven Local 只是当前联调证据，不能当作 Central 可用性证明。

`StackAnalyzer` 作为无请求共享状态的单例注入。控制器不读取或缓存完整请求体，业务服务负责在请求生命周期内消费受限输入流，并把解析后的字符串重新绑定为 JSON 以验证报告结构。

## Risks / Trade-offs

- [同步解析占用 Servlet 线程和临时磁盘] → 使用文件/解压双重上限、并发信号量和外部请求超时；达到异步需求时另建对象存储任务协议。
- [processor 以 `IOException` 同时表达多类失败] → HTTP 层先检查鉴权、大小、mapping 和临时目录，剩余解析 `IOException` 统一映射为 422，服务端日志保留异常链。
- [Maven Local 造成构建不可复现] → 固定坐标和版本并在部署文档记录受控制品库前置条件；不提交本机构件或绝对路径。
- [报告可能很大] → 本次受 64 MiB 输入和解析并发保护；生产响应体、堆内存和 p95/p99 仍需真实产物压测。
- [mappingId 存在但未请求 mapping] → 明确返回 `not_requested`，禁止把报告中存在 mappingId 解释为已解混淆。

## Migration Plan

1. 先在开发环境发布/安装固定版本 processor 构件，完成编译和真实产物回归。
2. 部署 mapping 根目录和项目子目录权限；未配置时只开放无 mapping 解析。
3. 灰度启用新入口，监控请求大小、解析耗时、并发拒绝、临时目录和 JVM 堆。
4. 回滚时停止暴露新入口并移除 processor 依赖；现有 JSON 批量接收和 ClickHouse 数据不受影响。

## Open Questions

- 生产环境采用企业 Maven 仓库还是正式发布 `io.github.mashanshui` 坐标，由部署前确定，不改变 API 契约。
- 是否持久化原始产物和报告、是否转为异步任务，由真实流量与响应体基线决定，需单独变更。
