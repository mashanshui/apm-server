# 卡顿指标性能基线

本文记录服务端固定规模的 JVM 内存仓库基线，不代表真实 ClickHouse 集群容量或移动端采集开销。

## 测试范围

- 测试类：`JankMetricsPerformanceTests`
- 数据量：2,000 条事件（1,000 条场景帧汇总、1,000 条前台挂起汇总），100 个匿名设备，包含两个场景。
- 查询组合：每轮执行 FPS 聚合、设备日挂起率二阶段聚合和一次多维查询；预热使用 FPS 的场景分组，正式测量使用挂起率的设备型号分组。
- 预热 5 轮，正式测量 20 轮；时间为同一 JVM 内三次查询组合的端到端耗时。
- 堆使用量是 `Runtime.totalMemory - freeMemory` 的前后差值，仅作粗略观测。

## 本机结果（2026-08-27，Windows/JDK 17）

```text
JANK_METRICS_BENCH records=2000 p95Ms=13.386 p99Ms=14.978 heapDeltaMb=19.000
```

结果说明：本次 JVM 内存测试的 p95 为 13.386 ms、p99 为 14.978 ms，观测到的堆差值约 19.000 MB。结果会受 JVM 预热和宿主机负载影响；测试只用于防止明显的算法退化，没有设定跨机器硬阈值，也没有据此推断 ClickHouse 生产延迟。

## 复现命令

以下命令从仓库根目录执行；既有带日期基线不表示本次重新执行。

```powershell
./backend/gradlew.bat -p backend test --no-daemon --tests com.shanshui.apmserver.JankMetricsPerformanceTests
```

macOS 使用已安装的 Java 21：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  GRADLE_USER_HOME="$PWD/.gradle-local" \
  bash ./backend/gradlew -p backend test --no-daemon --tests com.shanshui.apmserver.JankMetricsPerformanceTests
```

测试仅断言 FPS 输入数量和分位数耗时顺序，未设置性能回归阈值；历史 p95/p99 不是当前环境的性能保证。

固定规模的真实 ClickHouse 写入、合并、`FINAL` 查询、分位数和事实/详情一致性已完成一次外部验收；大规模数据、并发度、资源使用和故障恢复仍需在目标生产规格实例上单独压测。

## 当前应用身份固定集

2026-09-01 在 ClickHouse `26.7.3.19` 的空测试库执行 004 应用身份结构，并在 2026-09-24 用 macOS 当前脚本复验。输入 11 条、9 个唯一事件，使用 UUID app_id 和独立 package_name；关键期望如下：

| 检查 | 期望 |
|---|---|
| 原始事件 FINAL | 9 个唯一事件，重复事件不增加最终计数 |
| 卡顿事实 / 详情 FINAL | 各 3 行，event_id 集合一致 |
| Issue | 2 组；事件数 2/1，设备数 2/1 |
| FPS | fps-v1：2 条、平均 40、P50/P90/P99 为 50/30/30；fps-v2：1 条、均为 45 |
| 挂起率 | suspension-v1 合并为 1 个有效设备日、3 秒/小时；v2 为 1 个、2 秒/小时 |
| 时间桶 | FPS 按 UTC 小时、挂起率按 UTC 天，并保持算法版本隔离 |
| 初始化保护 | 受影响业务表非空时拒绝 004，不静默删除 |

完整夹具与计算见[固定数据集](../../../docs/datasets/jank-fixed-dataset.md)。当前复验命令（根目录）：

```bash
python3 scripts/validate-jank-clickhouse.py
```

脚本将历史事件时间平移到最近一天；可用 `--app-id <UUID>` 指定隔离应用。`--initialize-schema` 仅在确认符合空表条件的测试库使用，不能在有业务数据的环境重跑全套初始化。旧 001～003 schema 的验收不作为当前结构证明。

### 可复用的 SQL 注意事项

- 二阶段聚合使用 `prefer_column_name_to_alias=1` 避免别名替换造成嵌套聚合。
- DateTime64 参数使用 UTC `yyyy-MM-dd HH:mm:ss.SSS`，不能直接使用 Instant 的 T/Z 格式。
- FPS 的降序最近秩通过升序参数 `0.50/0.10/0.01` 实现，不能用负值搭配相同参数替代；偶数样本应与内存参考对照。

固定集证明 schema、幂等、分位数和时间桶的有限正确性，不证明生产容量、并发、长期 TTL、故障恢复、权限或 Android SDK 生命周期。
