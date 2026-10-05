# 后端接口查询正确性与资源验收

2026-10-04 在本机 Java 21、Docker Desktop 的独立 Testcontainers 中验证。ClickHouse 固定为 `26.7.3.19`，PostgreSQL 为 `16-alpine`。Docker 虚拟机可用 15 CPU、8,318,844,928 字节内存；容器未另设限额，ClickHouse `max_threads=15`。本记录仅证明合成数据、单实例顺序查询，不能外推生产并发或 p95/p99。

复现命令（从仓库根目录，Docker 必须可用）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) bash backend/gradlew -p backend test --tests "*ClickHouseJankAggregationTests" --tests "*ClickHouseMemoryLeakAggregationTests" --tests "*AuthenticationHttpPostgresTests"
```

测试使用发布的 004/006 表结构和 007 进程列创建独立事实表。合成数据不含真实设备或凭据，结束后容器销毁。原始资源输出保存在未跟踪的 `backend/build/query-evidence/`，以下保留本次结果。

## 口径与完整性

- Jank：120 个唯一事件、60 个 Issue，其中一个 Issue 有 61 条，其余各一条。同一时间全部并列，事件 ID 决定续页；limit=1/20/50 的总览和趋势均为 120，精确 P50/P90/P99 为 59/107/118 ms，61 条 Issue 的精确及估算 P50 均为 30 ms，合法零值计入。页遍历覆盖 60 个 Issue 和 61 个事件且无重复。摘要使用故意无效的采样 JSON 仍成功，query_log 确认未投影这些列。
- 内存泄漏：固定 A=3/B=1、多设备和版本、重复报告和路径、空路径、跨应用和特殊字符；扩展至超过 100 个 signature，与内存参考的完整 items 和总数逐字段对照。全部 sort/order、pageSize=1/20/100、末页和 page=2147483647 覆盖；同一最新时间按 eventId 字符串升序选路径，versions 未截断。
- 内存参考覆盖缺失耗时、奇偶与单样本秩。持久化 schema 保留非空 UInt64，本轮没有新增可空耗时迁移。趋势 2000 桶通过，2001 桶在访问仓储前拒绝，包括末端额外 1 ns 的情况。

## 预算与失败

默认单次执行 2000 ms（Jank 可指定至 5000 ms）、扫描 5,000,000 行/512 MiB、数据库内存 256 MiB、HTTP 正文 8 MiB。`wait_end_of_query=1` 和数据库 overflow=throw 防止部分成功；请求头与成功/错误正文共享客户端截止时间（执行预算加 250 ms HTTP 余量）。

真实 ClickHouse 将扫描行上限及内存上限降至 1 时返回 422；受控 sleep 查询在 10 ms 预算下返回 408；错误表返回 503；内存问题完整路径结果超过 20 字节正文预算返回 422。受控 HTTP 服务覆盖已发响应头但正文迟缓的成功/错误响应，以及取消与字节超限。Agent 保留 400/408/422/503，MCP 协议测试保留相同代码和首次绝对时间窗。没有将拒绝样本计为成功或空结果。

## HTTP 与页面的独立边界

`AuthenticationHttpPostgresTests` 四项使用真实 Servlet、随机端口、PostgreSQL JDBC 会话及独立 Cookie 容器，覆盖旧 Session 拒绝、首个 PATCH、CSRF 刷新、退出、网页/Agent/Worker 框架错误、Jank 跨页和详情、报告末页和趋势及 Token 应用隔离。该 HTTP 查询综合样本使用内存事实适配器；ClickHouse 语义及预算由独立真实数据库测试证明，没有将其描述为 HTTP + ClickHouse 一条综合链路。

前端本地 Vite + 模拟 API 页面通过浏览器检查：总览 422 的缩小范围提示、成功 Issue 保留、续页 INVALID_CURSOR 后“重新查询”替换第一页及恢复分页。[页面截图](../../../frontend/docs/knowledge-base/evidence/jank-query-recovery.jpg)属于模拟 API 视觉验收。云端未部署，HTTPS 入口与生产安全及容量需要独立验收。


## 十万卡顿事件

100,000 个事件在同一 UTC 小时，60 个 Issue，7 个会话、3 个设备，耗时为序号乘 1 ms；同一毫秒并列。

| 查询 | 数据库耗时 ms | HTTP 总耗时 ms | 扫描行 | 扫描字节 | 峰值内存字节 | 数据库结果行 | 实际 HTTP 正文字节 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 事件摘要（limit=50） | 3 | 5.16 | 108192 | 5487192 | 7606092 | 51 | 24805 |
| 问题页（limit=50） | 14 | 15.05 | 100000 | 7784693 | 10870946 | 51 | 13354 |
| 总览/趋势 | 5 | 6.14 | 100000 | 5184693 | 8676008 | 1 | 115 |
| 事件摘要（limit=1） | 4 | 4.85 | 108192 | 5484785 | 9529776 | 2 | 958 |
| 问题页（limit=1） | 15 | 16.29 | 100000 | 7784693 | 10878609 | 2 | 523 |
| 总览/趋势 | 6 | 7.26 | 100000 | 5184693 | 8677032 | 1 | 115 |

实际执行计划（小页）：

```text
Output: fingerprint, count(), uniqExactIf(session_id, notEmpty(session_id)), uniqExactIf(anonymous_device_id, notEmpty(anonymous_device_id)), toUnixTimestamp64Milli(min(event_time)), toUnixTimestamp64Milli(max(event_time)), argMin(tuple(fingerprint_version, scene, algorithm_version), tuple(event_time, event_id)), if(empty(arraySort(groupArray(message_duration_ns))), NULL, arraySort(groupArray(message_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(message_duration_ns))) * 0.5)))] / 1000000.), if(empty(arraySort(groupArray(message_duration_ns))), NULL, arraySort(groupArray(message_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(message_duration_ns))) * 0.9)))] / 1000000.), if(empty(arraySort(groupArray(message_duration_ns))), NULL, arraySort(groupArray(message_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(message_duration_ns))) * 0.99)))] / 1000000.), if(empty(arraySort(groupArray(estimated_duration_ns))), NULL, arraySort(groupArray(estimated_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(estimated_duration_ns))) * 0.5)))] / 1000000.), if(empty(arraySort(groupArray(estimated_duration_ns))), NULL, arraySort(groupArray(estimated_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(estimated_duration_ns))) * 0.9)))] / 1000000.), if(empty(arraySort(groupArray(estimated_duration_ns))), NULL, arraySort(groupArray(estimated_duration_ns))[greatest(1, toUInt64(ceil(length(arraySort(groupArray(estimated_duration_ns))) * 0.99)))] / 1000000.)

