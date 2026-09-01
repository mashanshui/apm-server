# Android 卡顿压缩包 manifest v3 契约

> 状态：服务端当前契约。服务端严格要求 v3 `.rheajank.zip`；apm-server 已固定并验证 `rhea-trace-processor:1.0.1` 与一份真实 v3 fixture，生产制品仓库、Android 真机、持久重试和容量仍需单独验收。

本文定义一次主线程卡顿对应的 `.rheajank.zip` 及其最小 `manifest.json`。服务端仅依赖压缩包完成卡顿事件校验、解析、去重、指纹和落库，客户端不再同时构造 `/ingest/v1/batches` 的结构化 `jank` JSON。

## 1. 最小化原则

- manifest 只保存服务端无法从 `sampling.bin`、请求鉴权或其他 manifest 字段可靠推导的数据。
- 可通过字段运算、二进制解码或固定协议版本得到的数据，由服务端计算，不要求客户端重复上传。
- 一个 `.rheajank.zip` 只表示一个逻辑卡顿事件。
- 客户端在开始导出前生成一次稳定 `eventId`；文件重试、进程重启后的续传都必须复用该值。
- ZIP 根目录固定只包含 `manifest.json`、`sampling.bin` 和 `sampling-mapping.bin`，不包含目录、临时文件或其他条目。
- `manifest.json` 使用 UTF-8、无 BOM 的 JSON 对象；所有纳秒和毫秒整数均使用 Kotlin/Java `Long`，不得经过 `Double` 转换。
- 建议最终文件名为 `<eventId>.rheajank.zip`，但文件名不参与服务端身份判断，`manifest.eventId` 才是事实来源。
- `appId` 不写入 manifest；服务端始终通过请求头 `X-App-Key` 认证并确定应用。
- 整个 ZIP 的 SHA-256 由服务端接收后计算，不能写入 ZIP 内部形成自引用；manifest 只保存两个二进制条目的大小和 SHA-256。

## 2. 最小完整示例

```json
{
  "schemaVersion": 3,
  "artifactType": "RHEA_JANK",
  "eventId": "jank-01J6R2Z81Q9M8M1T8N7JY4P6K3",
  "occurredAt": 1787992200123,
  "sessionId": "session-20260829-001",
  "anonymousDeviceId": "device-sha256-7f83b1657ff1",
  "packageName": "com.example.app",
  "appVersion": "3.2.0",
  "versionCode": 320,
  "buildId": "android-release-320",
  "environment": "production",
  "channel": "official",
  "osVersion": "15",
  "deviceModel": "Pixel 9",
  "scene": "home_feed",
  "messageStartNs": 1643221000000000,
  "messageEndNs": 1643221500000000,
  "thresholdNs": 200000000,
  "minSampleIntervalNs": 5000000,
  "attemptedSampleCount": 48,
  "processId": 17137,
  "files": {
    "sampling": {
      "size": 34587,
      "sha256": "bde47b661bcddf6eefa3db011eac0d323274fcdc00d8e3182bde47b661bcddf6"
    },
    "sampling-mapping": {
      "size": 2468,
      "sha256": "ed26275a2d63ef84b43185fefa668b0b97423027d0b810df1617c86d05faef89"
    }
  }
}
```

示例哈希只用于展示格式，不对应示例二进制文件，不能直接复制到真实产物。

## 3. 必填字段

### 3.1 协议和事件身份

| 字段 | 类型/单位 | 生成与校验规则 | 不能由服务端推导的原因 |
|---|---|---|---|
| `schemaVersion` | integer | 固定为 `3` | 用于选择本契约和服务端解析器 |
| `artifactType` | string | 固定为 `RHEA_JANK` | 用于在读取二进制前拒绝错误产物 |
| `eventId` | string | 1～128 字符；一次逻辑卡顿只生成一次，建议使用 UUID 或 ULID | 服务端自行生成会导致超时重试无法识别同一事件 |
| `occurredAt` | integer，毫秒 | Unix Epoch 毫秒，取主线程消息结束或卡顿确认时的墙上时间 | Sampling 只有单调时钟，无法恢复墙上发生时间 |
| `sessionId` | string | 1～128 字符；与客户端会话口径一致 | 用于受影响会话统计 |
| `anonymousDeviceId` | string | 1～256 字符；只能使用不可逆匿名标识 | 用于受影响设备统计，服务端不能从请求连接推断设备身份 |

`eventId` 和服务端认证得到的 `appId` 共同构成幂等键。客户端不得在 manifest 中自行声明 `appId`，也不得因上传重试重新生成 `eventId`、`occurredAt` 或其他事件字段。

### 3.2 应用和查询维度

