# 后端符号表实现

## 模块边界

`symbol` 模块对外只暴露 `symbol.api`：`SymbolRegistry` 负责按 `appId + buildId` 固定当前版本租约并执行请求内 Retrace，`SymbolFileMetadata`、页面结果和符号化结果属于 HTTP/领域契约。数据库、受控文件卷、R8 适配器和事务写入均位于 `symbol.internal`。Crash 和 Jank 不访问符号表内部实体、仓库或文件路径。

## 上传和替换链路

网页请求的 Session 和 CSRF 先由 Spring Security 检查，应用成员角色由 `AppAccessControl` 检查。服务端把 multipart 流写入符号目录同卷临时文件，同时计算 SHA-256 和大小，再使用官方 R8 Retrace mapping API 校验格式；空文件、超限文件、非法 mapping 不会发布。

首次上传在 `app_symbol_file` 建立 `appId + buildId` 唯一当前记录和审计记录，发布使用不可覆盖的 UUID 文件名并优先同卷原子移动；文件系统不支持时回退普通移动。相同摘要重复上传返回幂等成功；不同摘要返回 `SYMBOL_CONFLICT`，不自动覆盖。替换必须提交网页确认时看到的 `expectedRevision`，数据库使用 `symbolId + revision` 条件更新，成功后 revision 加一并写入 `app_symbol_file_audit`。相同摘要替换在 revision 校验后返回当前版本，不增加 revision 或审计记录。旧文件使用进程内读取租约计数，最后一个租约释放后才回收；服务启动时只清理数据库未引用且无租约文件，该租约不提供跨实例协调。

当前列表实现先按应用从 PostgreSQL 读取全部元数据，再在 Java 精确筛选 `buildId` 和切分游标页；默认 50、最大 200 仅限制响应页，不限制读取条数。尚未实现数据库筛选分页，构建数增长时需单独验证容量。

## R8 Retrace

`R8RetraceEngine` 通过固定版本 `com.android.tools:r8:8.9.35` 的官方 Retrace API 读取 `ProguardMapProducer`、`ProguardMappingSupplier` 和 `RetraceCommand`。上传校验阶段主动触发 mapping 读取；详情阶段把完整脱敏异常链转换为 R8 标准文本，保留 `Caused by` 顺序、候选结果和无行号帧。每次请求都在共享 Semaphore 内执行，输出超过 `apm.symbol.max-output-bytes` 或资源繁忙时返回降级状态，日志不写 mapping 内容或异常堆栈。

## Crash 与卡顿语义

- Crash 入库只保存已有脱敏原始事件。详情查询取得当前 mapping 的不可变租约，在请求内 Retrace 后返回 `symbolicatedStackText`、状态、原因和版本；不保存还原文本，不改变指纹、统计或事件记录。上传/替换后，旧事件下一次详情查询使用新版本。
- Jank ZIP 在 processor 解析回调中取得当前 mapping 租约，解析期间固定该文件；映射后的证据和指纹随事件保存。替换只影响之后上传的 ZIP，已保存事件不回算；无 mapping 注册记录时按可选 mapping 语义解析，已有记录对应文件丢失/不可读时返回可重试 503。

## 配置

| 配置键 | 环境变量 | 默认值 | 作用 |
|---|---|---:|---|
| `apm.symbol.directory` | `APM_SYMBOL_DIRECTORY` | `build/symbols` | 受控 mapping 文件卷，生产应挂载持久卷 |
| `apm.symbol.max-file-bytes` | `APM_SYMBOL_MAX_FILE_BYTES` | `33554432` | 单文件大小上限 |
| `apm.symbol.max-output-bytes` | `APM_SYMBOL_MAX_OUTPUT_BYTES` | `1048576` | 单次 Crash Retrace 输出上限 |
| `apm.symbol.max-concurrent-operations` | `APM_SYMBOL_MAX_CONCURRENT_OPERATIONS` | `2` | 上传校验和详情解析共享并发许可 |

Spring multipart 默认上限为 32 MiB 文件、34 MiB 请求；Nginx 或其他网关必须同步放行 multipart 请求。首版不连接对象存储、不提供构建自动上传、mapping 下载/删除、原生 SO 和 HPROF 符号化。

以上 multipart 配置与内存异常报告共用；256 MiB HPROF 是报告业务上限，不是默认部署的可上传文件上限。大附件部署要求见[构建配置](05-构建配置与本地运行.md#二进制堆栈解析配置)。

## 验证边界

已通过官方依赖在线解析、后端 `clean build`、真实 release mapping 的完整 Retrace 测试、Docker PostgreSQL 16 的 Flyway/JPA schema 校验、符号文件卷租约/回收测试和 Crash 异常链格式测试；并已在受控本机环境通过管理员网页替换、Crash 连续详情、Jank v3 新事件解析与后端重启读取验收。端到端 HTTP、浏览器和性能数据见[平台测试记录](../../../docs/knowledge-base/08-测试与质量保障.md#2026-09-20-android-符号表端到端验收)及[后端性能测试记录](06-测试与质量保障.md#2026-09-20-符号表端到端与性能验收)。性能数据是当前主机的小样本测量，不代表生产容量。
