# 内存泄漏报告固定验收数据

该数据集只用于服务端和前端验收，不来自真实设备，不包含真实 App Key、token 或用户标识。multipart 的最小参数见 `docs/fixtures/memory-leak-metadata.example.json`，报告文件见 `docs/fixtures/memory-leak-report.example.json`。

metadata 使用必填 UUID v4 `processId` 标识进程实例；`sessionId` 仍可选。持久队列、重试和重复检测必须保留 `anonymousDeviceId`、`processId` 与可选 `sessionId`，不能因补传或重启重新生成身份。

## 数据内容

- `classInfos`、`gcPaths`、`leakObjects` 和 `runningInfo` 四个 SDK 顶层字段均存在。
- 数字字段同时覆盖 JSON 整数和十进制数字字符串；`objectId` 保持字符串。
- 两条报告共享一个 `signature`，另一条报告包含第二个 signature；同一报告的重复 signature 使用相同路径内容。
- 一份报告 `gcPaths` 为空，用于验证报告可保存但不计入问题和趋势。
- `runningInfo.nowTime` 只作为原始字段保存，统计时间使用外层 `occurredAt`。

## 期望口径

将同一 signature 的三份 `(eventId, signature)` 报告写入后，问题列表应按事件计数、按匿名设备去重，分页前先计算总分母；`instanceCount`、`size` 和 `leakObjects` 数组位置不参与发生次数或泄漏字节计算。趋势查询应补齐无报告的 UTC 空桶。

真实 HPROF 不进入版本库；上传测试可以使用任意受限二进制占位文件验证 `attachmentStatus=stored`，服务端不应启动 HPROF 解析。
