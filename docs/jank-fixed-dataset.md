# 卡顿固定数据集与期望结果

`backend/src/test/resources/fixtures/jank-dataset.json` 是服务端归一化存储与查询口径的固定样本，不是 `/ingest/v1/batches` 的客户端上传示例。它包含 11 条内部测试输入：4 条已归一化卡顿事实（`jank-001` 重复一次）、4 条场景帧汇总（`frame-001` 重复一次）和 3 条前台挂起汇总，共 9 个唯一 `eventId`。真实客户端卡顿个例必须走 `.rheajank.zip` 入口。

固定样本的 `processId` 使用 UUID v4；卡顿 JSON 事件和 ZIP manifest 都必须在产生、补传与重试过程中保留该进程实例身份。子进程不能复用其他进程的 UUID，服务端也不把数值 PID 当作兼容值。

## 卡顿期望

采样间隔为 100 ms，连续覆盖上限为 200 ms。`jank-001` 的精确消息耗时为 500 ms，样本覆盖 0～400 ms，估算覆盖为 400 ms，未覆盖为 100 ms。`jank-002` 的 100～500 ms 间隔形成空洞，精确耗时为 700 ms，估算覆盖为 300 ms，未覆盖为 400 ms。`jank-003` 的精确耗时为 300 ms，估算覆盖为 200 ms，未覆盖为 100 ms。估算值仅表示采样覆盖，不是方法精确耗时或 CPU 自耗时。

三个事件的算法标识均为 `jank-artifact-v2`，前两个应归入同一个 `jank-v1` 指纹 Issue，第三个归入另一个 Issue；重复上报不增加事件数、Issue 数或受影响设备数。质量字段分别为 expected/parsed/missing：`jank-001=5/4/1`、`jank-002=7/3/4`、`jank-003=3/2/1`。事件总数为 3，受影响会话数为 3，受影响设备数为 2，可归组事件数为 3。精确耗时 P50/P90/P99 为 500/700/700 ms。

## FPS 与挂起期望

`frame-001` 的 `fps-v1` 值为 50，重复记录不计入统计；`frame-003` 与它处于同一 UTC 小时且同为 `fps-v1`，值为 30。因此 `fps-v1` 去重后总记录数/有效记录数为 2，平均值为 40，按从高到低取秩的 P50/P90/P99 为 50/30/30。`frame-002` 的 `fps-v2` 值为 45，总记录数/有效记录数均为 1，平均值和各分位数均为 45。查询必须按算法版本隔离，并满足 `P50 >= P90 >= P99`。

`susp-001` 的前台时长为 1 小时、挂起 2 秒；同一设备、同一 UTC 日和同一稳定维度的 `susp-003` 为 1 小时、挂起 4 秒。`suspension-v1` 必须先合并为 2 小时前台、6 秒挂起，再得到唯一设备日记录的 3 秒/小时；`totalRecords=2`、`validDeviceDayRecords=1`，平均值/P50/P90/P99 均为 3。`susp-002` 属于下一 UTC 日和 `suspension-v2`，0.5 小时前台、挂起 1 秒，平均值和各分位数均为 2 秒/小时。没有有效前台分母时返回 `denominator_insufficient`，不能显示为零。

固定数据集查询 `GET /api/v1/apps/{appId}/jank-metrics/fps` 应返回两个算法版本分组，`GET .../suspension-rate` 也应返回两个分组；按 `scene=checkout` 或 `appVersion=3.2.0` 筛选时只保留对应稳定维度。把时间范围移到 2027 年后应得到 `status=no_data` 和空 `metrics`，而不是返回零分位数。`appId` 必须替换为当前登录用户有权限访问的系统 UUID。

统一趋势接口的固定期望如下：FPS `interval=hour` 返回 `2026-08-15T10:00:00Z + fps-v1`（平均 40、P50/P90/P99 为 50/30/30）和 `2026-08-16T00:00:00Z + fps-v2 = 45` 两个点；FPS `interval=day` 返回相同两个算法版本在两个 UTC 日桶中的点。挂起率 `interval=day` 返回 `2026-08-15 + suspension-v1 = 3` 和 `2026-08-16 + suspension-v2 = 2` 秒/小时两个点。`suspension_rate + hour` 必须返回 `INVALID_INTERVAL`，不得按小时拆分设备日口径。重复 `jank-001` 和 `frame-001` 都不得放大趋势统计。

以上数字是固定数据集的人工计算基线；自动化测试必须使用同一文件独立计算并核对，真实 ClickHouse 验收另行记录，不能用内存测试替代外部环境验证。
