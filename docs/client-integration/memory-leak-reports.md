# 内存泄漏报告客户端接入

当前 Android SDK 在 KOOM 生成报告后，只把 `metadata` 与完整 `report` 文件写入持久队列并上传；HPROF 按 KOOM 回调约定在本地清理，不进入当前 SDK 上传接口。实现与设备边界见 [Android 内存泄漏专题](../../android/docs/knowledge-base/14-Activity内存泄漏检测与OOM触发.md)。

服务端协议另允许自定义调用方在同次 multipart 请求中携带可选 `hprof`，只受限保存、不解析；这不代表现有 SDK 已支持 HPROF 上传。完整 part、字段和限制由[内存泄漏报告 API](../api/memory-leak-reports-api.md#上报报告)维护。公共身份、App Key 和隐私规则见[客户端接入入口](README.md)。

## 事件构造

客户端必须生成并持久化稳定的 UUID `eventId`，使用 SDK 报告产生时的 Unix 毫秒 `occurredAt`，并填入与 App Key 绑定的 `packageName`。报告产生进程的 `processId` 必须是 UUID v4，并与 metadata 一起在队列中固定；`anonymousDeviceId` 应使用客户端现有匿名设备标识，不能填入可识别用户信息。完整字段和三个 multipart part 的约定见[内存泄漏报告 API](../api/memory-leak-reports-api.md)。

`metadata` JSON 只承载事件、版本、匿名设备和进程字段，不再嵌入 `report`。`report` 文件的根对象必须包含 `runningInfo`、`gcPaths`、`classInfos` 和 `leakObjects`。SDK 样例报告中的 `runningInfo.buildModel`、`currentPage`、`manufacture`、`sdkInt`、`dumpReason` 会分别成为设备型号、场景、厂商、SDK 整数版本和触发原因筛选字段；`nowTime`、`pss`、`rss`、`vss` 等原文仍保存在 report 中，不按没有单位的字符串推断泄漏字节。不要按 `gcPaths` 和 `leakObjects` 数组下标关联对象。

## 上传与本地队列

1. 先把 `metadata.json`（含 `processId`）和完整 `report` 文件写入持久队列，再发起 multipart 请求；自定义调用方若附带 HPROF，也须在首次发送前可靠保存。
2. 当前 SDK 的 `metadata`、`report` 两个 part 均为 `application/json`；自定义调用方附带 `hprof` 时使用 `application/octet-stream`。不能把 report 嵌入 metadata、拆散业务字段或把 HPROF Base64 放入 JSON。
3. 只有 HTTP 200、响应 `eventId` 与本任务相同、`status` 为 `accepted` 或 `duplicate` 才确认删除；正文损坏、标识不匹配或其他成功状态均保留任务进入有上限的重试。
4. 网络中断、HTTP 503、超时等不确定结果保留原 metadata、report 和原 `eventId` 重试；自定义调用方的原 HPROF 也必须保持一致。
5. 400、401、403、409、413、415 是内容或身份问题；修正内容或配置后再处理，不要无限重试同一个冲突事件。

服务端按 `appId + eventId` 去重，并比较规范 metadata+report 摘要和 HPROF SHA-256。相同事件不能事后补传或替换 HPROF；重试时必须保持 metadata、report 和 HPROF 内容一致。客户端不要在 URL、日志、截图或版本库中记录完整 App Key。

## 大小与隐私边界

客户端限制单份报告与整个队列大小，超限时隔离并记录原因；服务端不会截断后接受半份报告。实际允许大小取报告业务限制、Spring multipart 和网关限制中的最小值，具体配置见[服务端 API 的实际限制](../api/memory-leak-reports-api.md#上报报告)。自定义调用方上传 HPROF 前须单独核对部署容量，不能把业务上限当作网关已放行的大小。

报告中的 `anonymousDeviceId` 和原始 report 不经过事件 sanitizer 的哈希/模式脱敏；SDK 必须提供不含个人信息的匿名随机标识，并在构造 report 时完成必要脱敏。类名和引用链属于诊断证据，客户端日志避免打印完整原文。

异常报告与[运行期内存峰值](memory-metrics.md)使用独立事件和存储，不将报告对象的 `size` 当作 retained bytes。内存详情页和 HPROF 解析属于后续能力，当前页面只展示 SDK 报告聚合的问题、趋势和引用链。

服务端字段、响应和错误语义以[内存泄漏报告 API](../api/memory-leak-reports-api.md)为准。