Limit (preliminary LIMIT)
│  Limit 2
│  Offset 0
└──Sorting (Sorting for ORDER BY)
   │  Sort description: count() DESC, toUnixTimestamp64Milli(max(event_time)) DESC, fingerprint ASC
   │  Limit 2
   └──Aggregating
      │  Keys: fingerprint
      │  Aggregates: count(), uniqExactIf(session_id, notEmpty(session_id)), uniqExactIf(anonymous_device_id, notEmpty(anonymous_device_id)), min(event_time), max(event_time), argMin(tuple(fingerprint_version, scene, algorithm_version), tuple(event_time, event_id)), groupArray(message_duration_ns), groupArray(estimated_duration_ns)
      │  Skip merging: 0
      └──Filter ((WHERE + Change column names to column identifiers))
         │  Filter column: app_id = \'dba11e7a-ef64-3c60-9291-449fbb12c1d6\' AND event_time >= \'2026-10-01 00:00:00.000000000\' AND event_time < \'2026-10-02 00:00:00.000000000\' AND notEmpty(fingerprint)
         └──ReadFromMergeTree (default.apm_jank_event)
               Read type: Default
               FINAL: 1
               Parts: 1 | Granules: 13
               Output: fingerprint, session_id, anonymous_device_id, event_time, fingerprint_version, scene, algorithm_version, event_id, message_duration_ns, estimated_duration_ns, app_id
               Indexes:
                 Min-Max
                   Keys:
                     event_time
                   Condition: and((event_time in (-Inf, \'1790899200\')), (event_time in [\'1790812800\', +Inf)))
                   Parts: 1/1
                   Granules: 13/13
                 Partition
                   Keys:
                     toYYYYMM(event_time)
                   Condition: and((toYYYYMM(event_time) in (-Inf, 202610]), (toYYYYMM(event_time) in [202610, +Inf)))
                   Parts: 1/1
                   Granules: 13/13
                 PrimaryKey
                   Keys:
                     app_id
                     event_time
                   Condition: and((event_time in (-Inf, \'1790899200\')), and((event_time in [\'1790812800\', +Inf)), (app_id in [\'dba11e7a-ef64-3c60-9291-449fbb12c1d6\', \'dba11e7a-ef64-3c60-9291-449fbb12c1d6\'])))
                   Parts: 1/1
                   Granules: 13/13
                   Search Algorithm: binary search
                 Ranges: 1
```

小页与大页均保留完整聚合输入；Issue SQL 的最终 limit+1 为 2/51，Java 最多映射这些标量行。默认预算下十万事件顺序查询成功，更大规模及不同分布尚未验证。


## 一万份多路径报告

10,000 份报告，每份 A/B/C 三条路径，同一 UTC 小时，100 个设备、150 个版本；共 30,000 次事件及 signature 组合。

| 查询 | 数据库耗时 ms | HTTP 总耗时 ms | 扫描行 | 扫描字节 | 峰值内存字节 | 数据库结果行 | 实际 HTTP 正文字节 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 总览/趋势 | 15 | 16.95 | 10000 | 7686635 | 11960260 | 1 | 62 |
| 问题页（pageSize=100） | 50 | 51.56 | 30000 | 23059905 | 44973324 | 1 | 3836 |
| 问题页（pageSize=1） | 50 | 52.18 | 30000 | 23059905 | 44943588 | 1 | 1324 |

实际执行计划（小页）：

```text
Output: 3, count(), uniqExact(anonymous_device_id), [(\'A\', 10000, 100, 1790812800000, \'{"signature":"A","gcRoot":"root","leakReason":"reason","instanceCount":1,"path":[{"reference":"root","referenceType":"static","declaredClass":"Root"},{"reference":"a","referenceType":"instance","declaredClass":"Class"}],"contentHash":""}\', [\'v0\', \'v1\', \'v10\', \'v100\', \'v101\', \'v102\', \'v103\', \'v104\', \'v105\', \'v106\', \'v107\', \'v108\', \'v109\', \'v11\', \'v110\', \'v111\', \'v112\', \'v113\', \'v114\', \'v115\', \'v116\', \'v117\', \'v118\', \'v119\', \'v12\', \'v120\', \'v121\', \'v122\', \'v123\', \'v124\', \'v125\', \'v126\', \'v127\', \'v128\', \'v129\', \'v13\', \'v130\', \'v131\', \'v132\', \'v133\', \'v134\', \'v135\', \'v136\', \'v137\', \'v138\', \'v139\', \'v14\', \'v140\', \'v141\', \'v142\', \'v143\', \'v144\', \'v145\', \'v146\', \'v147\', \'v148\', \'v149\', \'v15\', \'v16\', \'v17\', \'v18\', \'v19\', \'v2\', \'v20\', \'v21\', \'v22\', \'v23\', \'v24\', \'v25\', \'v26\', \'v27\', \'v28\', \'v29\', \'v3\', \'v30\', \'v31\', \'v32\', \'v33\', \'v34\', \'v35\', \'v36\', \'v37\', \'v38\', \'v39\', \'v4\', \'v40\', \'v41\', \'v42\', \'v43\', \'v44\', \'v45\', \'v46\', \'v47\', \'v48\', \'v49\', \'v5\', \'v50\', \'v51\', \'v52\', \'v53\', \'v54\', \'v55\', \'v56\', \'v57\', \'v58\', \'v59\', \'v6\', \'v60\', \'v61\', \'v62\', \'v63\', \'v64\', \'v65\', \'v66\', \'v67\', \'v68\', \'v69\', \'v7\', \'v70\', \'v71\', \'v72\', \'v73\', \'v74\', \'v75\', \'v76\', \'v77\', \'v78\', \'v79\', \'v8\', \'v80\', \'v81\', \'v82\', \'v83\', \'v84\', \'v85\', \'v86\', \'v87\', \'v88\', \'v89\', \'v9\', \'v90\', \'v91\', \'v92\', \'v93\', \'v94\', \'v95\', \'v96\', \'v97\', \'v98\', \'v99\'])]

Aggregating
│  Keys:
│  Aggregates: count(), uniqExact(anonymous_device_id)
│  Skip merging: 0
└──Aggregating
   │  Keys: event_id, event_time, anonymous_device_id, app_version, JSONExtractString(arrayZip(JSONExtractArrayRaw(gc_paths_json), arrayEnumerate(JSONExtractArrayRaw(gc_paths_json))).1, \'signature\')
   │  Skip merging: 0
   └──Filter ((WHERE + (Change column names to column identifiers + (Project names + (Projection + WHERE [split])))))
      │  Filter column: notEmpty(JSONExtractString(arrayZip(JSONExtractArrayRaw(gc_paths_json), arrayEnumerate(JSONExtractArrayRaw(gc_paths_json))).1, \'signature\')) AND notEmpty(JSONExtractArrayRaw(arrayZip(JSONExtractArrayRaw(gc_paths_json), arrayEnumerate(JSONExtractArrayRaw(gc_paths_json))).1, \'path\'))
      └──ArrayJoin (ARRAY JOIN)
         │  ARRAY JOIN arrayZip(JSONExtractArrayRaw(gc_paths_json), arrayEnumerate(JSONExtractArrayRaw(gc_paths_json)))
         └──Filter ((((WHERE [split] + WHERE) + (DROP unused columns before ARRAY JOIN + (ARRAY JOIN actions + Change column names to column identifiers))))[split])
            │  Filter column: app_id = \'6f1c44d9-a7ed-36ec-b624-f86afc3ffab5\' AND event_time >= \'2026-10-01 00:00:00.000000000\' AND event_time < \'2026-10-02 00:00:00.000000000\'
            └──ReadFromMergeTree (default.apm_memory_report)
                  Read type: Default
                  FINAL: 1
                  Parts: 32 | Granules: 32
                  Output: event_id, event_time, anonymous_device_id, app_version, gc_paths_json, app_id
                  Indexes:
                    Min-Max
                      Keys:
                        app_id
                      Condition: (app_id in [\'6f1c44d9-a7ed-36ec-b624-f86afc3ffab5\', \'6f1c44d9-a7ed-36ec-b624-f86afc3ffab5\'])
                      Parts: 32/32
                      Granules: 32/32
                    Partition
                      Condition: true
                      Parts: 32/32
                      Granules: 32/32
                    PrimaryKey
                      Keys:
                        app_id
                      Condition: (app_id in [\'6f1c44d9-a7ed-36ec-b624-f86afc3ffab5\', \'6f1c44d9-a7ed-36ec-b624-f86afc3ffab5\'])
                      Parts: 32/32
                      Granules: 32/32
                      Search Algorithm: binary search
                    Ranges: 32
```

问题页一条 HTTP 查询的 CTE 被 ClickHouse 展开为三次事实扫描，因此 read_rows=30,000、约 23 MiB，这是当前已验证代价。Java 只收到总计和当前页代表路径及版本集合，不接收 10,000 份 report_json。改变 pageSize 不减少全范围统计扫描；趋势只扫描一次。

## 规格场景与证据索引

| 规格主题 | 正式测试证据 |
|---|---|
| 登录旧/无会话、CSRF 首写、失败与退出 | AuthenticationApiIntegrationTests、AuthenticationHttpPostgresTests、前端 authApi.test.ts |
| 框架 400/405/415、敏感字段、50 项上限、安全/业务错误保留 | FrameworkApiErrorTests、AuthenticationHttpPostgresTests；全量上报与权限回归 |
| PATCH 遗漏/null/空白、无操作、原子校验、权限及 CSRF | AppApiIntegrationTests、appApi.test.ts、AppSettingsView.test.ts |
| Jank 完整总览/趋势/Issue、小页统计、排序续页、默认窗、绑定错误、空数据/预算 | JankQueryServiceTests、JankDatasetStatisticsTests、ClickHouseJankAggregationTests、ClickHouseHttpClientQueryBudgetTests |
| 报告去重及筛选、完整路径/版本、全范围分母、末页、桶数、预算/失败 | MemoryLeakReportTests、ClickHouseMemoryLeakAggregationTests、AuthenticationHttpPostgresTests |
| 网页显式恢复、时间窗及旧响应、预算区域失败 | useJankQuery.test.ts、useJankPagination.test.ts、JankIssuesView.test.ts；本地模拟 API 浏览器截图 |
| 网页/Agent 一致、页上限、400/408/422/503、MCP 显式续页 | AgentQueryApiIntegrationTests、AgentJankQueryErrorTests、AuthenticationHttpPostgresTests、mcp-server/tests/tools.test.ts |

这些测试共同覆盖本变更六份规格；独立生产质量及容量没有据此提升为已验收。
