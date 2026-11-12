# Crash 查询下推与容量基线

## 当前查询路径与预算

Crash/app_start 只查 `apm_crash_fact FINAL`，按应用、发生时间 `[from,to)` 与白名单维度过滤；状态侧从 `apm_crash_state FINAL` 取最新版本再应用指纹筛选。概览、趋势、Issue 和事件摘要显式投影轻量列，不引用 `stack_payload`、`diagnostics_payload` 或 `thread_stacks_payload`。启动分母来自同一当前事实数据集，指纹筛选不丢启动分母。Issue 在数据库聚合、排序及 limit+1 分页；事件页按发生时间倒序/eventId 升序续读。分页不是快照，后台归组或迟到事件会改变排行，旧临时指纹可能为空。

单事件详情先定位不可变事实，再按精确应用/构建/发生时间/eventId 的 PREWHERE 读最新单份状态。后台候选限定应用、应用版本、最近 30 天及最新逻辑 pending/failed 状态，先取最多 100 个轻量身份；按其非空构建选择专用或默认来源，第二阶段用完整构建/时间/eventId 身份只读取适用载荷。排序前缀为应用/构建/发生时间，eventId 有 bloom filter；候选没有全量载荷扫描后 Java 筛选。

上述查询默认超时 2 秒、最多 500 万读取行/512 MiB 读取/256 MiB 数据库内存/8 MiB 响应。客户端只可在服务端范围内调整超时，不能扩大其他预算。资源/超时明确失败，不返回截断或部分成功；大载荷后台批次有限减半，单条仍超限则退避。数据库检查存在粒度，取消请求不保证 SQL 立即停止。

## 新模型后台候选基线

2026-11-11，隔离 ClickHouse 26.7.3.19，百万 pending 事件、十构建、两版本，每事件约 2 KiB 原始链；每万条另含 128 KiB 诊断和 256 KiB 辅助载荷。`RUN_CRASH_BENCHMARK=true` 显式启用 `BackgroundCrashQueryPerformanceTests`。版本条件在第一阶段 LIMIT 前生效，该阶段只读轻量身份、状态和来源，不读任何诊断或堆栈列。第二阶段按已选的完整构建/时间/eventId 集合定位，不能只使用 eventId 扩大读取范围。

本次载荷查询返回 100 条、855787 字节，读取 124701 行/47683006 字节，数据库内存 53708126 字节，单次 23 ms。数字为本机隔离样本，不是生产 SLA；轻量阶段另有完整 EXPLAIN，全部查询沿用 2 秒、500 万读取行、512 MiB 读取、256 MiB 内存和 8 MiB 响应预算。前台一天窗口与新版两阶段后台查询并行五轮通过；读取预算降为 1 行时明确返回资源失败，没有提高上限。

实际 SQL、计划及 query_log 输出至被忽略的 `backend/build/query-evidence/background-crash.txt`，运行 clean 后需重建。较早的按构建候选数字不适用于新版版本调度，不复用为容量结论。常规自动化默认跳过此性能用例；本次已显式运行。无匹配前页、重建执行器和常规唤醒不重置位置，由真实 PostgreSQL 的 105 条跨页用例覆盖；迟到事件与固定 publication 的故障恢复另有管理库测试。

## 十万符号登记分页

`VersionedSymbolMigrationTests` 在 PostgreSQL 16 写入 100000 条混合 mapping/SO，分布于 100 个版本。EXPLAIN ANALYZE 验证版本分页使用 `app_symbol_file_version_page_idx` 的 Index Scan，返回 51 项，样本执行约 0.153 ms、共享命中 54 块、无共享磁盘读取。完整原始计划在 `backend/build/query-evidence/versioned-symbol-list.json`；该结果不证明跨租户、冷缓存或生产并发能力。

## HTTP 积压与上传验证

真实后台、PostgreSQL、ClickHouse、受控文件卷和 HTTP 的混合验收与恢复边界见[隔离验收](../../../docs/validation/background-crash/README.md)。上传只校验文件并在管理事务登记版本工作，不在请求内遍历或还原历史。后台每进程最多一个版本、一页 100；未确认发布默认至多 100 条/16 MiB，单份编码载荷最多 4 MiB。慢 R8、事务登记失败、租约过期、通知丢失及首次成功幂等由数据库/故障注入测试验证，不能由上传快响应单独推断全部事件正确。
