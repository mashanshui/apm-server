# Android 版本符号表实现核验

## 核验范围

核验日期为 2026-11-12，针对当前未提交工作区的 `add-versioned-android-symbols`。读取 proposal、design、tasks 与四份增量规格；按实际源码、真实 PostgreSQL/ClickHouse/Servlet 测试、1280px 网页和有效 Token MCP 证据核验。没有 REMOVED 或 RENAMED 要求。本报告记录实现核验，不代表发布或业务环境迁移。

| 维度 | 结果 |
|---|---|
| 完整性 | 25/25 任务；16 个新增/修改要求均找到实现 |
| 正确性 | 16/16 要求完成实现映射；71 个场景按下表核对实现与自动化/实际页面证据；W1 已修复并增加错误分类及清理回归 |
| 一致性 | 主要设计及代码模式一致；W1 的 SO 校验 I/O/超时分类已修复，详见下文。页面标签、筛选和上传弹窗保留原契约 |

## 要求与场景证据

相同测试类可覆盖多个场景。表中的“场景数”对应该要求下全部场景；前端既有详情协议、权限与辅助诊断回归也属于本次全量测试，不因功能未改写而省略检查。

| 规格与要求 | 场景数 | 实现依据 | 测试与观察 |
|---|---:|---|---|
| application-symbol-management：网页管理应用构建符号表 | 5 | [管理服务](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/SymbolManagementService.java)、[数据库分页](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/persistence/SymbolFileRepository.java)、[页面](../../../../frontend/src/views/SymbolFilesView.vue) | SymbolApiIntegrationTests：listsVersionedMetadataWithoutInventingIdentity、uploadsDefaultAndSpecificMappingWithIndependentRevisions、coalescesConcurrentDefaultsAndBindsAllCursorFilters；10 万行列表计划与网页默认上传 |
| 上传和替换必须授权并校验 | 3 | [控制器](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/web/SymbolController.java)、[安全配置](../../../../backend/src/main/java/com/shanshui/apmserver/identity/internal/config/SecurityConfig.java)、[multipart 前门禁](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/web/NativeUploadFilter.java) | SymbolApiIntegrationTests 的权限、CSRF、Session、非法版本/构建/大小；VersionedSymbolsHttpTests 的 Viewer/无 CSRF/隐藏应用提前拒绝与实际字节边界 |
| 管理员确认替换及并发一致性 | 4 | [mapping 事务](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/SymbolRecordWriter.java)、[文件租约](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/storage/SymbolFileStore.java) | uploadsIdempotentlyAndReplacesWithExpectedRevision、serializesConcurrentDeleteAndReplacement、deletesWithRevisionAndRetiresOnlyAfterTheLastLease、preservesPublishedFileWhenMetadataCommitAcknowledgementIsUnknown；SymbolManagementServiceTests 的文件/数据库故障 |
| 上传完成与后台进展分别呈现 | 3 | [版本工作](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/CrashSymbolicationJobs.java)、[符号页面](../../../../frontend/src/views/SymbolFilesView.vue) | uploadDoesNotWaitForRetraceAndRollsBackWhenRegistrationFails、pollsBoundedProgressWithoutReadingCrashPayloads；页面有界轮询、SO 不轮询、旧登记 422；实际截图区分保存与还原 |
| SO 身份与符号信息来自文件内容 | 3 | [只读 ELF 检测](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/elf/ElfInspector.java)、[批次服务](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/NativeBatchService.java) | ElfInspectorTests：四 ABI、长 ID、缺失 ID、仅动态符号、损坏 headers/sections；实际模块入口 ZIP 不含目标时拒绝 |
| SO 批次全量校验与原子确认 | 3 | [批次事务](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/NativeBatchWriter.java)，同版本 advisory lock、已有行锁及局部唯一索引 | savesNativeBatchAtomicallyAndRequiresEveryExpectedIdentity、rejectsConcurrentAbsentNativeSnapshotAndPreservesCommitUnknownFiles、serializesAbsentNativeIdentitiesInsidePostgresTransactions；真实网页五项快照整批确认 |
| SO 压缩包处理有界且不采用归档路径存储 | 2 | [归档读取](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/archive/NativeArchiveReader.java)、[目录预检](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/archive/ZipDirectoryPreflight.java)、[流式接收](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/web/NativeMultipartBody.java) | NativeArchiveReaderTests、ZipDirectoryPreflightTests：安全路径、目录、链接、特殊项、嵌套/加密/ZIP64、本地头、CRC、实际长度与炸弹、中断清理；NativeUploadLimiterTests 的并发/磁盘；真实慢流截止 |
| 无应用版本的旧登记隔离 | 2 | [V17 元数据](../../../../backend/src/main/resources/db/migration/V17__versioned_android_symbol_metadata.sql)、[V18 工作迁移](../../../../backend/src/main/resources/db/migration/V18__versioned_crash_work.sql)、版本选择器及活动对账 | VersionedSymbolMigrationTests 保留旧行/引用/审计/来源；listsVersionedMetadataWithoutInventingIdentity 与 selectsVersionedMappingAndDoesNotFallbackFromUnreadableSpecificFile；旧登记不可匹配/替换/进度，重新上传新身份 |
| mapping 版本默认与构建优先 | 5 | [版本选择服务](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/SymbolManagementService.java) 的 acquireVersioned、第 241 行起；数据库 lockPreferredMapping | selectsVersionedMappingAndDoesNotFallbackFromUnreadableSpecificFile、uploadsDefaultAndSpecificMappingWithIndependentRevisions、coalescesConcurrentDefaultsAndBindsAllCursorFilters；SO buildId 字段不适用测试 |
| crash-background-symbolication：可靠唤醒与重启恢复 | 5 | [工作表操作](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/CrashSymbolicationJobs.java) 第 34～136 行、[对账](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashSymbolicationReconciler.java) | BackgroundCrashJobPostgresTests：通知/租约合并恢复、版本隔离、持久游标和迟到轮转；VersionedSymbolsHttpTests 上下文重启、专用删除后默认处理 |
| 后台处理有界且失败不阻塞全部数据 | 6 | [候选及有限载荷 SQL](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/ClickHouseCrashStateStore.java) 第 174～216 行、[Worker](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashBackgroundProcessor.java) | BackgroundCrashClickHouseTests 的 FINAL 最新状态；BackgroundCrashJobPostgresTests 的前 100 无文件/后 5 可处理及重启、早到迟到；CrashBackgroundProcessorTests 的失败继续与查询资源退让；百万两版本与大型辅助栈、前后台五轮并行，不提高原预算 |
| Native 原始事件不进入 JVM 符号还原 | 4 | [接收器](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashEventProcessor.java)、Worker/SQL/发布的 Native 门禁 | ignoresNativeAcrossMappingChangesAndRestarts、CrashIngestionBackgroundTests；真实 HTTP 的 mapping/SO 新建、删除、重启后 Native 详情相等；有效 Token MCP 在网页 SO 替换后逐字节复查原始栈 |
| mapping 来源确定性选择与固定 | 5 | [首次成功发布](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/persistence/CrashSymbolicationPublications.java)、Worker 实际 symbolId/revision 重试、V18 迁移守卫 | retriesOnlyChangedSourcesAndContinuesAfterAnUnreadableSpecificFile、replaysFixedBytesAndNeverLetsLateFailureOverrideSuccess、refusesInflightWorkAndPreservesRetiredJobsAndPublishedSources；真实 HTTP/MCP 固定来源 |
| crash-frontend：Crash 事件详情和堆栈展示 | 6 | [详情页](../../../../frontend/src/views/CrashEventDetailView.vue)、当前 API 解析层 | CrashEventDetailView.test.ts 的 pending/failed、固定还原、安全展示、缺失/权限、旧协议拒绝、迟到响应、精确版本与构建入口；实际 4.0/build-320 预填，可清空构建 |
| Native Crash 事件详情展示 | 4 | [Native 面板](../../../../frontend/src/components/NativeCrashPanel.vue)、详情权限/路由、[应用切换](../../../../frontend/src/components/AppLayout.vue) | NativeCrashPanel.test.ts、CrashEventDetailView.test.ts、AppLayout.test.ts 的已知/未知身份、只读成员、应用切换清目标；真实 Native 详情和目标不匹配截图 |
| jank-stack-artifact-ingestion：使用产物 buildId 安全选择 mapping | 11 | [上传解析](../../../../backend/src/main/java/com/shanshui/apmserver/jank/internal/artifact/StackArtifactParseService.java)、[预检身份](../../../../backend/src/main/java/com/shanshui/apmserver/jank/internal/artifact/JankArtifactIdentity.java)、[版本解析器](../../../../backend/src/main/java/com/shanshui/apmserver/jank/internal/artifact/AppStackMappingResolver.java) | StackArtifactParseServiceTests 的实际 1.0.4、有文件/无文件/存储错误、缺失构建、包名/回调/报告身份不符、duplicate 不覆盖及租约释放；actualProcessorKeepsJankEvidenceAcrossDefaultAndSpecificChanges 覆盖默认、专用、不可读与后续/已有事件 |

