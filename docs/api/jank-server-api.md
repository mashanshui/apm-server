# 卡顿监控服务端协议与查询 API

客户端上传接入请先阅读[Android 卡顿监控上传接入文档](../client-integration/jank-monitoring.md)；本文保留服务端协议、查询和统计口径说明。

本文记录已实现的卡顿服务端契约，供客户端和网页联调。本文不描述客户端采集实现；Vue 页面只消费这里的正式查询字段，不在浏览器自行聚合原始记录。

## 当前实现范围

- `POST /ingest/v1/stack-artifacts:parse` 接收 v3 `.rheajank.zip` 卡顿个例，服务端完成解析、脱敏、`jank-artifact-v2` 指纹、幂等存储和 ClickHouse 事实/详情写入。
- `POST /ingest/v1/batches` 继续接受 `frame_scene_summary` 和 `foreground_suspension_summary`；`eventType=jank` 返回事件级永久错误 `JANK_ARTIFACT_REQUIRED`。
- `/api/v1/apps/{appId}/janks/*` 已提供总览、趋势、Issue 排行、Issue 事件列表和事件详情。
- `/api/v1/apps/{appId}/jank-metrics/*` 已提供 FPS、设备日挂起率、统一时间桶趋势和白名单多维聚合查询；ClickHouse 路径在数据库侧完成 `FINAL` 去重和设备日二阶段聚合，内存路径用于本地闭环和固定数据集。

## 卡顿个例来源与质量字段

```json
{
  "expectedSampleCount": 460,
  "parsedSampleCount": 454,
  "missingSampleCount": 6
}
```

卡顿事件公共事实来自 v3 manifest，采样证据来自 processor 报告中 `processId` 对应唯一主线程的有效 `segments`。服务端根据消息区间计算 `expectedSampleCount`，根据主线程有效 segment 数计算 `parsedSampleCount`，再计算非负差值 `missingSampleCount`。客户端上传的 `attemptedSampleCount` 只满足 processor 契约，服务端不读取、不统计、不指纹化也不落库。

`messageDurationNs` 是消息开始与结束之差。响应中的 `estimatedDurationNs`、`estimatedUnattributedDurationNs`、`coveredDurationNs` 和 `uncoveredDurationNs` 来自 processor 的采样估算证据，不是方法精确耗时或 CPU self time；未覆盖区间不会用消息总时长补齐。

## 接收请求与可靠性

```http
POST /ingest/v1/stack-artifacts:parse
Content-Type: application/vnd.shanshui.rheajank+zip
X-App-Key: <从应用设置页获取的应用级 Key>

<原始 .rheajank.zip 字节>
```

压缩包入口默认限制为 64 MiB、同步解析并发 2；归一化详情仍受卡顿详情 512 KiB、卡顿采样 2000 个、堆栈字典 2000 项、单堆栈 128 帧、堆栈总帧 10000 和场景 128 字符等限制。完整上传契约见[卡顿压缩包解析与落库 API](stack-artifact-api.md)。

压缩包的 manifest `packageName` 必填且必须与 appKey 绑定包名完全一致；不一致返回不可重试的 `403 PACKAGE_NAME_MISMATCH`，不生成指纹或写入事实/详情。压缩包首次写入返回 `{"success":true,"status":"accepted"}`，重复请求返回 `duplicate`；二者都是 HTTP 200。上报 appKey 错误返回 401，鉴权数据库不可用返回带 `Retry-After: 30` 的 `503 APP_AUTH_UNAVAILABLE`，空请求返回 400，请求过大返回 413，旧媒体类型返回 415，产物或证据错误返回 422，解析繁忙或存储暂时不可用返回带 `Retry-After` 的 503。服务端以 `appId + eventId` 去重，同一事件重试不会增加事件、Issue 或受影响设备数。

## 卡顿查询 API

网页查询使用登录 Session 和应用成员关系授权，不接受客户端应用请求头作为授权事实。默认查询最近 24 小时，最大 31 天；`limit` 默认 50、最大 500；`timeoutMs` 默认 2000、最大 5000。筛选支持 `from`、`to`、`appVersion`、`channel`、`environment`、`osVersion`、`deviceModel`、`scene`、`algorithmVersion`、`fingerprint`、`limit`、`cursor` 和 `timeoutMs`。

```http
GET /api/v1/apps/{appId}/janks/overview
GET /api/v1/apps/{appId}/janks/trend?interval=hour|day
GET /api/v1/apps/{appId}/janks/issues
GET /api/v1/apps/{appId}/janks/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/janks/events/{eventId}
```

