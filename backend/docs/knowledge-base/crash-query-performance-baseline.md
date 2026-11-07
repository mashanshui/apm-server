# Crash 查询下推与容量基线

## 当前查询路径与预算

Crash/app_start 只查 `apm_crash_fact FINAL`，按应用、发生时间 `[from,to)` 与白名单维度过滤；状态侧从 `apm_crash_state FINAL` 取最新版本再应用指纹筛选。概览、趋势、Issue 和事件摘要仅投影轻量列，不引用 stack_payload。启动分母来自同一当前事实数据集，指纹筛选不丢启动分母。Issue 在数据库聚合、排序及 limit+1 分页；事件页按发生时间倒序/eventId 升序续读。分页不是快照，后台归组或迟到事件会改变排行，旧临时指纹可能为空。

单事件详情先定位不可变事实，再按精确应用/构建/发生时间/eventId 的 PREWHERE 读最新单份状态。后台候选限定应用、构建、最近 30 天及当前可处理状态，先选最多 100 个身份再读取这些堆栈。排序前缀为应用/构建/发生时间，eventId 有 bloom filter；候选没有全量载荷扫描后 Java 筛选。

上述查询默认超时 2 秒、最多 500 万读取行/512 MiB 读取/256 MiB 数据库内存/8 MiB 响应。客户端只可在服务端范围内调整超时，不能扩大其他预算。资源/超时明确失败，不返回截断或部分成功；大载荷后台批次有限减半，单条仍超限则退避。数据库检查存在粒度，取消请求不保证 SQL 立即停止。

## 新模型后台候选基线

2026-11-05 在 macOS arm64、Java 21.0.11、Docker Desktop（15 个可见 CPU、约 7.75 GiB 内存）上使用独立 Testcontainers ClickHouse 26.7.3.19。生成 100 万当前事实及 pending 状态，分布在十个构建和十天内，每条完整链约 2 KiB，初始待处理比例 100%。一个构建、30 天候选查询只返回 100 条。

| 查询 | 耗时 | read_rows | read_bytes | memory_usage | 返回 |
|---|---:|---:|---:|---:|---:|
| 后台候选单次 | 45 ms | 342,172 | 20,530,735 | 40,735,336 | 100 条 / 332,591 bytes |
| 并行时后台候选五次 | 33～57 ms | 每次 342,172 | 每次 20,530,735 | 最大 49,146,592 | 每次 100 条 |
| 并行时前台一天概览五次 | 34～40 ms | 每次 551,011 | 每次 27,037,215 | 最大 62,223,913 | 每次 1 行 / 680 bytes |
| 一天趋势 | 31 ms | 551,011 | 27,037,215 | 58,081,418 | 25 桶 |
| 一天事件页 | 46 ms | 551,011 | 39,066,203 | 68,315,063 | limit+1 = 51 行 |
| 一天 Issue 页 | 48 ms | 551,011 | 37,455,784 | 62,837,611 | 1 个问题 |

前台一天窗口与后台候选并行五轮，全部通过默认预算。查询计划的摘要投影不含 stack_payload；后台读取字节少于 64 MiB，低于单构建约 200 MiB 完整堆栈量。读取行预算设为 1 时返回 QUERY_RESOURCE_LIMIT，未增加预算。日志只取真实 SELECT 的 QueryFinish，排除 EXPLAIN 和测量 SQL。

从仓库根目录复跑：

```bash
RUN_CRASH_BENCHMARK=true JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  GRADLE_USER_HOME="$PWD/.gradle-local" bash backend/gradlew -p backend test \
  --tests '*BackgroundCrashQueryPerformanceTests' --no-daemon
```

实际 SQL、EXPLAIN indexes=1 及 query_log 输出到被忽略的 `backend/build/query-evidence/background-crash.txt`。用例默认条件跳过，显式执行时使用临时服务和合成数据，测试后销毁。旧 raw/detail 模型的基准类已移除，旧数据不作为新容量结论。当前数字不证明百万全窗口、真实租户混合分布、长期合并、R8 生产吞吐或 SLA。

## HTTP 积压与上传验证

真实后台、PostgreSQL、ClickHouse、受控文件卷和 HTTP 的混合验收与恢复边界见[隔离验收](../../../docs/validation/background-crash/README.md)。上传只校验文件并在管理事务登记构建工作，不在请求内遍历或还原历史。后台每进程最多一个构建、一批 100；未确认发布默认至多 100 条/16 MiB，单份编码载荷最多 2 MiB。慢 R8、事务登记失败、租约过期、通知丢失及首次成功幂等由数据库/故障注入测试验证，不能由上传快响应单独推断全部事件正确。
