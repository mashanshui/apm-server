# 固定数据集

本目录维护跨端固定样本、人工计算的期望值和使用边界。数据文件仍保留在后端测试资源或共享夹具目录；本文档不复制测试数据，不以固定规模结果代表生产容量。

| 数据集 | 主要口径 |
|---|---|
| [Crash 固定验收数据](crash-fixed-dataset.md) | 会话分母、去重、Crash 率与 Issue |
| [卡顿固定数据](jank-fixed-dataset.md) | 个例质量、耗时、FPS 与设备日挂起率 |
| [内存指标固定数据](memory-fixed-dataset.md) | 合法零值、缺失值、插值分位数和去重 |
| [内存报告固定数据](memory-leak-fixed-dataset.md) | 报告与路径去重、问题计数和趋势 |

复验前核对目标应用、时间窗、TTL 和数据库范围，不直接向正式资源写入固定数据。内存报告文件见[metadata 示例](../fixtures/memory-leak-metadata.example.json)和[report 示例](../fixtures/memory-leak-report.example.json)。执行方法与证据边界见[平台测试](../knowledge-base/08-测试与质量保障.md)及[验收资料](../validation/README.md)。

返回[项目文档导航](../README.md)。