测试源码入口：[后端集成测试](../../../../backend/src/test/java/com/shanshui/apmserver)、[归档与 ELF](../../../../backend/src/test/java/com/shanshui/apmserver/symbol/internal)、[前端页面测试](../../../../frontend/src/views)。以上 71 个场景已核对条件分支或既有实现与针对性回归依据；“存储故障”涉及的 SO 校验阶段 I/O/超时分类由 W1 修复及新增接口级断言覆盖；不将浏览器小样本或性能一次观测推广为生产承诺。

## 设计及代码一致性复核

- 元数据复用原表、文件卷和审计；默认、专用、SO 各有活动唯一索引，列表在数据库过滤并键集分页，游标绑定完整过滤。没有以应用全量事件在 Java 内存筛选。
- V18 原位增加稳定 job_id/版本/游标，旧构建工作保留原构建和计数且退役，publication 保留旧字节/来源/序列；有效租约或未确认发布阻止升级。没有另建历史工作表、清空遥测或猜测旧版本。
- 版本条件在候选 LIMIT 前，第一阶段无堆栈/诊断；按完整有限身份读取载荷并自适应缩小批次；常规唤醒不重置扫描位置。失败比较实际来源，首次成功先恢复登记字节。
- SO 全量校验、全部预期项确认后单事务提交，预期不存在也受锁和唯一约束保护；提交未知保留可能被引用文件。总条目 256 与唯一 SO 身份 128 分别计算，相同身份同内容可合并。
- 请求头 CSRF/角色、许可和磁盘门禁先于 multipart；真实 Tomcat 读超时和拒绝后停止吞读，数据库语句锁等待使用剩余期限。Spring 256/260 MiB 与 Nginx 配置同步，mapping 仍为 32 MiB。
- 后续页面优化以标签、筛选、列表和原生上传弹窗呈现；路由仍预填版本/构建或 SO 模块身份，关闭清文件、类型切换隔离旧请求、只读角色和进度上限均保留。新增弹窗和键盘测试、1280px 本地页面证据见[前端测试](../../../../frontend/docs/knowledge-base/06-测试与质量保障.md)。
- 新代码位于现有 symbol/crash/jank 模块，跨模块通过既有 api/port，ArchUnit 通过；新增方法、身份与预算含中文说明，关键成功和失败日志不含文件内容、凭据和内部路径。
- Android 模型、队列、事件 Schema、MCP 查询没有可空构建改造；没有改动外部 btrace。现有 SDK 和实际 processor 均按 1.0.4 验证。

