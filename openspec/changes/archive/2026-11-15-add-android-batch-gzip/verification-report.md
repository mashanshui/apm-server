# gzip 变更核验：add-android-batch-gzip

## 核验结论

再次核验确认：W1（JSON 解析异常映射）和 S1（已有 gzip 网络失败日志阶段）均已修复；本轮复查规格、代码及回归断言，并重新执行针对性与隔离联合测试，未发现新的问题。

| 维度 | 结果 |
|---|---|
| 完整性 | 13/13 任务完成，8 项新增/修改要求均有实现，无删除或重命名要求 |
| 正确性 | 8/8 要求符合；25/25 场景已逐项核对，非法 JSON 语法分支补充自动化验证 |
| 一致性 | 共享拦截器、有界缓冲、端点匹配、完整解码、外部所有权和错误阶段符合设计 |

任务完成、规格覆盖、实现映射、场景覆盖、设计遵循、代码模式六项检查均已执行。没有未解决的严重问题、警告或建议；可进入归档流程。归档仍需独立执行规格同步及 knowledge-sync，本轮未部署或归档。物理设备、功耗和生产容量保持范围外边界。

### 核验发现的修复证据

- W1 已修复：`IngestController.java:106` 同时处理 IOException 与 JacksonException，不扩大为捕获所有运行时异常。`BatchGzipApiTests.rejectsMalformedJsonWithoutWritingEvents` 分别验证普通 JSON 与完整 gzip 的非法文本、截断 JSON、合法批次后额外 token，共 6 种组合；全部返回 400、INVALID_BATCH、retryable=false，内存仓库无事件写入。
- S1 已修复：`BatchGzipInterceptor.kt:40` 将已有 gzip 的网络执行移出压缩准备 try/catch。`BatchGzipInterceptorTest.existingGzipNetworkFailureDoesNotLogPreparationFailure` 验证下游原始 IOException 保持传播，不新增 stage=prepare 日志；原压缩准备失败与成功日志测试继续通过。

## 要求实现映射

| 要求 | 实现位置 | 核验 |
|---|---|---|
| SDK 自建会话统一 gzip | `TransportSession.kt:63`、`BatchGzipInterceptor.kt:30` | 已实现，现有领域 API 及合成挂起分支有证据 |
| 逻辑协议和请求头 | `BatchGzipInterceptor.kt:41`、`:57` | 保留认证、类型和内容，正确计算长度 |
| 原始预算和有界资源 | `BatchGzipInterceptor.kt:48`、`:68`，各原领域 uploader | 未扩大原批次，原始 1 MiB/输出 2 MiB 防御上限 |
| 严格内容编码 | `IngestController.java:112` | 完整 token、重复头、普通 JSON 分支齐全 |
| 完整 gzip 与两级字节限制 | `IngestController.java:76`、`:89`、`:94` | 尾部与大小已实现；Jackson 语法异常映射及无写入回归通过 |
| 压缩失败与领域确认 | `BatchGzipInterceptor.kt:60`、各领域 uploader | 复用原重试和逐事件确认；没有新增底层自动重试 |
| 非目标透传与安全日志 | `BatchGzipInterceptor.kt:30`、`:56` | 目标匹配与脱敏已实现；已有编码路径不再误记准备阶段 |
| 模块化上传可观察行为 | 自建共享会话及 Crash/Metrics 独立模块 API | 未改领域 DTO/磁盘模型及确认策略，不引入业务模块依赖 |

## 范围与证据入口

本变更只处理 Android 自建会话的 JSON 事件批次。SDK 现有 JVM/Native Crash、启动、FPS、内存峰值通过实际 Retrofit API 验证；前台挂起按用户确认使用合成协议验证，不新增 Android 采集上传功能。外部网络会话保持借用语义。

## 场景与自动化映射