总览响应示例：

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "from": "2026-08-15T09:59:00Z",
  "to": "2026-08-16T00:02:00Z",
  "stats": {
    "jankEvents": 3,
    "affectedSessions": 3,
    "affectedDevices": 2,
    "groupableEvents": 3,
    "exactMessageDuration": {"p50Ms": 500.0, "p90Ms": 700.0, "p99Ms": 700.0},
    "status": "ok"
  },
  "dataSource": "memory"
}
```

Issue 结果同时返回 `exactMessageDuration` 和 `estimatedStackDuration` 两组分位数；前者来自消息精确耗时，后者来自采样估算。卡顿算法标识为 `jank-artifact-v2`，指纹继续使用服务端稳定关键路径，行号、地址、动态文本和采样次数不会拆分相同关键路径。

没有合法卡顿事件时，响应状态为 `no_data`，结果为空且分位数字段为 `null`，不能用零伪造。事件详情返回公共信息、卡顿载荷、采样片段、调用树、堆栈字典和采集质量计数；详情中的匿名设备 ID 已按应用盐哈希，服务端日志不输出完整载荷、堆栈或设备标识。

## FPS、设备日挂起率和多维查询

三个指标入口均要求已登录 Session，并且当前用户是目标应用成员；`X-App-Id`、`X-User-App-Ids` 等请求头不会改变授权结果。时间、版本、渠道、环境、Android 版本、设备型号、算法版本等筛选与上面的范围/超时限制相同。指标查询使用 `limit` 限制算法版本或维度分组数量，当前不接受 `cursor`。

```http
GET /api/v1/apps/{appId}/jank-metrics/fps
GET /api/v1/apps/{appId}/jank-metrics/suspension-rate
GET /api/v1/apps/{appId}/jank-metrics/trend?metric=fps&interval=hour
GET /api/v1/apps/{appId}/jank-metrics/dimensions?metric=fps&dimension=scene
```

可用参数：

| 参数 | FPS | 挂起率 | 说明 |
| --- | --- | --- | --- |
| `from`/`to` | ✓ | ✓ | ISO-8601 时间，左闭右开；默认最近 24 小时，最大 31 天 |
| `appVersion`、`channel`、`environment`、`osVersion`、`deviceModel` | ✓ | ✓ | 精确匹配稳定维度 |
| `scene` | ✓ | — | 仅场景 FPS；挂起率使用 `INVALID_FILTER` 拒绝 |
| `algorithmVersion` | ✓ | ✓ | 不兼容算法版本始终拆组返回 |
| `limit`、`timeoutMs` | ✓ | ✓ | 默认/最大值分别为 50/500 和 2000/5000 ms |
| `metric`、`dimension` | 仅 `/dimensions` | 仅 `/dimensions` | `metric` 为 `fps` 或 `suspension_rate`；`dimension` 为 `appVersion`、`channel`、`environment`、`osVersion`、`deviceModel`、`scene`、`algorithmVersion`，挂起率不支持 `scene` |
| `metric`、`interval` | 仅 `/trend` | 仅 `/trend` | `metric` 为 `fps` 或 `suspension_rate`；FPS 支持 `hour`/`day`，设备日挂起率只支持 UTC `day` |

FPS 响应按算法版本隔离，分位数按 FPS 从高到低计算，保证 `p50Fps >= p90Fps >= p99Fps`：

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "from": "2026-08-15T09:59:00Z",
  "to": "2026-08-16T00:02:00Z",
  "metrics": [{
    "algorithmVersion": "fps-v1",
    "totalRecords": 2,
    "validRecords": 2,
    "averageFps": 40.0,
    "p50Fps": 50.0,
    "p90Fps": 30.0,
    "p99Fps": 30.0,
    "status": "ok"
  }],
  "status": "ok",
  "dataSource": "memory"
}
```