## 验证结果与限制

此前完整基线（2026-11-11）：后端 clean build 404 项、401 通过、0 失败、3 条件跳过；Android Debug JVM 206 通过及依赖检查；前端当时 179 通过及 typecheck/build；MCP 22 通过及 typecheck。慢流和百万容量专项显式启用，实际四 ABI 夹具参与测试。完整命令、大小边界、截图、MCP 脱敏摘要、条件跳过及查询指标见[隔离验收](../../../../docs/validation/versioned-android-symbols/README.md)。

2026-11-12 本轮定向重跑后端 12 类共 62 项，全部通过、0 跳过：SymbolApiIntegrationTests、VersionedSymbolMigrationTests、BackgroundCrashJobPostgresTests、CrashBackgroundProcessorTests、StackArtifactParseServiceTests、EventBuildRequiredTests，以及 archive/elf 包测试；使用实际四 ABI 临时夹具及隔离 PostgreSQL，包含真实 processor 1.0.4。前端完整 50 文件/181 项及 typecheck 再次通过，页面优化后的生产 build 已在同一任务前序执行通过。严格 OpenSpec 校验与 git diff --check 通过。

W1 修复后执行完整后端 clean build：409 项、406 通过、0 失败、3 条件跳过，显式启用真实 60 秒慢流和百万候选专项，使用实际四 ABI 夹具。最后针对读取途中 EOF 补充分支后，定向重跑符号接口、archive/elf 共 40 项，全部通过且无跳过，并生成最终 JAR。Android、MCP 和设备验收没有重新执行，沿用未改变实现的既有证据。

