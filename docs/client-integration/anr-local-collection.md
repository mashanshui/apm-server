# Android ANR 采集与上传接入

[返回客户端接入目录](README.md)

## 初始化

仅接入 ANR 时，依赖 `com.shanshui.nativelib:performance-anr:<SDK 版本>`，与其他 SDK 模块使用同一发布版本。离线初始化无需 App Key 或服务地址。从主线程（例如 `Application.onCreate()`）启动：

```kotlin
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrConfig

val anr = AnrComponent.start(application, AnrConfig(enabled = true))
val status = anr.initResult
```

全量接入时，在 `PerformanceConfig` 传入 `anr = AnrConfig(enabled = true)`，从初始化返回实例的 `anr` 获取句柄，并通过 `anrInitResult` 查看状态。两种方式默认都关闭 ANR。采集面向 API 23+、arm64-v8a 的应用主进程，初始化返回 `DEGRADED` 和 `matrix_hook_status_unverified` 表示 Matrix 启动流程已执行、hook 安装状态仍需实际信号验证。

## 读取与关闭

- 通过 `anr.reports.list(pageSize = 20)` 查询摘要，下一页传入上页 `nextAfterId`。通过 `read(id)` 读取正文，`delete(id)` 删除指定报告，`clean()` 执行容量和过期清理。文件查询建议在后台执行。
- 通过 `anr.setReportListener { summary -> /* 使用报告摘要 */ }` 设置保存完成回调。回调在后台归档线程执行，读取 UI 前切换到主线程。
- 独立持有的句柄由宿主调用 `close()` 释放。全量持有的句柄由全量 SDK 关闭。需要单独读取历史时可使用 `AnrComponent.reports(application)`，无需启用采集。

常规接入由系统信号触发，宿主无需调用 `printTrace()`。只启用采集时不会上传；手动 trace 和没有 ANR 判定的 trace 不产生上传事件。

## 显式启用上传

全量 SDK 在已有 `PerformanceConfig` 中传入 `anr = AnrConfig(enabled = true, uploadEnabled = true)`，并按[统一初始化](../../android/docs/knowledge-base/16-SDK配置指南.md#1-最小初始化)配置服务地址和 App Key。独立接入可以让组件创建并持有自己的传输会话：

```kotlin
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrConfig
import com.shanshui.performance.network.NetworkConfig

// 持有 ANR 句柄，用于读取报告和按生命周期关闭。
val anr = AnrComponent.startWithNetwork(
    application = application,
    config = AnrConfig(enabled = true, uploadEnabled = true),
    networkConfig = NetworkConfig(
        baseUrl = "https://apm.example.com",
        appKey = BuildConfig.PERFORMANCE_APP_KEY,
    ),
)
```

也可通过 `AnrComponent.start` 借用已有 `TransportSession`；借用会话仍由宿主负责关闭。独立句柄的 `flushAsync()` 和全量 SDK 的 `flushAsync()` 只触发异步上传检查，不保证立即发送或跳过退避。采集与上传分别默认关闭；默认上传检查间隔为 30 秒，可通过 `uploadIntervalMillis` 配置为正数。

符合条件的 ANR 先持久化核心和原进程身份，再在有限窗口内补充并冻结现场。断网、服务端临时失败或无效确认会保留原载荷重试；重启不重新采集旧事件。待确认项绑定原服务地址和凭据摘要，目标变更不会将旧事件发送给新目标。关闭上传时保留已登记项，恢复相同目标后可继续处理；原先仅保存在本地、未登记待上传的历史报告不会回灌。正式字段与响应见 [ANR API](../api/anr-api.md)。

采集限制、设备覆盖和故障验证见 [Android ANR 专题](../../android/docs/knowledge-base/18-ANR客户端采集与本地报告.md#验证与限制)。
