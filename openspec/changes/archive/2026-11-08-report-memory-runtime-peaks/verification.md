# 内存运行峰值服务端核验报告

核验日期：2026-11-08。变更：`report-memory-runtime-peaks`，流程：`spec-driven`。

## 结论

未发现阻止归档的问题。完整性、正确性和设计一致性检查均已执行；CRITICAL 0、WARNING 0、SUGGESTION 1。可同步正式规格并归档，建议项见下文。本报告核对当前源码、需求场景及同一任务中2026-11-07生成的JUnit和联合验收证据，没有重新运行设备实验或部署服务。

| 维度 | 结果 |
|---|---|
| 完整性 | 20/20任务；4份增量的9项新增/修改需求均有实现；1项重命名由对应MODIFIED需求一并核验 |
| 正确性 | 9/9需求完成实现映射，30/30场景已检查，由自动化、源码和独立/联合浏览器证据互补支持 |
| 一致性 | 独立表、原身份/版本、同步持久化、数据库聚合、有界资源及只读查询设计一致；未引入兼容转换或新服务 |

## 实现与场景映射

下列代码路径相对服务端仓库根；场景数量对应增量规格，测试名称保留实际标识，不能把“有测试”单独当作已执行。

| 需求 | 场景数 | 实现与验证依据 |
|---|---:|---|
| 接收运行期内存峰值汇总 | 7 | `ingest/internal/protocol/EventSchemaValidator.java:50`严格类型与字段、`ingest/internal/application/BatchIngestionService.java:162`分派；完整路径前缀为`backend/src/main/java/com/shanshui/apmserver/`。`memory/internal/application/MemoryEventValidator.java:39`校验时间/独立上下文和90天窗口。MemoryMetricsTests的零/缺失、数值/浮点、载荷混用、固定时钟窗口及长运行用例，MemoryMetricsApiIntegrationTests的包名/旧协议错误，以及联合J6覆盖7个场景 |
| 重试不改变统计数量 | 4 | `backend/src/main/java/com/shanshui/apmserver/memory/internal/persistence/ClickHouseMemoryMetricsRepository.java:53`同步查重后写入；009峰值表保留原身份及90天TTL。MemoryPeakPersistenceTests覆盖并发/批内/跨应用、重建仓库与跨接收日、写入失败和确认丢失、旧表隔离；联合J2/J3/J4核对原磁盘、请求和入库身份 |
| 概览统计遵循固定样本口径 | 4 | `backend/src/main/java/com/shanshui/apmserver/memory/internal/persistence/ClickHouseMemoryMetricsQuerySql.java:18`数据库FINAL、逐指标条件及精确插值；MemoryMetricsTests验证0/100/200/300和缺失、单值/空值、独立上下文；MemoryPeakQueryBudgetTests执行十万目标运行与二十万干扰记录、扫描/时间/内存/响应边界 |
| 趋势与筛选使用同一时间和授权边界 | 4 | 同一SQL文件`:31`按event_time生成UTC桶、`:75`执行`[from,to)`及月份裁剪；MemoryMetricsSqlTests、MemoryMetricsTests和API专项覆盖from/to、空桶、跨日、进程及非法参数/成员权限；联合J5独立从原设备事件计算桶与统计 |
| 提供三个内存指标页签 | 1 | `frontend/src/views/MemoryMetricsView.vue:61`展示运行峰值、下次启动及截止时间说明；页面单测和1280/1440/1920真实后端截图，联合J5实际SDK截图 |
| 筛选和地址状态一致 | 2 | `frontend/src/utils/memoryQuery.ts`恢复URL/校验；`frontend/src/composables/useMemoryMetrics.ts`复用查询区域的取消及代次检查；memoryQuery.test.ts与useJankQuery.test.ts覆盖地址恢复和旧响应丢弃，浏览器复核后台筛选 |
| 展示概览和分位数趋势 | 3 | `frontend/src/views/MemoryMetricsView.vue:36`按指标选择各自统计，`frontend/src/components/MemoryMetricSummary.vue:27`读取runCount；组件覆盖0/null/MiB，后端固定集和浏览器覆盖分位数/空值，联合J5核对三指标和P95 |
| 各事件入口接收进程标识 | 3 | 公共Schema`:58`要求UUID v4，各入口原身份校验继续生效；ProcessIdentity专项及各上传入口测试覆盖合法/缺失/非v4；联合J4与MemoryPeakPersistenceTests证明新版本上传不改写旧身份 |
| 内存只读查询使用运行峰值口径 | 2 | Agent复用内存查询业务；`mcp-server/src/server.ts:74`保留工具/筛选，`:213`拒绝旧或非法计数；MCP domain/tools/runtime测试及官方HTTP验收脚本比较真实后端、授权错误与存储错误，联合J5核对全部指标和各自上下文 |

## 证据边界与设计一致性

- 现存后端JUnit：316项，313通过、3条件跳过、失败/错误0。跳过为未启用的SymbolMappingPerformanceTests、BackgroundCrashQueryPerformanceTests和缺少外部mapping的Retrace用例，不属于本次峰值场景。
- 同一任务前端47文件/156项、类型检查和构建通过；MCP20项及类型检查通过。具体命令、固定集、真实存储、截图、APK哈希与脱敏联合结果以仓库`docs/validation/memory-runtime-peaks/README.md`为唯一证据入口。
- J1..J7使用用户指定Pixel_6/API35/arm64模拟器；真实SDK、隔离Servlet/H2管理及ClickHouse，不是MockMvc或模拟API替代。故障和升级结束后原目录内容哈希、原APK与网络状态已恢复。
- 采样间隔内尖峰、未持久化更新、物理真机功耗、跨ROM、生产容量和多实例原子性均不在已验证结论内。这里是已声明的验收范围，不是跳过了本变更的适用检查。
- 实现沿用领域模块、公共身份/鉴权、查询预算和前端状态机制；新增迁移009而非改写旧迁移，旧表仅隔离保留，不混入峰值统计。

## 建议项

SUGGESTION 1：可在`frontend/src/views/MemoryMetricsView.test.ts:12`增加PSS与Java堆均为非零但runCount不同的页签展示断言。当前该页面单测让三项共用同一统计对象；统计组件、后端不同计数及浏览器PSS有值/VSS空的证据已覆盖现有实现，此建议用于提高页面专门回归的定位能力，不要求修改产品代码或阻止归档。

## 正式规格同步检查

同步范围为`memory-metrics-server`（4项修改含1项重命名）、`memory-metrics-frontend`（3项修改）、`event-process-identity`（1项修改）、`apm-mcp-query`（1项新增）。无删除能力；未涉及的需求和场景必须保留。同步后的正式规格、严格校验与跨仓库归档链接检查是归档收尾条件。