| 字段 | 类型 | 生成与校验规则 | 用途 |
|---|---|---|---|
| `packageName` | string | 必填，1～255 字符；必须等于 appKey 绑定的 Android application ID | appKey 与包名绑定校验 |
| `appVersion` | string | 1～128 字符，例如 `3.2.0` | 版本筛选和回归比较 |
| `versionCode` | integer | 非负整数 | 构建版本排序和定位 |
| `buildId` | string | 1～256 字符；稳定对应一个可发布构建 | 构建定位及默认 mapping 选择 |
| `environment` | string | 1～64 字符，例如 `production`、`staging` | 环境筛选 |
| `channel` | string | 1～128 字符，例如 `official`、`play` | 渠道筛选 |
| `osVersion` | string | 1～64 字符；Android 系统版本或双方约定的 API Level 字符串 | 系统版本维度 |
| `deviceModel` | string | 1～256 字符；使用脱敏后的设备型号 | 设备型号维度 |
| `scene` | string | 1～128 字符；使用稳定、低基数场景名，不包含页面参数、用户 ID 或动态文本 | 卡顿场景筛选和 Issue 指纹上下文 |

这些字段不能从堆栈内容可靠识别。客户端必须在卡顿发生时冻结其值，不能在延迟上传或重试时读取可能已经变化的新值。

### 3.3 精确消息边界和采样输入

| 字段 | 类型/单位 | 生成与校验规则 | 用途 |
|---|---|---|---|
| `messageStartNs` | integer，纳秒 | 主线程消息开始的 `elapsedRealtimeNanos` | 从产物中裁剪本次卡顿的证据起点 |
| `messageEndNs` | integer，纳秒 | 同一消息结束的 `elapsedRealtimeNanos`，必须大于 `messageStartNs` | 从产物中裁剪证据终点，并计算精确消息耗时 |
| `thresholdNs` | integer，纳秒 | 客户端本次实际采用的卡顿阈值，必须为正且不大于消息耗时 | 记录客户端为何判定为卡顿；固定阈值部署可按第 8 节省略 |
| `minSampleIntervalNs` | integer，纳秒 | Native 采集器对本次产物实际采用的最小采样请求间隔，必须为正 | 服务端重建点采样有限覆盖范围 |
| `attemptedSampleCount` | integer | 非负；processor v3 契约要求存在 | 仅供 processor 读取和校验；服务端业务映射、统计、指纹和存储全部忽略 |
| `processId` | integer | Android 进程 ID，必须为正 | 当前 Sampling 数据使用主进程 tid 识别主线程 |

`occurredAt` 使用墙上时钟；`messageStartNs`、`messageEndNs` 和 Sampling 记录使用单调时钟。客户端和服务端都不得用墙上时间与单调时间直接相减，也不得把跨设备的单调时钟绝对值放到同一时间轴比较。

### 3.4 文件校验

| 字段 | 规则 |
|---|---|
| `files.sampling.size` | `sampling.bin` 的真实字节数，非负整数 |
| `files.sampling.sha256` | `sampling.bin` 的 SHA-256，小写 64 位十六进制字符串 |
| `files.sampling-mapping.size` | `sampling-mapping.bin` 的真实字节数，非负整数 |
| `files.sampling-mapping.sha256` | `sampling-mapping.bin` 的 SHA-256，小写 64 位十六进制字符串 |

文件大小可以由服务端重新读取，但 manifest 中的期望值用于发现产物生成中断、错误文件配对和内容损坏。SHA-256 用于把 manifest 声明与实际二进制绑定，不是客户端身份签名。

## 4. 服务端派生字段

以下字段不写入 manifest，由服务端统一计算：

| 派生字段 | 计算方式 |
|---|---|
| `messageDurationNs` | `messageEndNs - messageStartNs` |
| `samplingIntervalNs` | 使用 `minSampleIntervalNs` |
| `expectedSampleCount` | 按服务端 `jank-artifact-v2` 算法，根据消息耗时和采样间隔计算 |
| `parsedSampleCount` | `processId` 对应唯一目标主线程中，消息窗口内堆栈非空的有效 `segments` 数量 |
| `missingSampleCount` | `max(0, expectedSampleCount - parsedSampleCount)` |
| `recordCount` | 解码 `sampling.bin` 后统计，不信任客户端重复声明 |
| mapping 选择 | 服务端使用认证应用与 `buildId` 查找，不新增 manifest 字段 |

`parsedSampleCount` 不使用 `attemptedSampleCount`、顶层 `recordCount` 或其他线程记录，也不只统计 `kCustom`；processor 保留的所有有效主线程 segment 都计数。`missingSampleCount` 不是客户端确认的丢弃次数。

## 5. 由协议版本固定的解析参数

客户端不再重复写入以下常量；服务端看到 `schemaVersion=3` 和 `artifactType=RHEA_JANK` 后必须按固定值解析：

| 参数 | v3 固定值 |
|---|---|
| Sampling 格式 | `5` |
| 字节序 | `little-endian` |
| Sampling 时钟 | `ELAPSED_REALTIME_NANOS` |
| 选择类型 | `RANGE` |

以后需要改变任一固定值时必须升级 manifest `schemaVersion`，不得在 v3 中静默改变二进制解释方式。

## 6. 不进入最小 manifest 的诊断字段

以下字段不影响当前卡顿入库、Issue 和查询，客户端不需要上传：

