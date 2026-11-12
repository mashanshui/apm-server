# Android 版本符号表隔离验收

## 范围与结论

2026-11-11 对 `add-versioned-android-symbols` 当前工作区执行隔离验收。独立 PostgreSQL 16、ClickHouse 26.7、随机 HTTP 端口与临时符号目录用于合成数据；网页以 1280px 视口连接同一后端，有效查询 Token 连接独立 MCP。未修改既有本地服务、云端或业务数据，验收临时服务已停止。

事件 `buildId` 继续必填；仅 mapping 登记可省略构建，含义为版本默认。JVM 与 Jank 统一在同版本优先构建专用、缺失时选默认，专用读取或注册表故障不回退。SDK 与 processor 保持 1.0.4，未修改外部 btrace。SO 只登记文件和元数据，不执行 Native 地址还原。

## 跨端检查

| 检查 | 观察与断言 | 可复用证据 |
|---|---|---|
| 默认/专用和版本隔离 | 有构建 JVM 事件分别选择默认及专用；相同构建的其他版本保持等待；删除专用后新事件选默认，先前成功来源和字节不变 | `VersionedSymbolsHttpTests.validatesVersionSelectionNativeUploadAndEarlyAdmissionOverHttp`；`SymbolApiIntegrationTests.uploadsDefaultAndSpecificMappingWithIndependentRevisions` |
| 网页默认 mapping | 上传不填写 buildId，返回同一活动身份及修订号；版本进度单独显示，说明计数范围与上传不等于还原 | [默认 mapping 与版本进度](mapping-1280.png) |
| JVM 详情预填 | 版本 4.0、build-320 准确带入表单，可清空构建显示版本默认提示；该版本没有实际提交新文件 | [预填截图](jvm-prefill-1280.png) |
| SO 整批确认 | 四 ABI 同名 SO；新一批包含一个新身份、一个不同内容及三个相同项，首次 409 完整展示五项，确认后新建 1、替换 1、相同 3 | [完整快照](so-conflict-1280.png)、[确认后列表](so-1280.png)；真实 PostgreSQL 并发与回滚另由 `SymbolApiIntegrationTests` 覆盖 |
| 长标识与页面布局 | 64 字节 GNU Build ID 对应 128 个十六进制字符完整显示；冲突面板和 mapping 页面 document.scrollWidth 均为 1280 | [完整快照](so-conflict-1280.png)，没有截短身份 |
| Native 模块入口 | 原始详情无 JVM mapping 或 JVM 分析入口；SO 管理仅传版本、ABI、完整 ELF Build ID，没有应用构建输入；目标不在 ZIP 时整批拒绝 | [Native 详情](native-detail-1280.png)、[目标不符](so-target-mismatch-1280.png) |
| 权限、接收预算和共享入口 | Viewer、无 CSRF、隐藏应用在正文解析前拒绝；中断释放许可；256 MiB ZIP 接收，额外一字节拒绝；mapping 超过 32 MiB 拒绝，33 MiB 内存 HPROF 仍接收 | `VersionedSymbolsHttpTests` 实际 Servlet/HTTP，不以 MockMvc 代替大小与接收验证 |
| 持续慢流 | 每 200ms 发送字节，约 60.442 秒得到完整 503 JSON 并释放许可；容器不继续吞读剩余 SO 正文 | `stopsAContinuouslySlowMultipartUploadAtTheSharedDeadline`，完整基线显式启用条件 |
| 删除和重启 | SO 条件删除不新增工作；销毁并重新创建完整后端上下文，文件、默认来源、首次成功结果、查询 Token 及 Native 材料保持，版本工作没有膨胀 | `preservesFilesSourcesAndNativeMaterialAcrossBackendRestart` |
| 有效 Token MCP | 19 个工具发现成功，应用与 JVM/Native 详情、Native stack 分段查询成功；网页 SO 新建/替换后再次查询，JVM 来源和 Native 摘要、原始栈内容逐字节一致 | [不含凭据的摘要](mcp-summary.json)；原始 Token/响应仅在忽略的 build 临时文件中，不作为持久资料 |
| Jank 1.0.4 | 实际 processor 处理合法 v3 ZIP，默认/专用来源、缺失拒绝、注册表故障和重复事件证据固定 | `StackArtifactParseServiceTests`、`SymbolApiIntegrationTests.actualProcessorKeepsJankEvidenceAcrossDefaultAndSpecificChanges` |

