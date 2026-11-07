# Crash 固定验收数据集

测试夹具位于 `backend/src/test/resources/fixtures/crash-dataset.json`，故意包含 13 条上报记录：8 条 `app_start`、4 个唯一 Crash `eventId`，以及 `crash-202` 的一次重复上报。

身份字段固定为合法 UUID v4：设备身份在安装范围内保持不变，启动身份按 `sessionId` 区分，主进程可以令 `processId=sessionId`；跨启动补传和重复上传必须保留原始 `processId`，不使用数值 PID 替换。

## 期望结果

| 层级 | 启动会话 | 唯一 Crash 事件 | 崩溃会话 | 受影响设备 | 每千会话崩溃率 | 无崩溃会话率 |
|---|---:|---:|---:|---:|---:|---:|
| 全量 | 8 | 4 | 4 | 4 | 500 | 0.5 |
| 3.2.0 | 5 | 2 | 2 | 2 | 400 | 0.6 |
| 3.3.0 | 3 | 2 | 2 | 2 | 666.6667 | 0.3333333 |

全部 pending、尚未上传 mapping 时，tmp-v1 按 buildId 隔离，共三个临时问题：build-320 的 IllegalStateException 为 2 条，build-330 的 IllegalStateException 和 NullPointerException 各 1 条。

成功回填规范化调用后，resolved-v1 不包含构建或版本身份，共两个正式问题：

- `java.lang.IllegalStateException` + `PaymentActivity.submit` 为同一指纹，共 3 个唯一事件、3 个会话、3 台设备；行号和用户数字变化不能拆组。
- `java.lang.NullPointerException` + `OrderRepository.load` 为另一指纹，共 1 个唯一事件、1 个会话、1 台设备。
- `crash-202` 的重复记录不能使任一统计值增加。

后台回填仅改变问题归属，不增加事件、会话、设备或分母；按原发生日期统计趋势。成功后旧临时指纹事件页为空，返回总览刷新；分页不是快照。`CrashDatasetStatisticsTests` 同时验证回填前后三个/两个问题及全部统计不变。固定夹具不进入开发数据库；真实 ClickHouse 测试使用保留期内的受控日期。

查询无 `app_start` 时，`crashRatePer1000Sessions` 和 `crashFreeSessionRate` 必须为 `null`，状态为 `denominator_insufficient`；不能以 `0` 代替未知值。
