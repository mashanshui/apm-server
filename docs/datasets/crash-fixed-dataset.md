# Crash 固定验收数据集

测试夹具位于 `backend/src/test/resources/fixtures/crash-dataset.json`，故意包含 13 条上报记录：8 条 `app_start`、4 个唯一 Crash `eventId`，以及 `crash-202` 的一次重复上报。

身份字段固定为合法 UUID v4：设备身份在安装范围内保持不变，启动身份按 `sessionId` 区分，主进程可以令 `processId=sessionId`；跨启动补传和重复上传必须保留原始 `processId`，不使用数值 PID 替换。

## 期望结果

| 层级 | 启动会话 | 唯一 Crash 事件 | 崩溃会话 | 受影响设备 | 每千会话崩溃率 | 无崩溃会话率 |
|---|---:|---:|---:|---:|---:|---:|
| 全量 | 8 | 4 | 4 | 4 | 500 | 0.5 |
| 3.2.0 | 5 | 2 | 2 | 2 | 400 | 0.6 |
| 3.3.0 | 3 | 2 | 2 | 2 | 666.6667 | 0.3333333 |

按问题指纹分组时：

- `java.lang.IllegalStateException` + `PaymentActivity.submit` 为同一指纹，共 3 个唯一事件、3 个会话、3 台设备；行号和用户数字变化不能拆组。
- `java.lang.NullPointerException` + `OrderRepository.load` 为另一指纹，共 1 个唯一事件、1 个会话、1 台设备。
- `crash-202` 的重复记录不能使任一统计值增加。

查询无 `app_start` 时，`crashRatePer1000Sessions` 和 `crashFreeSessionRate` 必须为 `null`，状态为 `denominator_insufficient`；不能以 `0` 代替未知值。