截图直接来自实际页面，没有编辑身份或结果；合成管理员显示名存在测试配置编码现象，不涉及此次符号表功能。

## 自动化与复跑

| 工程 | 已执行结果 | 边界 |
|---|---|---|
| 后端 | `clean build` 404 项，401 通过、0 失败、3 条件跳过；随后定向复核百万数据中大型辅助载荷确实进入有限 payload 查询 | 跳过：外部 Native 设备样本、真实 mapping 性能专项、外部 Release mapping；SO 四 ABI 与持续慢流没有跳过 |
| Android | 各模块 Debug JVM 206 项通过，模块依赖检查通过；native-tools 无 JVM 用例 | 未重做 Release、lint、APK 构建、设备采集或 SDK 发布 |
| 前端 | 50 文件/179 项通过；typecheck、生产 build 通过 | 真实页面检查为 1280px PC，不代表移动布局 |
| MCP | 22 项通过，typecheck 通过；另做上表实际协议查询 | 仅隔离后端合成数据，不代表云端授权查询 |
| Nginx | 配置在 nginx:1.27-alpine 中 `nginx -t` 通过 | 未部署或执行真实 Nginx 反向代理容量测试 |

后端复跑需 Java 21、Docker、实际 Maven Local processor 1.0.4 和临时生成的四 ABI 夹具，见[夹具生成](../../../backend/src/test/fixtures/elf/README.md)：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
APM_ELF_FIXTURES="$fixture_dir" RUN_CRASH_BENCHMARK=true RUN_NATIVE_SLOW_HTTP=true \
  bash backend/gradlew -p backend clean build --console=plain
```

`VersionedSymbolsHttpTests` 按顺序完成真实 HTTP、销毁后重启与持续慢流；默认不设置 `APM_SYMBOL_HTTP_HOLD`，只有连接浏览器/MCP 时才设置为一个尚不存在的临时文件路径，创建该文件后继续重启断言。凭据仅用于测试，不在日志或文档输出查询 Token。前端执行 `npm test`、`npm run typecheck`、`npm run build`；MCP 执行 `npm test`、`npm run typecheck`。Android 分模块测试入口见[构建测试](../../../android/docs/knowledge-base/11-构建测试与本地发布.md)。

## 查询与迁移边界

10 万混合符号记录的版本列表使用 Index Scan，返回 51 行、54 个共享命中、0 个读取块、约 0.153ms。百万 Crash、十构建、两版本及大型诊断样本下，轻量候选与有限载荷返回 100 条、855,787 字节；一次观测读取 124,701 行/47,683,006 字节，内存 53,708,126 字节、23ms，未提高既有查询预算。前台一天窗口与后台并行五轮通过；不代表全窗口或生产并发容量。查询方法、SQL 和限制唯一维护在[性能基线](../../../backend/docs/knowledge-base/crash-query-performance-baseline.md)。

V17～V19 在真实 PostgreSQL 验证升级、默认 NULL 唯一性、旧登记与旧工作退役、有效租约和未确认 publication 阻止升级、来源/审计/序列保留。正式切换必须排空后停机，备份管理库与符号卷；本次没有进行真实业务环境备份恢复，不宣称可无损回滚。步骤见[部署运维](../../knowledge-base/07-部署与运维.md)及[后端迁移](../../../backend/docs/knowledge-base/03-持久化与数据库迁移.md)。

契约见[符号表 API](../../api/symbol-api.md)与[客户端说明](../../client-integration/symbol-mapping.md)，规格映射见[变更核验](../../../openspec/changes/archive/2026-11-12-add-versioned-android-symbols/verification.md)。返回[验收入口](../README.md)。