未提供外部 Native 设备样本、真实 mapping 性能样本和外部 Release mapping 的三项条件跳过已明确记录；这些不替代本变更的真实 HTTP、ELF 和 processor 测试。没有重做 Android 设备/Release/lint、生产容量、Nginx 实际代理吞吐或备份恢复；这些环境边界不属于“已验证”结论。没有跳过本报告的任务、规格覆盖、实现映射、场景覆盖、设计一致性和代码模式核对。

根、后端、前端及 Android 知识库、API、客户端接入按归属同步；新证据经验收导航和平台状态页索引。严格 OpenSpec 校验及本地 Markdown 目标检查通过，任务全部完成。

## 文档同步文件清单

### 根知识库

- [03-事件模型与数据存储.md](../../../../docs/knowledge-base/03-事件模型与数据存储.md)
- [06-安全与隐私.md](../../../../docs/knowledge-base/06-安全与隐私.md)
- [09-实施路线图.md](../../../../docs/knowledge-base/09-实施路线图.md)
- [10-架构决策记录.md](../../../../docs/knowledge-base/10-架构决策记录.md)
- [11-术语表.md](../../../../docs/knowledge-base/11-术语表.md)
- [13-待确认事项.md](../../../../docs/knowledge-base/13-待确认事项.md)
- [README.md](../../../../docs/knowledge-base/README.md)
- [02-总体架构与模块.md](../../../../docs/knowledge-base/02-总体架构与模块.md)
- [00-当前实现与验证边界.md](../../../../docs/knowledge-base/00-当前实现与验证边界.md)
- [04-上报协议与可靠性.md](../../../../docs/knowledge-base/04-上报协议与可靠性.md)
- [07-部署与运维.md](../../../../docs/knowledge-base/07-部署与运维.md)
- [08-测试与质量保障.md](../../../../docs/knowledge-base/08-测试与质量保障.md)

### 后端知识库

- [04-认证授权与安全实现.md](../../../../backend/docs/knowledge-base/04-认证授权与安全实现.md)
- [00-当前实现与验证边界.md](../../../../backend/docs/knowledge-base/00-当前实现与验证边界.md)
- [01-工程结构与模块边界.md](../../../../backend/docs/knowledge-base/01-工程结构与模块边界.md)
- [03-持久化与数据库迁移.md](../../../../backend/docs/knowledge-base/03-持久化与数据库迁移.md)
- [05-构建配置与本地运行.md](../../../../backend/docs/knowledge-base/05-构建配置与本地运行.md)
- [06-测试与质量保障.md](../../../../backend/docs/knowledge-base/06-测试与质量保障.md)
- [README.md](../../../../backend/docs/knowledge-base/README.md)
- [crash-query-performance-baseline.md](../../../../backend/docs/knowledge-base/crash-query-performance-baseline.md)
- [symbol-mapping.md](../../../../backend/docs/knowledge-base/symbol-mapping.md)

### 前端知识库

- [01-产品范围与当前状态.md](../../../../frontend/docs/knowledge-base/01-产品范围与当前状态.md)
- [03-页面路由与交互.md](../../../../frontend/docs/knowledge-base/03-页面路由与交互.md)
- [04-API数据模型与状态管理.md](../../../../frontend/docs/knowledge-base/04-API数据模型与状态管理.md)
- [06-测试与质量保障.md](../../../../frontend/docs/knowledge-base/06-测试与质量保障.md)
- [README.md](../../../../frontend/docs/knowledge-base/README.md)

