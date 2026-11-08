# 内存运行峰值固定数据集

共享事件文件为[峰值JSON](../fixtures/memory-runtime-peaks.json)，自动化根据测试基准平移时间并派生各轮数值。

正式契约见[内存API](../api/memory-metrics-api.md)，客户端接入见[上传接入](../client-integration/memory-metrics.md)。本固定集用于运行峰值，历史普通memory_sample不参与新统计。

| 运行 | PSS峰值 | VSS峰值 | Java堆峰值 |
|---|---:|---:|---:|
| R0 | 0 | 10 | null |
| R1 | 100 | 20 | 5 |
| R2 | 200 | null | 6 |
| R3 | 300 | 40 | 7 |
| R4 | null | 50 | 8 |

单位均为字节，PSS runCount=4，平均/P50=150、P90=270、P95=285、P99=297。重复R1不能增加运行数。缺失不补零，空桶runCount=0且统计null。

同轮采样(100,500,20)、(200,400,80)、(150,450,40)应分别累计(200,500,80)，保留首次达到各峰值的时间和上下文。精确累计属于客户端自动化目标，服务端不从这些样本重算峰值。

场景固定集：同轮PSS在前台A、VSS在后台、Java堆在前台B，查询scene=A且foreground=true只计入PSS。运行在from截止计入、to截止排除；跨日运行按lastSampledAt所在UTC桶归属，补报不改变桶。八天补报允许，恰好九十天过期；长运行起点可以更早。

自动化时钟必须固定；真实环境应整体平移时间，不能上传已经过期的固定时间。服务端HTTP持久化与真实页面已独立验证，见[验收入口](../validation/memory-runtime-peaks/README.md)；真实设备与跨端联合验收尚待完成，不沿用旧样本验证结论。
