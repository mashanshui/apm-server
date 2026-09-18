# Android 内存指标上传接入

本文说明客户端如何构造并上传 PSS、VSS、Java 堆采样。服务端字段和响应的唯一契约是[内存指标上传与查询 API](../api/memory-metrics-api.md)；本仓库不实现 Android 定时采集 SDK。

## 采样值和口径

每次采样表示一个进程在一个时刻的原始值：

- `pssBytes`：进程 PSS，转换为字节后上传。
- `vssBytes`：进程虚拟地址空间大小，转换为字节后上传。
- `javaHeapUsedBytes`：使用 `Runtime.totalMemory() - Runtime.freeMemory()` 计算的 Java 堆已使用字节，不是最大堆或累计分配量。
- `processName`：被采样进程名，必填；多进程应用每个进程分别发送事件。
- `processId`：被采样进程实例的 UUID v4，必填；不能使用 Android 数值 PID，同一事件重试必须保持不变。
- `foreground`：采样瞬间的前后台状态，必填布尔值。
- `scene`：可选，表示采样时当前应用的 Activity 名称；无法确定时省略或传 null。

`processName` 和 `scene` 是用于查询精确筛选的结构化名称。客户端应上传系统返回的完整包名、进程名和 Activity 名称；名称中的数字、冒号和类名分隔符不会按手机号等通用文本规则替换。

三个指标至少上传一项。未能采集的指标传 null 或省略，不能用 0 代替；0 只表示真实的零值。客户端不要上传平均值、P50/P90/P95/P99 或跨进程求和结果，服务端会按原始样本统计。

## 事件格式

内存事件与 Crash/卡顿共用 `/ingest/v1/batches` 的 JSON 信封。请求体外层使用 `{"requestId":"批次请求 ID","events":[...]}`，将下面的事件对象放进 `events` 数组，只把事件类型设为 `memory_sample` 并填入 `memorySample`：

```json
{
  "schemaVersion": 2,
  "eventId": "01J7MEMORY0000000000000001",
  "eventType": "memory_sample",
  "occurredAt": 1788854400000,
  "sessionId": "session-01",
  "processId": "11111111-1111-4111-8111-111111111111",
  "anonymousDeviceId": "device-local-id",
  "packageName": "com.example.app",
  "appVersion": "3.2.0",
  "versionCode": 320,
  "buildId": "build-320",
  "environment": "production",
  "channel": "official",
  "osVersion": "16",
  "deviceModel": "Pixel-8",
  "networkType": "wifi",
  "measurements": {},
  "attributes": {},
  "memorySample": {
    "pssBytes": 73400320,
    "vssBytes": 157286400,
    "javaHeapUsedBytes": 25165824,
    "processName": "com.example.app",
    "foreground": true,
    "scene": "com.example.HomeActivity"
  }
}
```

`eventId`、`anonymousDeviceId`、`sessionId` 和 `processId` 必须在本地队列中持久保存。请求头使用创建应用后由 `OWNER`/`ADMIN` 查看得到的 `X-App-Key`，并携带 `X-Schema-Version: 2`。`packageName` 必须与 Key 绑定的包名完全一致；一个批次可以混合多种合法事件，但所有事件的包名必须一致。

## 持久化、批量和重试

推荐按以下顺序实现客户端上传器：

1. 采样完成后生成完整事件和稳定 `eventId`，先以原子方式写入本地持久队列，再通知上传任务。
2. 上传任务从队列取多个事件组成 JSON 批次；请求体较大时使用 gzip，仍要遵守服务端压缩前 1 MiB、解压后 4 MiB 和单事件 256 KiB 上限。
3. 收到 HTTP 200 后，按响应中的 `accepted`、`duplicate` 和 `errors` 逐条处理：accepted/duplicate 从队列删除，`retryable=false` 的永久错误记录原因并删除或进入人工诊断队列。
4. 网络中断、408、429、5xx 或 503 `EVENT_STORE_UNAVAILABLE` 保留原批次和原 eventId，按照 `Retry-After`（服务端存储失败默认 30 秒）或指数退避加随机抖动重试。请求超时后无法确定服务端是否写入时，也必须复用原 eventId，不能新建事件。
5. 达到最大重试次数时保留可诊断的失败记录并限制队列占用；不要把失败事件伪装为成功或无数据。

服务端按 `appId + eventId` 去重，因此同一采样的重试必须保持相同 ID 和载荷。内存事件与 Crash、卡顿事件共享 Key、包名校验、批次大小、gzip 和部分接受协议，不需要另建上传连接。

## 采集频率和生命周期

采样频率、前后台切换触发时机、电量/网络约束和本地队列容量由 Android SDK 产品策略决定，不在本仓库固定。建议在前台或关键场景采样，并在后台采用更低频率；任何策略都应记录采样时刻 `occurredAt`。页面统计按样本计数，不将不同频率解释成设备平均或时长平均。

## 联调检查

服务端固定数据集见[内存指标固定数据集](../memory-fixed-dataset.md)。联调至少检查：合法 0 值、部分指标缺失、重复 eventId、批内错误、错误包名和 503 保留队列。当前仓库已用内存适配器和前端自动化测试验证协议/统计/状态；Android 真机采集、正式 SDK 队列实现、真实 ClickHouse 对照和生产容量仍需单独验收。
