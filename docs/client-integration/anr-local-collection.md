# Android ANR 本地接入

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

常规接入由系统信号触发，宿主无需调用 `printTrace()`。本地报告不进入上传队列。采集限制、真机覆盖和故障验证见 [Android ANR 专题](../../android/docs/knowledge-base/18-ANR客户端采集与本地报告.md#验证与限制)。
