# ANR 客户端采集与本地报告

[返回 Android 知识库](README.md) · [宿主接入步骤](../../../docs/client-integration/anr-local-collection.md)

## 信号与采集

`performance-anr` 在主进程、API 23+ 的 arm64-v8a 进程中显式启用。原生层使用 ByteHook 1.1.1 替换 Matrix 的 xHook PLT 拦截，与项目已有 ShadowHook 2.0.0 共用运行依赖。外部 SIGQUIT 先触发 Matrix Java 采样并等待完成或超时，再准备 trace hook 并转交 ART Signal Catcher。SDK 保存 Java 栈、消息、cgroup 及判定来源。消息阈值和系统错误状态是 Matrix 的两种确认路径，`fromProcessErrorState` 用于区分两者。

PLT 代理保留 Matrix 按系统版本选择的调用方，以及首次命中同线程写入的字符串截取方式。截取结果可能只包含系统 trace 的一部分。debugger 信号分支提供主线程 Java 栈；系统 Native 转储由原处理链执行。手动 trace 在示例与测试中使用，正常宿主无需主动调用。

## 本地报告和生命周期

`AnrComponent` 将采集回调异步写入应用私有 `noBackupFilesDir/performance-anr`。活动 trace 与已完成报告分目录保存，正文和索引提交后通知宿主。报告保留当次会话和进程身份，重启后可以按 ID 读回。SDK 按配置对已登记报告执行数量、容量和过期清理。采集关闭或平台不支持时仍可通过报告入口读取原数据。全量 SDK 复用运行身份，借用已有组件时只释放自己的句柄；最后一个句柄关闭后，同进程再次启动返回 `matrix_restart_not_supported`。ANR 数据仅保存在客户端。

源码入口：[AnrComponent](../../performance-anr/src/main/java/com/shanshui/performance/anr/AnrComponent.kt)、[AnrReportStore](../../performance-anr/src/main/java/com/shanshui/performance/anr/AnrReportStore.kt)、[MatrixTracer](../../performance-anr/src/main/cpp/MatrixTracer.cc)。

## 验证与限制

- Pixel 4 XL / API 33 真机已观察到系统输入 ANR、主线程锁环和本地报告。华为 API 29 真机可以处理受控外部 SIGQUIT；厂商无响应提示时是否向目标进程发送 SIGQUIT 尚未证实。两台真机已验证 debugger 信号的 Java 栈采集，系统 Native 转储是否完成仍未确认。
- 旧版系统分支按实际镜像留证，API 23/24/26 模拟器的系统 trace 文件需预置。华为 API 29 的 32 位进程验证了采集降级和历史报告读取。
- 正常宿主不主动请求手动 trace。本期接受短间隔重复捕获时，后一次 trace 覆盖活动文件，导致归档及完成回调对应到其他次捕获的限制。发生频率尚未实测。
- 已验证权限类写入失败及归档任务尚在队列时进程被终止的恢复。进程在正文落盘、索引提交前被终止时，未登记正文可能残留并超出索引清理范围。该窗口、采集中关闭和实际磁盘耗尽待验证。

专项证据见 [场景对应表](../../../openspec/changes/archive/2026-11-21-add-android-anr-collection/scenario-matrix.md) 和 [验证记录](../../../openspec/changes/archive/2026-11-21-add-android-anr-collection/verification.md)。
