# 技术设计

## Context

动机与范围见 [变更提案](proposal.md)。本变更涉及 Android 公共传输、Crash/Metrics 上传及后端解码，且需要明确内存和错误边界，因此保留独立设计文档。

已核对的当前实现：

- `android/performance-transport/.../TransportSession.kt` 使用 OkHttp 4.12.0 和 Retrofit 2.11.0，共享 App Key、超时和可选 BASIC 日志；`retryOnConnectionFailure(false)` 将重试交给领域上传器。外部 OkHttp/Retrofit 成对传入时直接借用。
- Crash、FPS 和内存 API 均以 Gson 序列化 JSON，POST 相对路径 `ingest/v1/batches`；Crash/指标批次默认 512 KiB，配置最高 1 MiB，上传器按原始 UTF-8 JSON 校验预算。Android 尚无前台挂起汇总采集/上传模型；该事件仅在服务端有接收能力，本次使用合成协议验证，不新增采集功能。
- `backend/.../ingest/internal/web/IngestController.java` 已先鉴权，再使用线上受限流、`GZIPInputStream` 与解压后受限流。当前 `contains("gzip")` 判断会接受未知子串或编码组合；只读取 JSON 树不能作为已完整校验 gzip 尾部的证明。
- `application.properties` 默认线上 1 MiB、解压后 4 MiB。现有 `CrashApiIntegrationTests` 覆盖 gzip 部分接受，`JankRequestLimitApiIntegrationTests` 有解压超限测试；这些现存测试本轮未重新执行。
- `modular-sdk-composition` 的“拆分保持采集上传的可观察行为”含“请求格式保持”，本变更以增量规格明确保留逻辑 JSON，并新增传输内容编码，避免静默改变该约束的解释。

## Goals / Non-Goals

**目标：** SDK 自建会话的目标批次一律 gzip；用共享设施控制压缩次数、字节数与日志；将完整解码成功作为领域分派前置条件。

**非目标：** 不引入动态阈值、开关、压缩协商探测、自动普通 JSON 回退、压缩磁盘缓存或新重试层；不把外部借用会话改造成 SDK 拥有的实例。其他传输范围按提案保持原行为。

## Decisions

### 1. 在共享应用拦截器中执行一次压缩

在 `performance-transport` 增加批次 gzip 拦截器，由 `TransportSession.create` 的自建分支安装。匹配目标由 `normalizedBaseUrl` 解析相对批次路径得到，比较同源、准确路径、POST 方法和 JSON 媒体类型，支持基础路径前缀，不用简单的路径后缀匹配。安装位置在请求正文已由 Retrofit 生成之后、OkHttp 处理线上长度之前；不手动设置 `Accept-Encoding`，不影响既有响应处理。

各业务模块不增加压缩分支。相比在三个 API 客户端逐个包装，这使全量和按需接入一致，也减少 Jank/Leak 误压缩风险。外部借用分支继续原样使用，由宿主控制其策略。

### 2. 有界缓冲生成完整 gzip 后再发起请求

目标请求为可重复写出的、已经有单批预算的 JSON 正文。将其写入限制为 1 MiB 的临时原始缓冲，再生成完整 gzip 字节；压缩输出设置 2 MiB 的防御性缓冲上限，只服务于本次单批，不是新线上许可。关闭 gzip 输出以写入尾部后，再创建保留 JSON 类型、长度已知的新正文，由 OkHttp 写出正确 Content-Length。清理原始 Content-Length/Transfer-Encoding，避免遗留长度冲突。缓冲在当前请求结束后释放，不读取整个队列。

该方案比额外压缩文件或通用流式包装更直接，容易验证完整字节、正确长度和错误发生时机，且单批原始内容最高 1 MiB。缓冲只在现有上传后台链路使用，不在 Crash 现场线程压缩。已有单独 gzip 编码的正文原样通过，其他预编码在本地失败，禁止二次压缩或只改头不改正文。