- `requestedStartNs`、`requestedEndNs`
- `availableStartNs`、`availableEndNs`
- `actualStartNs`、`actualEndNs`
- `snapshotTimeNs`
- `bufferSizeBytes`、`bufferCapacityRecords`
- `foregroundOnly`
- `enableJniHook`、`enableObjectAllocation`、`enableWakeup`、`enableRusage`
- `appName`
- `androidApi`、`abi`
- `networkType`
- `measurements`、`attributes`

`overwrittenRecordCount`、`droppedByRateLimit` 和 `abi` 可在将来的可观测性版本中作为可选诊断字段引入，但不得成为卡顿事件成功上传的前置条件。需要增加可选字段时仍应更新契约和测试，不能依赖服务端静默忽略未知字段。

## 7. Sampling mapping 和 ProGuard/R8 mapping

`sampling-mapping.bin` 保存运行时方法指针、符号和线程名称映射，不是 ProGuard/R8 mapping。

最小协议默认约定 ProGuard/R8 mapping 的业务标识等于 `buildId`：

```text
<mapping-root>/<appId>/<buildId>.txt
```

`appId` 由应用 Key 认证得到，客户端不能提供 mapping 标识或路径。对应文件不存在时，服务端继续保存未解混淆证据。

## 8. 可按部署约束进一步省略的字段

只有形成并验证以下服务端事实来源后，才能继续减少客户端字段：

| 可省略字段 | 前置条件 |
|---|---|
| `thresholdNs` | v3 在所有客户端和场景中使用不可配置的固定阈值，服务端按协议版本得到同一值 |
| `appVersion`、`versionCode`、`channel`、`environment` | 服务端存在经过发布流程维护的 `buildId` 注册表，且能稳定反查这些字段 |

当前 `packageName` 不可省略：服务端使用它与 appKey 绑定的包名做逐字符匹配，不会用绑定值静默补齐 manifest。系统生成的 `appId` 不写入 manifest；`apm-server` 尚无完整构建和 mapping 注册中心，因此客户端仍应按第 3 节提供其余字段，不能只上传 `buildId` 后依赖不存在的服务端元数据。

## 9. 客户端生成顺序

1. 主线程消息开始时记录 `messageStartNs`，消息结束时记录 `messageEndNs` 和墙上时间 `occurredAt`。
2. 确认达到本次 `thresholdNs` 后立即生成稳定 `eventId`，冻结公共事件字段、场景和 `attemptedSampleCount`。
3. 导出 `sampling.bin` 和 `sampling-mapping.bin` 到同一临时目录；二进制中可以保留卡顿前后数据，服务端以后按消息边界裁剪。
4. 读取两个二进制文件的最终大小并计算 SHA-256；文件仍在写入时禁止计算或打包。
5. 最后生成最小 `manifest.json`，再次检查消息边界、阈值、尝试次数和文件校验信息。
6. 把三个固定条目写入临时 ZIP，关闭并同步文件后，原子重命名为 `<eventId>.rheajank.zip`。
7. 队列至少保存最终文件路径、`eventId`、创建时间、重试次数和下次重试时间；不得在重试时重新导出或修改 ZIP。
8. 使用新的卡顿压缩包媒体类型上传；只有收到 `accepted` 或 `duplicate` 才删除本地文件。

## 10. 客户端验收清单

- 解压后只有三个固定条目，且文件名大小写完全一致。
- `manifest.json` 能以 UTF-8 解析，根节点为对象，所有必填字段无 `null`。
- 同一逻辑卡顿重复读取队列时，ZIP 字节、`eventId` 和所有事件字段保持不变。
- `messageEndNs > messageStartNs`，且两者都来自 `elapsedRealtimeNanos`。
- `thresholdNs > 0` 且 `messageEndNs - messageStartNs >= thresholdNs`。
- `minSampleIntervalNs > 0`，`attemptedSampleCount >= 0`。
- 两个二进制文件的声明大小和 SHA-256 与实际内容一致。
- manifest、文件名和客户端日志不包含应用 Key、账号、手机号、原始设备 ID、业务正文或绝对文件路径。
- 至少准备一个正常产物、一个采样存在明显空洞的产物和一个 `attemptedSampleCount` 大于服务端可解析成功轮次的产物，供服务端后续回归。

## 11. 当前服务端接收边界

- 请求路径仍使用 `POST /ingest/v1/stack-artifacts:parse`。
- 媒体类型切换为 `application/vnd.shanshui.rheajank+zip`。
- mapping 默认按 `buildId` 选择，不再要求客户端重复发送 `X-Mapping-Id`。
- 成功响应只确认 `accepted` 或 `duplicate`，不返回完整 `RHEA_STACK_REPORT`。
- 服务端把解析结果写入卡顿原始、事实和详情存储；客户端不再为同一卡顿额外上传结构化 `jank` JSON。
- `/ingest/v1/batches` 仍用于 Crash、启动、场景 FPS 和前台挂起汇总。

完整请求、成功响应、错误码和重试规则以[卡顿压缩包解析与落库 API](../api/stack-artifact-api.md)为准。
