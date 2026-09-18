# 内存指标上传与查询 API

本文是 PSS、VSS、Java 堆首版服务端契约。客户端队列、采样实现和重试调度见[内存指标上传接入](../client-integration/memory-metrics.md)。

## 批量上传

复用 `POST /ingest/v1/batches`，不新增内存专用上传路由。请求可以是 JSON 或 gzip 压缩的 JSON：

```http
POST /ingest/v1/batches
Content-Type: application/json
Content-Encoding: gzip       # 未压缩时省略或使用 identity
X-App-Key: <应用上报 Key>
X-Schema-Version: 2
```

请求体外层固定为 `{"requestId":"批次请求 ID","events":[...]}`；下面的对象放在 `events` 数组中，`measurements` 和 `attributes` 可为空对象或省略：

```json
{
  "schemaVersion": 2,
  "eventId": "memory-20260908-0001",
  "eventType": "memory_sample",
  "occurredAt": 1788854400000,
  "sessionId": "session-01",
  "processId": "11111111-1111-4111-8111-111111111111",
  "anonymousDeviceId": "device-hash-input",
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

### 字段规则

| 字段 | 类型 | 规则 |
|---|---|---|
| `eventType` | string | 固定为 `memory_sample` |
| `occurredAt` | integer | 采样时刻 Unix 毫秒；查询按它过滤和分桶 |
| `eventId` | string | 同一事件重试必须保持不变；服务端按 `appId + eventId` 去重 |
| `processId` | string | 必填标准连字符 UUID v4；不能填 Android 数值 PID，重试和补传保持原值 |
| `packageName` | string | 必须与 `X-App-Key` 绑定的应用包名一致；不一致整批拒绝 |
| `pssBytes` | integer/null | 进程 PSS，0 至 `9007199254740991`；可缺失 |
| `vssBytes` | integer/null | 进程虚拟地址空间，0 至 `9007199254740991`；可缺失 |
| `javaHeapUsedBytes` | integer/null | Java 堆已使用字节，0 至 `9007199254740991`；可缺失 |
| `processName` | string | 必填，1 至 256 字符；作为结构化进程标识符精确匹配，包名和进程名中的数字会原样保留 |
| `foreground` | boolean | 必填，表示采样时前台状态 |
| `scene` | string/null | 可选；采样时当前应用的 Activity 名称，提供时非空且最长 128 字符，作为结构化名称精确匹配 |

三项指标至少提供一项非 null。零是有效测量；缺失或 null 不会被改成零，也不参与该指标的统计。一个事件只能携带 `memorySample`，不能同时携带 `crash`、`jank`、`frameSceneSummary` 或 `foregroundSuspensionSummary`。服务端不接受客户端平均值、分位数或跨进程求和字段。

`javaHeapUsedBytes` 的客户端口径是 `Runtime.totalMemory() - Runtime.freeMemory()`；它不表示最大堆或累计分配量。PSS/VSS 在客户端转换为字节后上传，服务端不接收 KB、MiB 字符串或单位字段。

批次响应沿用既有部分接受格式：合法事件计入 `accepted`，重复事件计入 `duplicate`，单条永久校验错误计入 `rejected` 并在 `errors` 中返回 `retryable=false`。批内包名错误、无效 App Key 和批次结构错误不会写入任何内存事件。

缺失、空值、数值 PID 或非 v4 的 `processId` 属于事件级永久错误；服务端不随机补值。存量 ClickHouse 记录没有进程列时，查询只返回缺失值语义，不影响已有统计分母和聚合维度。

## 概览

```http
GET /api/v1/apps/{appId}/memory-metrics/summary?from=2026-09-08T00:00:00Z&to=2026-09-09T00:00:00Z&processName=com.example.app&foreground=true
Cookie: SESSION=<登录会话>
```

查询需要登录 Session 和目标应用成员查看权限；`X-App-Key` 只用于上报，不能作为查询凭据。默认时间范围是最近 24 小时，最大 31 天；时间区间为 `[from,to)`。

响应示例：

```json
{
  "appId": "11111111-1111-1111-1111-111111111111",
  "from": "2026-09-08T00:00:00Z",
  "to": "2026-09-09T00:00:00Z",
  "pss": {
    "sampleCount": 4,
    "averageBytes": 150,
    "p50Bytes": 150,
    "p90Bytes": 270,
    "p95Bytes": 285,
    "p99Bytes": 297,
    "status": "ok"
  },
  "vss": { "sampleCount": 4, "averageBytes": 25, "p50Bytes": 25, "p90Bytes": 37, "p95Bytes": 38.5, "p99Bytes": 39.7, "status": "ok" },
  "javaHeap": { "sampleCount": 4, "averageBytes": 6.5, "p50Bytes": 6.5, "p90Bytes": 7.7, "p95Bytes": 7.85, "p99Bytes": 7.97, "status": "ok" },
  "status": "ok",
  "dataSource": "clickhouse"
}
```

`pss`、`vss`、`javaHeap` 各自统计筛选后、去重后的非缺失样本。平均值为算术平均；升序样本 `x[0..n-1]` 的分位数使用 `h=(n-1)*p` 线性插值，`p` 分别为 0.50、0.90、0.95、0.99。没有有效值时 `sampleCount=0`、统计值为 null、该指标 `status=no_data`。不同进程不会被相加成应用总内存。

## 趋势

```http
GET /api/v1/apps/{appId}/memory-metrics/trend?metric=pss&interval=hour&from=2026-09-08T00:30:00Z&to=2026-09-08T03:15:00Z
```

`metric` 只接受 `pss`、`vss`、`java_heap`；`interval` 只接受 `hour`、`day`，缺省分别为 `pss`、`hour`。响应一次返回全部分位数，页面切换 P50/P90/P95/P99 不需要重新上传数据：

```json
{
  "appId": "11111111-1111-1111-1111-111111111111",
  "from": "2026-09-08T00:30:00Z",
  "to": "2026-09-08T03:15:00Z",
  "metric": "pss",
  "interval": "hour",
  "points": [
    { "bucketStart": "2026-09-08T00:00:00Z", "bucketEnd": "2026-09-08T01:00:00Z", "sampleCount": 1, "averageBytes": 100, "p50Bytes": 100, "p90Bytes": 100, "p95Bytes": 100, "p99Bytes": 100, "status": "ok" },
    { "bucketStart": "2026-09-08T01:00:00Z", "bucketEnd": "2026-09-08T02:00:00Z", "sampleCount": 0, "averageBytes": null, "p50Bytes": null, "p90Bytes": null, "p95Bytes": null, "p99Bytes": null, "status": "no_data" }
  ],
  "status": "ok",
  "dataSource": "memory"
}
```

趋势按采样时间的 UTC 小时或 UTC 日边界分桶，返回与查询区间相交的桶；无样本桶补 `sampleCount=0` 和 null，前端折线断开。概览不由趋势桶再次聚合。

概览和趋势共同支持 `from`、`to`、`appVersion`、`osVersion`、`deviceModel`、`processName`、`scene`、`foreground`。文本为精确匹配；`foreground` 只能是 `true` 或 `false`。服务端拒绝未知参数、非法 metric/interval、`from>=to`、超过 31 天、非法布尔值和超出超时上限的请求。内存没有多维下钻接口，也没有 32/64 位、FD 触顶率、对比列表或导出参数。

## 错误和重试

| HTTP/错误 | 是否重试 | 说明 |
|---|---|---|
| 200，事件级 `errors` | 否（`retryable=false`） | 单条字段、载荷或 Schema 错误；只移除对应永久失败事件 |
| 400 | 否 | 批次结构、查询参数或 Schema 错误 |
| 401/403 | 否 | App Key 无效、未登录或无应用成员权限 |
| 413 | 否 | 请求或解压内容超过上限 |
| 503 `EVENT_STORE_UNAVAILABLE` | 是 | 存储不可用，响应带 `Retry-After: 30`；不能当作 accepted 或 no_data |
| 网络中断、408、429 或其他 5xx | 是 | 保留原批次和原 `eventId`，按退避重试 |

服务端当前使用同步写入；`dataSource` 仅标识内存或 ClickHouse 适配器。内存模式重启会丢失数据，ClickHouse 模式依赖迁移脚本和 `FINAL` 查询获得最终去重视图。详见[内存固定数据集](../memory-fixed-dataset.md)。
