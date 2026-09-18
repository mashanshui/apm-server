# 内存泄漏报告客户端接入

客户端在 SDK 判定内存异常时，把解析后的 `hprof.json` 作为 `report` 文件上传；原始 HPROF 可作为同一次 multipart 请求的 `hprof` 文件，也可以省略。服务端解析并保存 report，首版只保存 HPROF，不解析详情。metadata 必须携带 UUID v4 `processId`；`sessionId` 继续可选。

## 事件构造

客户端必须生成并持久化稳定的 UUID `eventId`，使用 SDK 报告产生时的 Unix 毫秒 `occurredAt`，并填入与 App Key 绑定的 `packageName`。报告产生进程的 `processId` 必须是 UUID v4，并与 metadata 一起在队列中固定；`anonymousDeviceId` 应使用客户端现有匿名设备标识，不能填入可识别用户信息。完整字段和三个 multipart part 的约定见[内存泄漏报告 API](../api/memory-leak-reports-api.md)。

`metadata` JSON 只承载事件、版本、匿名设备和进程字段，不再嵌入 `report`。`report` 文件的根对象必须包含 `runningInfo`、`gcPaths`、`classInfos` 和 `leakObjects`。SDK 样例报告中的 `runningInfo.buildModel`、`currentPage`、`manufacture`、`sdkInt`、`dumpReason` 会分别成为设备型号、场景、厂商、SDK 整数版本和触发原因筛选字段；`nowTime`、`pss`、`rss`、`vss` 等原文仍保存在 report 中，不按没有单位的字符串推断泄漏字节。不要按 `gcPaths` 和 `leakObjects` 数组下标关联对象。

## 上传与本地队列

1. 先把 `metadata.json`（含 `processId`）、完整 `report` 文件和可选 HPROF 的本地任务写入持久队列，再发起 multipart 请求。
2. 请求必须使用 `multipart/form-data`：`metadata` part 为 `application/json`，`report` part 为 `application/json`，可选 `hprof` part 为二进制；不能把 report 嵌入 JSON、把业务字段拆到多个表单字段或把 HPROF Base64 放入 JSON。
3. HTTP 200 的 `accepted` 和 `duplicate` 都可以确认删除本地任务。
4. 网络中断、HTTP 503、超时等不确定结果保留原 metadata、report、原 HPROF 和原 `eventId` 重试。
5. 400、401、403、409、413、415 是内容或身份问题；修正内容或配置后再处理，不要无限重试同一个冲突事件。

服务端按 `appId + eventId` 去重，并比较规范 metadata+report 摘要和 HPROF SHA-256。相同事件不能事后补传或替换 HPROF；重试时必须保持 metadata、report 和 HPROF 内容一致。客户端不要在 URL、日志、截图或版本库中记录完整 App Key。

## 大小与隐私边界

默认 metadata 上限 2 MiB、report 文件上限 2 MiB、HPROF 256 MiB、总请求 260 MiB。客户端应在本地限制队列大小，并在超限前给出诊断记录；服务端不会截断后接受半份报告。报告中的类名和引用链属于诊断证据，客户端日志仍应避免打印完整原文。

服务端不会把异常报告写入普通 `memory_sample` 指标，也不会将 `size` 当作 retained bytes。内存详情页和 HPROF 解析属于后续能力，当前页面只展示 SDK 报告聚合的问题、趋势和引用链。

服务端字段、响应和错误语义以[内存泄漏报告 API](../api/memory-leak-reports-api.md)为准。
