# 内存指标上传与查询 API

本文维护当前服务端运行期观测峰值契约；Performance配套实现与用户指定Pixel_6模拟器联合验收已通过，范围见[统一验收](../validation/memory-runtime-peaks/README.md)。接入步骤见[客户端接入](../client-integration/memory-metrics.md)。旧 `memory_sample` 已停止接收，不转换旧样本或混入新统计；内存泄漏报告保持独立。

## 批量上传

复用 `POST /ingest/v1/batches`，`X-App-Key` 和 `X-Schema-Version: 2`，Content-Type 为 application/json，支持原有 gzip。外层 `requestId/events` 不变，事件格式如下（固定时间用于测试，实际请求应使用原运行真实时间）：

```json
{
  "schemaVersion": 2,
  "eventId": "22222222-2222-4222-8222-222222222222",
  "eventType": "memory_peak_summary",
  "occurredAt": 1794013200000,
  "sessionId": "11111111-1111-4111-8111-111111111111",
  "processId": "11111111-1111-4111-8111-111111111111",
  "anonymousDeviceId": "33333333-3333-4333-8333-333333333333",
  "packageName": "com.shanshui.performance",
  "appVersion": "1.0-test", "versionCode": 1, "buildId": "memory-peak-test-build",
  "environment": "test", "channel": "local", "osVersion": "13", "deviceModel": "test-device",
  "memoryPeakSummary": {
    "processName": "com.shanshui.performance",
    "collectionStartedAt": 1794009600000,
    "lastSampledAt": 1794013200000,
    "pss": {"bytes": 200, "occurredAt": 1794009610000, "foreground": true, "scene": "com.shanshui.performance.MainActivity"},
    "vss": {"bytes": 500, "occurredAt": 1794009620000, "foreground": false},
    "javaHeap": {"bytes": 80, "occurredAt": 1794009630000, "foreground": true, "scene": "com.shanshui.performance.MainActivity"}
  }
}
```

公共身份、版本、buildId、环境与渠道均来自原运行；processId 必须为标准连字符 UUID v4，不从上传进程推断。packageName 与 Key 不匹配仍整批拒绝。occurredAt 必须等于 lastSampledAt，二者都不是上传或退出时间。

## 峰值规则

- processName 必填，长度1..256；collectionStartedAt/lastSampledAt 为非负 Unix 毫秒，前者不晚于后者。
- pss/vss/javaHeap 为独立峰值对象，至少一项非null。每项必填bytes、occurredAt、foreground，可选scene；不能借用另一项的上下文。
- bytes 为0..9007199254740991整数。PSS/VSS转换为字节；Java堆为totalMemory-freeMemory。真实零保留，读取失败不补零。
- 单项occurredAt必须位于采集区间内。scene为当时Activity名称，提供时非空且最长128字符。
- 字段/类型边界严格，未知字段或类型错误沿用批次400；合法结构的业务校验错误沿用事件级永久拒绝；互斥专属载荷不得混用。
- 历史窗口独立使用 `apm.ingest.max-memory-peak-past-days`，默认90天，接收时要求 `now-90d < lastSampledAt`；恰好90天/更早为EVENT_TIME_TOO_OLD。其他事件窗口不变。运行起点/单项峰值可早于窗口。
- 未来容差沿用公共配置，超出为EVENT_TIME_IN_FUTURE。峰值时间不能超过截止。

## 确认和重试

沿用批次HTTP200及accepted/rejected/duplicate/errors语义，eventId在封存及重试中不变。按appId/eventId去重，持久化完成后才能accepted；重复事件返回duplicate，不改写原记录。网络异常、408/429/5xx、存储503及可重试错误保留原事件退避；永久错误隔离，不能用修改ID/时间重传规避。

## 概览和趋势

保留 `/api/v1/apps/{appId}/memory-metrics/summary` 和 `/trend`，要求Session及应用查看权限；App Key不能查询。Agent沿用其独立Token授权。

默认最近24小时、最长31天，UTC `[from,to)` 按原lastSampledAt归属；一轮只落入一个截止小时/日桶，跨日运行不展开，上传时间不移动桶。metric仅pss/vss/java_heap，interval仅hour/day，默认pss/hour。筛选包含from/to、appVersion、osVersion、deviceModel、processName、scene、foreground及现有limit/timeoutMs资源参数；未知参数拒绝。

版本/设备/进程使用原元数据，scene/foreground分别匹配每项峰值。summary三个指标的有效运行数可能不同。各项响应使用 `runCount`（无sampleCount别名）、averageBytes、p50Bytes、p90Bytes、p95Bytes、p99Bytes、status；顶层保留appId/from/to/status/dataSource。趋势点保留bucketStart/bucketEnd及同样统计字段。

每轮每项只贡献一个峰值，算术平均与线性插值 `h=(n-1)*p` 不按设备/时长加权。四轮0/100/200/300得到runCount=4、平均/P50=150、P90=270、P95=285、P99=297。空项/空桶为runCount=0、统计null、status=no_data，真实0不变。前端按1048576换算MiB，不重算概览。

## 存储与边界

新表 `apm_memory_peak_summary` 使用009迁移，原表保留但不参与新统计；默认TTL为截止时间后90天。持久查询在数据库完成去重、筛选和聚合，并使用平台扫描/内存/时间/响应预算，资源失败明确报错，不伪装no_data。内存验证模式重启丢失数据；十万目标运行混合集及Pixel_6/真实HTTP联合验证见[验收入口](../validation/memory-runtime-peaks/README.md)，不代表生产容量或物理真机覆盖。