### API 文档

- [app-api.md](../../../../docs/api/app-api.md)
- [README.md](../../../../docs/api/README.md)
- [crash-api.md](../../../../docs/api/crash-api.md)
- [stack-artifact-api.md](../../../../docs/api/stack-artifact-api.md)
- [symbol-api.md](../../../../docs/api/symbol-api.md)

### 客户端文档

- [README.md](../../../../docs/client-integration/README.md)
- [jank-artifact-manifest.md](../../../../docs/client-integration/jank-artifact-manifest.md)
- [jank-monitoring.md](../../../../docs/client-integration/jank-monitoring.md)
- [symbol-mapping.md](../../../../docs/client-integration/symbol-mapping.md)

### Android 知识库

- [03-Crash采集与上报.md](../../../../android/docs/knowledge-base/03-Crash采集与上报.md)
- [09-服务端对接与数据协议.md](../../../../android/docs/knowledge-base/09-服务端对接与数据协议.md)
- [11-构建测试与本地发布.md](../../../../android/docs/knowledge-base/11-构建测试与本地发布.md)

验收资料新增上述独立目录与截图，并同步[验收导航](../../../../docs/validation/README.md)。

## 问题分级与结论

严重问题：0；未解决警告：0；独立建议：0。W1 已修复并补齐回归，25/25 任务完成；任务/规格覆盖、实现映射、场景覆盖、设计和代码模式检查均已执行，四份增量规格已同步主规范，并于 2026-11-12 归档；提交与推送状态以 Git 为准。主规范常规校验 24/24 通过；全量严格校验存在要求描述超过 500 字的警告（包含本次扩充后 514 字的 Crash 详情要求），按完整保留 MODIFIED 要求的规则未额外拆分。上述零警告结论限于实现核验，不代表全量主规范严格校验。

### W1 修复与回归依据

- 归档格式错误使用明确的 ZipException，ELF 范围错误使用 ElfValidationException；超时/中断使用 InterruptedIOException，普通 I/O 和读取途中 EOF 保留存储故障身份，接口映射为可重试 503 SYMBOL_STORE_UNAVAILABLE、Retry-After: 30。格式错误仍为 422，字节超限仍为 413。
- [NativeArchiveChannel](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/archive/NativeArchiveChannel.java) 在 ZIP 库包装索引异常前保留底层存储故障，不依据异常消息猜测类别，不额外复制压缩包。
- [NativeBatchService](../../../../backend/src/main/java/com/shanshui/apmserver/symbol/internal/application/NativeBatchService.java) 区分内容与 I/O 失败；数据库提交后出现清理故障时不退休已引用键，普通原批次重传可幂等对账。
- [SymbolApiIntegrationTests](../../../../backend/src/test/java/com/shanshui/apmserver/SymbolApiIntegrationTests.java) 新增 retriesPostReceiveDeadlineInterruptAndStorageFailures、keepsMalformedArchiveAndTruncatedElfNonRetryable、preservesCommittedNativeFilesWhenArchiveCleanupFails，覆盖实际过期期限、注入 ELF 中断/EOF/I/O、实际删除暂存 SO 后的摘要读取失败、损坏 ZIP 索引/CRC 和截断 ELF、提交后清理故障。检查错误码/retryable/Retry-After、无路径泄漏、零部分元数据/审计、清理、许可释放和幂等重试。
- [NativeArchiveChannelTests](../../../../backend/src/test/java/com/shanshui/apmserver/symbol/internal/archive/NativeArchiveChannelTests.java) 验证真实 ZIP 库索引初始化不会吞掉通道 I/O 故障；归档/ELF 底层用例检查中断类别、标记保留及内容范围错误。

错误语义同步[符号表 API](../../../../docs/api/symbol-api.md)、[客户端重试说明](../../../../docs/client-integration/symbol-mapping.md)及[后端实现](../../../../backend/docs/knowledge-base/symbol-mapping.md)。已有本地部署状态见[平台状态](../../../../docs/knowledge-base/00-当前实现与验证边界.md)；没有重做物理磁盘故障、云端部署或备份恢复演练。
