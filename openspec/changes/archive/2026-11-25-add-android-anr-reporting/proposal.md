# 提案

## 背景

Android SDK 已能通过 Matrix 判定 ANR 并在本地保存主线程栈和 trace 片段。需要将这些事件和排查所需的现场上传到平台，使开发者按应用、时间和版本查看 ANR，并能追查线程阻塞与资源状态。

## 变更范围

- Android 继续沿用 Matrix 外部 SIGQUIT 路径的两种判定。消息超时或系统进程错误状态判定产生的 `SIGNAL_ANR` 报告可进入上传流程，分别保留判定来源。只有 trace、手动 trace、debugger 报告和锁解析结果均不单独生成上报事件。
- 为同次捕获保存可关联的主线程 Java 栈和现有 ART 系统 trace 片段。读取本进程 `ProcessErrorStateInfo.longMsg` 作为 `ANR_INFO`，复用 Crash 的内存、Logcat、文件描述符、进程及设备诊断。ANR 首版省去额外的全量 Java 线程快照。
- 增加显式启用的 ANR 上传，复用 Schema v2 批次、gzip、应用鉴权、失败重试与重启补传。原进程诊断在上传前冻结，缺失分区带可识别状态。
- 服务端增加独立 ANR 事件的持久化、去重和应用级查询。Web 增加 ANR 导航、列表、发生次数、影响设备数和事件详情。首版统计采用已接收事件及其设备，按事件和设备计数。
- 服务端复用版本化 mapping，在后台还原 ANR 主线程 Java 栈和系统 trace 中可识别的 Java 帧。Web 提供原文和 Java 还原视图，显示各分区状态及实际 mapping 来源。线程和锁记录、Native 帧及未识别文本按采集内容展示。

## 涉及规格

### 新增能力

- `anr-monitoring-server` 管理 ANR 批次接收、存储、隔离查询、统计与后台 Java 符号还原。
- `anr-monitoring-frontend` 管理 ANR 导航、列表、诊断详情及原文与 Java 还原视图。

### 修改能力

- `android-anr-collection` 将仅本地保存的首期约束改为按配置上报，明确同次采集归属、`ANR_INFO` 和通用现场的采集、冻结与恢复。
- `modular-sdk-composition` 明确本地和上传接入方式、共享传输会话的所有权以及 ANR 独立装配。
- `event-batch-transport` 将 ANR 加入公共 gzip 批次覆盖与确认场景。
- `event-process-identity` 将 ANR 加入原进程标识的校验、补传与详情展示范围。

## 影响

- Android 涉及 ANR Native/Java 回调桥接、私有报告存储、公共诊断采集、传输与聚合入口。
- 后端涉及批次分派、ANR 查询、持久化与后台还原处理，复用版本化 mapping 和 R8 Retrace，沿用应用凭据和网页应用查看权限。
- Web 涉及应用级路由、查询状态、事件详情及还原状态展示。跨端验收覆盖真实系统输入 ANR，以及混淆 Release 包经匹配 mapping 还原后的调用帧和锁信息。