| 规格场景 | 实施与证据 |
|---|---|
| 小批次、压缩膨胀、Unicode 与解压后字节一致 | `BatchGzipInterceptorTest.sizesAndPreparationFailures`、`routesAndBytesMatchContract` |
| 各现有事件类型编码一致 | `CrashGzipApiTest.domainApisUseGzip`、`MetricsGzipApiTest.domainApisUseGzip`；使用真实业务 Retrofit 接口 |
| 合成前台挂起 | `BatchGzipApiTests.acceptsSyntheticSuspensionGzip` 验证 gzip 接收与 accepted |
| 外部会话所有权 | `TransportSessionTest.externalResourcesAreBorrowed`，检查实例相同且关闭不释放外部资源 |
| 编码头、长度头及媒体类型 | `BatchGzipInterceptorTest.routesAndBytesMatchContract`、`sizesAndPreparationFailures` |
| 已有 gzip 不二次编码、其他编码本地失败 | `BatchGzipInterceptorTest.existingEncodingIsPreserved` |
| 原始大小预算 | 原上传器仍先检查未压缩 UTF-8 JSON；`sizesAndPreparationFailures` 验证传输原始缓冲上限；本次未修改领域预算 |
| 最高预算膨胀与 413 | `BatchGzipE2eTest.sdkReachesIsolatedBackend` 的 1 MiB 不可压缩噪声控制样本，经实际 SDK gzip 后由隔离后端返回 413；该噪声不代表合法 JSON 业务事件 |
| 编码大小写、空白、identity、普通 JSON | `BatchGzipApiTests.acceptsEncodingsAndDuplicates` 与既有普通 JSON API 回归 |
| 未知、子串、叠加、重复头 | `BatchGzipApiTests.rejectsUnsupportedEncodings` |
| 鉴权先于解码 | `BatchGzipApiTests.limitsWireBytesAndAuthenticatesBeforeReading`；控制器在 parseBatch 前调用 authenticator |
| 声明长度和实际未知长度超限 | `BatchGzipApiTests.limitsWireBytesAndAuthenticatesBeforeReading`、`BatchGzipHttpTests.verifyProxy` |
| 完整解压及 JSON 后空白限制 | `BatchGzipApiTests.countsFullDecodedBody`，保留线上 1 MiB、解压后 4 MiB 默认配置 |
| 尾部 CRC、长度与截断 | `BatchGzipApiTests.rejectsCorruptAndTruncatedTrailers`、`trailerFailureDoesNotWriteEvents`；合法事件损坏尾部后重发仍首次 accepted |
| 声明 gzip 却发普通 JSON、内部无效批次 | 错误声明、结构校验及完整 gzip 内部语法错误均有回归 |
| 准备失败不发送、不泄露正文 | `BatchGzipInterceptorTest.logsAndIoFailureStaySafe`；关闭日志也有覆盖 |
| 失败队列保留与 413/415 永久处理 | Crash/FPS/Memory 的 `gzipFailuresKeepExistingQueuePolicy`，复用原确认/重试实现 |
| 网络失败、503、确认丢失后重试与原身份 | 既有 `CrashUploaderTest`、`MemoryUploaderTest`、`MemoryRuntimePeaksTest`；与新增 gzip API 及准备失败测试组合验证 |
| 接受与重复确认 | `BatchGzipApiTests.acceptsEncodingsAndDuplicates`、`BatchGzipHttpTests.sdkThroughIsolatedNginx` |
| 部分接受与无效确认 | 既有 `CrashApiIntegrationTests.acceptsJsonGzipAndReturnsPartialAcceptance` 和各领域 uploader 测试 |
| 基础路径前缀、准确匹配、非目标请求透传 | `BatchGzipInterceptorTest.routesAndBytesMatchContract`；Jank/Leak 既有模块测试 |
| 全量与按需共享 | 自建共享会话仅安装一次拦截器，Crash/Metrics 独立模块 API 回归均使用该会话，不引入业务模块相互依赖 |

## 可复用验证命令

- Android：`bash android/gradlew -p android :performance-transport:testDebugUnitTest :performance-crash:testDebugUnitTest :performance-metrics:testDebugUnitTest :performance-jank:testDebugUnitTest :performance-leak:testDebugUnitTest :app:assembleDebug`。
- 后端全量：`bash backend/gradlew -p backend test`。
- 隔离联合：`APM_GZIP_HTTP_E2E=true bash backend/gradlew -p backend test --tests '*BatchGzipHttpTests' --tests '*BatchGzipApiTests'`。需要 Docker、本机 `apm-server-web` 镜像与 Android SDK；测试使用临时端口、H2 内存库和临时 Nginx 配置，结束后删除临时容器。不要与同工程其他 Gradle 测试并行运行。
- 规格：`openspec validate add-android-batch-gzip --strict`；空白：`git diff --check`。

## 合成样本测量

实际生产拦截器的 `measuresSyntheticBatches` 一次 JVM 测量如下；不代表 Android 真机功耗或生产吞吐。

| 样本 | 原始字节 | gzip 字节 | 压缩准备耗时 |
|---|---:|---:|---:|
| 极小 JSON | 2 | 22 | 36.5 微秒 |
| 重复堆栈 JSON | 38011 | 202 | 150.25 微秒 |
| 最大原始预算不可压缩噪声 | 1048576 | 1048914 | 15.26 毫秒 |

极小请求变大与无压缩率回退符合用户明确选择。噪声只用于传输上限实验，不是可接收的事件样本。

## 验证边界

不包含物理设备、弱网功耗、云端、生产容量、部署、Maven 发布或知识库同步。普通测试中外部 Android 联合入口按条件跳过，专门联合命令会启动真实 SDK 子测试；后端联合测试仅在显式环境开关打开时执行。完成状态以本次最终执行结果为准，不以规划文件存在或历史测试代替。

## 最终执行结果

- 本轮后端专项与隔离联合复测：28 项，失败 0、错误 0、条件跳过 1；其中 gzip API 8 项、真实 Servlet/Nginx 联合 1 项实际执行并通过。筛选模式同时匹配 NativeCrashApiIntegrationTests，其既有条件测试 1 项跳过，不计为已验证。
- 本轮 Android gzip 拦截器复测：6 项通过；隔离联合命令调用的真实 SDK 子测试 1 项实际执行并通过，没有跳过。
- 复查代码与场景映射：13/13 任务、8 项要求、25 个场景；W1 的 6 种 JSON/编码组合及 S1 下游失败日志回归均有断言。六类核验检查全部执行，没有未验证的整体检查项。
- 最近修复后完整基线：后端 491 项，失败 0、错误 0、条件跳过 39；Android 五模块 188 项，失败 0、错误 0、条件跳过 1，Debug APK 构建通过。本轮未重复运行完整基线，完整基线后实现源码未再修改。
- OpenSpec 严格校验和 `git diff --check` 本轮重新通过。隔离代理容器已清理，原有五服务保持运行；本轮仅更新核验报告，未修改实现、同步知识库、部署、发布或归档。
