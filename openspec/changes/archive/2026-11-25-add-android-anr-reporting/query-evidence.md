# ANR 查询规模验证

使用隔离的 ClickHouse 26.7.3.19，写入 10 万条合成事件，包含两个应用、四个版本、两种判定来源以及稀疏的 96 KiB 诊断正文。查询窗口为最近 24 小时，筛选一个应用、版本和判定来源。完整 SQL、`EXPLAIN indexes=1` 和 `system.query_log` 结果由测试输出至 `backend/build/query-evidence/anr.txt`。

| 查询 | 结果行数 | 读取行数 | 读取字节 | 查询内存 | 耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 概览 | 1 | 100000 | 3892520 | 7813105 | 6 ms |
| 列表，含判断下一页的一条 | 51 | 171864 | 5439761 | 8158381 | 5 ms |

查询计划显示应用和时间条件进入数据库过滤，列表排序与数量限制、概览去重聚合均由数据库执行。查询日志中的读取列不包含 `metadata_json` 或 `payload_json`。该数据分布下时间跳数索引检查了全部 32 个粒度，未减少扫描范围，读取量与内存量仍低于测试配置的上限。本结果只描述单次隔离合成数据测试，不代表生产容量。

复现命令为 `RUN_ANR_BENCHMARK=true bash backend/gradlew -p backend test --tests com.shanshui.apmserver.AnrQueryPerformanceTests --no-daemon`。