用户明确选择所有批次 gzip，因此即使压缩膨胀也照常发送。若最高预算附近的请求压缩后超过服务端 1 MiB，则沿用 413 永久拒绝；不自动缩小现有批次预算，不提高服务端上限，不改发普通 JSON。正常默认 512 KiB 与服务端 1 MiB 之间存在余量，但不承诺所有配置边界都可接受。

### 3. 服务端在原入口严格识别并完整解码

仅修改批次入口，不添加作用于全站的解压 Filter。通过所有 Content-Encoding 头值识别编码：无头、空白或单独 identity 为普通 JSON，忽略大小写和首尾空白的单独 gzip 为压缩 JSON；重复头、逗号列表及未知 token 返回 415。保留当前媒体类型规则与鉴权顺序，不顺手调整其他协议。

线上 `LimitedInputStream` 在 gzip 外，解压限制在 gzip 内，大小配置保持不变。gzip 路径必须在分派前完整读取到解压 EOF，验证 CRC/尾部并触发完整大小限制；采用解压后最高 4 MiB 的有界缓冲完成读取，再解析现有批次树与事件模型。普通 JSON 的大小限制和校验继续保留。不捕获并掩盖限流异常：超限保持 413，gzip I/O/内部 JSON 错误保持 400 INVALID_BATCH，不支持编码为 415。读取、完整性或解析失败不进入领域分派，防止部分写入。

### 4. 压缩错误交给现有上传器

压缩 I/O 或有界缓冲准备失败作为可报告的请求准备失败，在 `chain.proceed` 前终止并通过现有网络错误路径保留队列，遵守原有次数/退避规则。协程取消保持传播，不捕获 OOM 等不可恢复错误，不伪造业务失败响应。HTTP 413、415 等仍由各领域原错误策略处理，不新增自动重试请求或探测。

重试重新序列化本次选中的原事件并 gzip，不要求 gzip 字节或 requestId 跨重试相同；要求 eventId、事件载荷和原运行身份不变。服务端继续按 appId/eventId 去重，批次 requestId 必须与该次响应匹配。

### 5. 使用现有日志设施观察编码结果

压缩成功记录原字节、gzip 字节、耗时与结果，沿用 `enableLogging` 控制可选网络日志；失败记录受控阶段和异常类型。后端新增必要的解码成功/拒绝摘要遵循已有日志等级，不逐条打印事件。禁止记录 App Key、Authorization、正文、完整诊断栈与带敏感查询参数的完整 URL。不增加依赖、监控服务或日志表。

## Risks / Trade-offs

- 小请求可能变大并增加 CPU 消耗 → 按已确认的一律 gzip 策略执行，合成样本记录字节数与耗时，不承诺固定节省比例或真机功耗。
- 客户端缓冲和后端完整解码增加单请求内存 → 原始/输出/解压缓冲均有明确上限，保留现有请求规模，不扩大批次和并发；不引入新的通用并发平台。
- JSON 已完整但 gzip 尾部损坏仍可能被提前接受 → 在解析分派前读至 gzip EOF，使用“合法 JSON + 损坏尾部”和“合法 JSON + 超量空白”测试证明。
- 外部会话不自动 gzip → 规划和规格明确自动策略仅属于 SDK 自建会话，验证外部借用所有权保持；不擅自重建宿主 Retrofit。
- 单元测试无法证明代理和真实 Servlet 一致 → 补充隔离真实 HTTP 及复用现有 Nginx 配置的隔离入口合成烟测，覆盖固定长度和无声明长度请求；不使用用户真实事件或改动业务数据。

## Migration Plan

无数据库、队列文件或 Schema 迁移。实施完成后先验证后端仍接受普通 JSON 与严格 gzip，再更新 SDK 产物；本变更任务不包含部署或 Maven 发布。回退 SDK 后会恢复普通 JSON，服务端现有接收能力可继续使用；后端回退只丢失新增严格校验，不涉及数据回滚。

归档前按仓库流程显式运行 knowledge-sync，对应主题包括 Android 网络/协议、后端接收链路、根上报可靠性、Crash/API 与客户端接入说明；测试证据与环境边界原位维护，不把当前规划写成已实现或已验证。
