# 内存指标固定数据集

本文用于验证 PSS、VSS、Java 堆的去重、缺失值和分位数口径，不代表真实设备数据。

五条固定事件使用 UUID v4 `processId`，并在同一进程范围内保持一致；重试、并发重复和跨服务重启都必须保留该值。主进程可以复用本次启动的 `sessionId`，子进程则必须生成独立进程 UUID。

## 事件

在同一应用、同一进程和查询区间内发送五个 `memory_sample` 事件：

| eventId | pssBytes | vssBytes | javaHeapUsedBytes | 说明 |
|---|---:|---:|---:|---|
| memory-0 | 0 | 10 | null | 零是合法值 |
| memory-1 | 100 | 20 | 5 | 参与三项统计 |
| memory-2 | 200 | null | 6 | VSS 缺失 |
| memory-3 | 300 | 40 | 7 | 参与三项统计 |
| memory-missing | null | 50 | 8 | PSS 缺失 |

再次上传 `memory-1` 时必须使用相同的 `eventId` 和载荷。查询应将重复事件折叠为一条。

## 期望

去重后 PSS 有效样本为 `0、100、200、300`，`sampleCount=4`，平均值和 P50 为 `150`，P90 为 `270`，P95 为 `285`，P99 为 `297` 字节。VSS 和 Java 堆分别只使用自身非 null 值；缺失值不补零。

固定数据集也应覆盖：

- `occurredAt == from` 被计入，`occurredAt == to` 被排除；
- 中间没有事件的 UTC 小时返回 `sampleCount=0` 和所有统计值 `null`；
- `foreground=false` 与 `processName` 筛选只返回匹配采样；
- 32/64 位参数、未知查询参数、非法 metric 和超过 31 天范围返回 400；
- 同一事件并发上传或跨服务重启重试，最终统计仍只贡献一次。

## 执行边界

后端内存适配器和统计单元测试使用本数据集。真实 ClickHouse 执行需要单独运行初始化脚本 `backend/src/main/resources/db/clickhouse/005_memory_metrics.sql` 并记录 ClickHouse 版本、写入行数和 `FINAL` 查询结果；没有执行真实数据库时，不把内存测试描述为 ClickHouse 验收。Android 真机采集、客户端定时策略和生产容量也不由本文证明。

## 2026-09-09 本地外部验收记录

在当前 Windows 主机使用 Java 21、Spring Boot 8080、Vite 5173 和 ClickHouse HTTP `26.7.3.19` 完成受控测试应用验收。应用包名为 `com.shanshui.memoryfixed20260909001`，通过 `/ingest/v1/batches` 上传本页五个固定事件，服务返回 `accepted=5`；重复上传 `memory-1` 返回 `duplicate=1`。进程名和 Activity 名称包含数字时，查询精确筛选仍能命中，`scene` 取采样时的 `com.shanshui.memoryfixed.MainActivity2`。

真实 ClickHouse 查询与内存适配器期望值一致：PSS 为 `0/100/200/300`，得到 `sampleCount=4`、平均值 `150`、P50/P90/P95/P99 为 `150/270/285/297`；VSS 和 Java 堆分别只统计非 null 值。SQL 使用 `quantileExactInclusiveIf` 实现与 `(n-1)×p` 相同的线性插值。并发重复上传、服务重启后的同一 `eventId` 重试以及跨接收日期重复行均通过 `FINAL` 折叠为一条；一次跨接收日期夹具写入后原始行数为 7、唯一事件数为 6，`FINAL` 行数为 6。

Edge 本地登录态页面完成上传后查询、进程与 Activity 筛选、PSS/VSS/Java 堆页签切换和空 UTC 桶显示检查，并在 1280×900、1440×900、1920×1080 视口检查布局。该记录只证明当前固定规模、本机服务和受控浏览器链路；不代表 Android 真机采集、客户端持久队列、生产容量、跨浏览器或高可用验收。
