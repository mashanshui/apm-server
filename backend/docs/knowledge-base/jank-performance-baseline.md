# 卡顿指标性能基线

本文记录服务端固定规模的 JVM 内存仓库基线，不代表真实 ClickHouse 集群容量或移动端采集开销。

## 测试范围

- 测试类：`JankMetricsPerformanceTests`
- 数据量：2,000 条事件（1,000 条场景帧汇总、1,000 条前台挂起汇总），100 个匿名设备，包含两个场景。
- 查询组合：FPS 聚合、设备日挂起率二阶段聚合、场景和设备型号多维聚合。
- 预热 5 轮，正式测量 20 轮；时间为同一 JVM 内三次查询组合的端到端耗时。
- 堆使用量是 `Runtime.totalMemory - freeMemory` 的前后差值，仅作粗略观测。

## 本机结果（2026-08-27，Windows/JDK 17）

```text
JANK_METRICS_BENCH records=2000 p95Ms=13.386 p99Ms=14.978 heapDeltaMb=19.000
```

结果说明：本次 JVM 内存测试的 p95 为 13.386 ms、p99 为 14.978 ms，观测到的堆差值约 19.000 MB。结果会受 JVM 预热和宿主机负载影响；测试只用于防止明显的算法退化，没有设定跨机器硬阈值，也没有据此推断 ClickHouse 生产延迟。

## 复现命令

以下命令从迁移后的仓库根目录执行；下文带日期的外部验收命令是历史记录，不表示本次重新执行。

```powershell
./backend/gradlew.bat -p backend test --no-daemon --tests com.shanshui.apmserver.JankMetricsPerformanceTests
```

固定规模的真实 ClickHouse 写入、合并、`FINAL` 查询、分位数和事实/详情一致性已完成一次外部验收；大规模数据、并发度、资源使用和故障恢复仍需在目标生产规格实例上单独压测。

## 外部 ClickHouse 验收记录

2026-08-27 在当前 Windows 主机通过 ClickHouse HTTP 接口完成固定规模外部验收。`docker version`/`docker info` 显示 Docker CLI 29.7.2、上下文为 `desktop-linux`，Docker Desktop Linux Engine 仍返回 HTTP 500；本次使用已运行的独立 ClickHouse HTTP 服务（`Test-NetConnection localhost -Port 8123` 为 `TcpTestSucceeded=True`，`GET /ping` 返回 `Ok.`），服务端版本查询结果为 `26.7.3.19`。本机未安装 `clickhouse` CLI，验收命令改用 HTTP JSONEachRow。

执行命令如下，脚本只读取未跟踪的 `.env.local` 中的 ClickHouse 管理账号，不输出凭据；应用 UUID 使用隔离值，避免污染其他数据：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/validate-jank-clickhouse.ps1 `
  -InitializeSchema -AppId 00000000-0000-0000-0000-000000000001
```

脚本执行 `002_jank_schema.sql` 的 8 条 `CREATE`（重复执行均返回 HTTP 200），从 `backend/src/test/resources/fixtures/jank-dataset.json` 写入 11 条输入记录（9 个唯一 `eventId`），并验证以下结果：

| 验收项 | 结果 |
| --- | --- |
| 原始事件 `uniqExact(event_id)` / `FINAL` | 9 / 9；重复 `jank-001`、`frame-001` 未增加最终事件数 |
| 卡顿事实/详情 `FINAL` | 3 / 3；两张表的 `event_id` 集合完全一致 |
| Issue 聚合 | 2 组；事件去重数分别为 2、1，设备去重数分别为 2、1 |
| Issue 精确耗时分位数 | 同一 Issue 为 `[500, 700, 700]` ms；另一 Issue 为 `[300, 300, 300]` ms |
| FPS | `fps-v1`：2 条、平均 40、P50/P90/P99 为 50/30/30；`fps-v2`：1 条、均为 45 |
| 设备日挂起率 | `suspension-v1` 合并 2 个区间为 1 条有效设备日、均为 3；`suspension-v2` 为 1 条有效设备日、均为 2 秒/小时 |
| FPS UTC 小时趋势 | 两个时间桶/算法点：`2026-08-15 10:00 + fps-v1` 平均 40、`2026-08-16 00:00 + fps-v2 = 45` |
| 设备日挂起率 UTC 天趋势 | 两个时间桶/算法点：8 月 15 日 `suspension-v1 = 3`、8 月 16 日 `suspension-v2 = 2` 秒/小时 |

首次执行还暴露了 ClickHouse 别名替换导致挂起率二阶段查询嵌套聚合的问题，以及 `DateTime64` 时间边界不能直接使用 `Instant.toString()` 的 `T...Z` 格式；已分别启用 `prefer_column_name_to_alias=1` 并统一格式化为 UTC `yyyy-MM-dd HH:mm:ss.SSS` 后复验通过。扩展固定样本后还发现“负值 + 相同分位参数”在偶数 FPS 样本下与内存降序最近秩不一致，SQL 已改为等价的升序参数 `0.50/0.10/0.01`。2026-08-27 使用隔离项目 `jank-validation-20260827145638-d257e6ad` 复验 11 条输入、9 个唯一事件，FPS 小时趋势和挂起率 UTC 天趋势均通过。该记录证明固定规模 schema、写入、重复、查询、时间桶和分位数可用，不代表生产规模容量、并发延迟、TTL、故障恢复或正式权限验收。

2026-08-30 在同一 ClickHouse HTTP 环境再次执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/validate-jank-clickhouse.ps1 -InitializeSchema
```

脚本自动使用隔离项目 `jank-validation-20260830025430-fc9b3902`，完成 8 条 `CREATE`、2 条质量字段 `ALTER`、11 条输入写入和 9 个唯一事件校验。原始表 `FINAL` 为 9 行，卡顿事实/详情 `FINAL` 均为 3 行，Issue、FPS、设备日挂起率及两类趋势查询均通过；ClickHouse 版本为 `26.7.3.19`。本次结果仍只代表固定规模外部冒烟，不代表生产规模容量、并发延迟、TTL、故障恢复或正式权限验收。

## 当前应用身份结构验收（2026-09-01）

应用身份变更后，使用当前脚本和空的测试业务表重新执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/validate-jank-clickhouse.ps1 `
  -InitializeSchema
```

本次按 004 受控流程执行 36 条 schema 语句，写入 11 条输入、9 个唯一事件；原始事件 `FINAL` 为 9 行，卡顿事实/详情 `FINAL` 均为 3 行，Issue 为 2 组，FPS 与前台挂起率聚合及 UTC 趋势均通过。ClickHouse 版本为 `26.7.3.19`。在保留固定数据时再次初始化被明确拒绝，并提示业务表非空、脚本不会静默删除；验收结束后已清理 10 张当前应用业务表，复核总行数为 0。

这次记录证明当前 `app_id UUID`、独立 `package_name`、004 schema、去重和固定查询口径在本地 ClickHouse 上成立；不代表生产容量、并发延迟、TTL、故障恢复或 Android/processor v3 ZIP 兼容性。
