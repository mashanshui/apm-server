# Crash 查询下推与容量基线

## 当前实现

Crash 概览、趋势、Issue 和事件摘要分别通过查询端口执行。ClickHouse 先按应用、`[from,to)`、事件类型和允许的维度筛选，再按 `eventId` 选择最新接收的逻辑事件；概览和趋势使用精确去重，Issue 在数据库聚合排序，事件页仅返回摘要列。只有单事件详情关联异常链表。Issue 游标保存事件数、最后出现时间和指纹；事件游标保存发生时间和事件 ID。游标包含版本、首次绝对时间窗及应用和筛选摘要；旧游标或跨查询复用返回 `INVALID_CURSOR`。分页不是数据快照，新增或迟到事件可改变排行。

单查询初始预算为 2 秒（请求最大 5 秒）、最多扫描 500 万行/512 MiB、数据库内存 256 MiB、HTTP 响应 8 MiB。ClickHouse 超时或资源限制使用 `throw`，HTTP 等待与响应字节另有上限；失败返回明确错误，不把部分统计当成功。配置见 `apm.query.*`，请求只能缩短超时。数据库检查有粒度，取消请求后不承诺数据库严格实时停止。

## 2026-09-28 合成基线

在 macOS aarch64 主机（48 GiB 内存）、Docker Desktop 虚拟机（约 7.75 GiB 内存、15 个可见 CPU）、ClickHouse `26.7.3.19`、Java `21.0.11` 上使用 `CrashQueryPerformanceBaselineTests`。每个规模生成独立应用，一小时内 10% Crash、90% `app_start`，事件 ID 唯一；每组执行 5 次概览查询，分别以并发 1 和 2 调用。Java 时间为端到端调用耗时，P95 在 5 个样本下等于最大值，不代表稳定生产尾延迟。JVM 峰值是测试进程各堆内存池峰值之和，约 92.3 MiB，不是单次查询的增量。

| 事件数 | 并发 | p50 / p95 | 结果 | ClickHouse `read_rows` / `read_bytes` | 结果传输字节 |
|---:|---:|---:|---|---:|---:|
| 1 万 | 1 | 6 / 17 ms | 5 次成功 | 50,000 / 4,257,800 | 3,400 |
| 1 万 | 2 | 6 / 6 ms | 5 次成功 | 累计 100,000 / 8,515,600 | 累计 6,800 |
| 10 万 | 1 | 26 / 28 ms | 5 次成功 | 500,000 / 43,569,370 | 3,400 |
| 10 万 | 2 | 28 / 30 ms | 5 次成功 | 累计 1,000,000 / 87,138,740 | 累计 6,800 |
| 100 万 | 1 | 32 / 41 ms | 5 次均明确 `QUERY_RESOURCE_LIMIT` | 3,645,908 / 325,256,158 | 0 |
| 100 万 | 2 | 41 / 43 ms | 5 次均明确 `QUERY_RESOURCE_LIMIT` | 累计 7,308,200 / 651,978,914 | 0 |

并发 2 一行的查询日志计数是同一应用此前并发 1 的 5 次与本组 5 次累计，故对应扫描和传输也是 10 次累计，不能作为单次数据。100 万全窗口在 256 MiB 数据库内存预算下拒绝；它证明失败边界明确，不构成百万行全窗口容量通过。

将窗口缩为同一小时的首秒后，1 万/10 万/100 万集的精确 Crash 数分别为 3/28/278；查询日志 `read_rows` 分别为 10,000/8,192/18,865，`result_bytes` 均为 680。1 万集仍读取全部行，是小表索引粒度效应；较大两组没有将全量事件传回 Java。真实 ClickHouse 固定集测试同时与内存参考口径对照重复物理行、跨桶会话、指纹筛选、分母不足、空值和分页；`system.query_log` 确认列表不关联堆栈表，结果行数仅为当前页。

复跑命令（从 `backend/`，macOS 上以 Android Studio JBR 启动 Gradle，实际测试工具链为 Java 21）：

```sh
RUN_CRASH_BENCHMARK=true JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  bash ./gradlew test --tests 'com.shanshui.apmserver.CrashQueryPerformanceBaselineTests' --no-daemon
```

运行结果在 `build/test-results/test/TEST-com.shanshui.apmserver.CrashQueryPerformanceBaselineTests.xml` 的 `CRASH_BENCH` 行。该测试默认跳过，不向正式业务表写入；其容器和合成数据在测试结束后销毁。固定集与资源错误集成测试在常规 `test` 中运行。此基线没有生产硬件、真实租户混合负载、网络代理及长时间运行证据。