设备日挂起率先按 `appId + anonymousDeviceId + UTC 日期 + appVersion/channel/environment/osVersion/deviceModel + algorithmVersion` 合并区间，再以 `累计挂起秒数 / 前台小时数` 计算每个设备日，最后在设备日记录上按从低到高计算分位数。响应字段 `totalRecords` 是参与合并的原始区间数，`validDeviceDayRecords` 是前台分母为正的设备日数：

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "from": "2026-08-15T09:59:00Z",
  "to": "2026-08-16T00:02:00Z",
  "metrics": [{
    "algorithmVersion": "suspension-v1",
    "totalRecords": 2,
    "validDeviceDayRecords": 1,
    "averageSecondsPerHour": 3.0,
    "p50SecondsPerHour": 3.0,
    "p90SecondsPerHour": 3.0,
    "p99SecondsPerHour": 3.0,
    "status": "ok"
  }],
  "status": "ok",
  "dataSource": "memory"
}
```

指标状态语义：`ok` 表示至少有一条有效记录；`no_data` 表示时间/筛选范围没有记录；FPS 全部记录字段无效时为 `no_valid_data`；挂起率没有有效前台分母时为 `denominator_insufficient`，不把比率伪造为零。多维接口的 `points` 元素使用 `dimensionValue` 和相同的指标字段，FPS 使用 `validRecords`，挂起率使用 `validDeviceDayRecords`。

统一趋势接口按 `bucketStart + algorithmVersion` 升序返回左闭右开的 UTC 时间桶。点内公共字段是 `bucketStart`、`bucketEnd`、`algorithmVersion`、`totalRecords`、`validRecords` 和 `status`；FPS 只填充 `averageFps`、`p50Fps`、`p90Fps`、`p99Fps`，挂起率只填充 `averageSecondsPerHour`、`p50SecondsPerHour`、`p90SecondsPerHour`、`p99SecondsPerHour`，另一组字段为 `null`。挂起率点中的 `validRecords` 表示有效设备日数，`totalRecords` 仍表示设备日合并前的原始区间数。

```json
{
  "appId": "550e8400-e29b-41d4-a716-446655440000",
  "from": "2026-08-15T00:00:00Z",
  "to": "2026-08-16T00:00:00Z",
  "metric": "fps",
  "interval": "hour",
  "points": [{
    "bucketStart": "2026-08-15T10:00:00Z",
    "bucketEnd": "2026-08-15T11:00:00Z",
    "algorithmVersion": "fps-v1",
    "totalRecords": 2,
    "validRecords": 2,
    "averageFps": 40.0,
    "p50Fps": 50.0,
    "p90Fps": 30.0,
    "p99Fps": 30.0,
    "averageSecondsPerHour": null,
    "p50SecondsPerHour": null,
    "p90SecondsPerHour": null,
    "p99SecondsPerHour": null,
    "status": "ok"
  }],
  "status": "ok",
  "dataSource": "clickhouse"
}
```

指标查询错误码：

| HTTP | code | 触发条件 | 是否重试 |
| --- | --- | --- | --- |
| 400 | `INVALID_TIME_RANGE`、`TIME_RANGE_TOO_LARGE` | 时间格式、顺序或范围无效 | 否 |
| 400 | `INVALID_LIMIT`、`INVALID_TIMEOUT`、`FILTER_TOO_LONG` | 查询资源限制超出配置 | 否 |
| 400 | `INVALID_METRIC`、`INVALID_DIMENSION`、`INVALID_FILTER` | 指标/维度不在白名单或挂起率使用 scene | 否 |
| 400 | `INVALID_INTERVAL` | 趋势粒度不是 `hour`/`day`，或挂起率请求 `hour` | 否 |
| 401 | `AUTH_REQUIRED` | 网页 Session 不存在或已失效 | 先登录 |
| 404 | `APP_NOT_FOUND` | 非应用成员或应用不可见 | 否，避免探测应用 |

指标接口不会返回分页游标；需要更多维度时应先缩小时间范围或增加稳定筛选，仍受 `limit` 上限保护。

## Vue 控制台消费边界

浅色 PC 控制台已提供卡顿指标分析、问题列表、Issue 事件列表和单事件详情。浏览器只通过应用路径、同源 Session Cookie 和统一 401 处理访问上述 API，不发送 Android 上报使用的 `X-App-Key`，也不使用应用请求头声明权限。

- 问题列表并行消费总览、趋势和 Issue 接口；三个区域独立处理加载、无数据、失败和重试。
- Issue 事件使用服务端游标追加并按 `eventId` 去重；追加失败不清除已加载结果。
- 事件详情将消息总耗时标记为精确证据，将时间片、调用树和火焰图标记为采样估算；未覆盖空洞不分配给方法，未归属估算时长不命名为 CPU 自耗时。
- 指标页按算法版本展示区间汇总和趋势。FPS 使用帧/秒并按从高到低解释分位数；挂起率使用秒/小时前台时长，固定为 UTC `day`，有效记录表示有效设备日。
- `no_data`、`no_valid_data`、`denominator_insufficient` 和合法数值零分别展示，不将不可计算的 `null` 转成零。

## 协议资源

- [卡顿压缩包解析与落库 API](stack-artifact-api.md)
- [卡顿压缩包 manifest v3](../client-integration/jank-artifact-manifest.md)
- [`frame-scene-summary-v2.schema.json`](../../src/main/resources/schema/frame-scene-summary-v2.schema.json)
- [`foreground-suspension-summary-v2.schema.json`](../../src/main/resources/schema/foreground-suspension-summary-v2.schema.json)
- [`jank-fixed-dataset.md`](../jank-fixed-dataset.md)
- [`jank-performance-baseline.md`](../jank-performance-baseline.md)

## 验证边界

`RheaStackAnalyzerIntegrationTests`、`JankArtifactReportMapperTests`、`StackArtifactParseServiceTests`、`StackArtifactApiIntegrationTests`、`JankApiIntegrationTests`、批量入口、查询和 ClickHouse 仓库测试覆盖服务端。仓库真实夹具固定验证主线程 454 条 segment、理论 460、缺失 6，并验证 `attemptedSampleCount=1` 不参与质量计算。前端 Vitest、类型检查和构建覆盖 API、状态、证据组件、指标口径和页面类型。自动化结果不等同于 Android 生产上传、真实 ClickHouse 容量、故障恢复或正式浏览器端到端验收。
