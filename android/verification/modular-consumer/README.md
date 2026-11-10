# 模块化独立消费者验证

该目录不参与主工程构建，用于验证八个发布坐标能够被外部 Android 工程消费。三个消费者分别覆盖仅 Crash、仅 Metrics，以及全量 SDK 加 Native Tools；依赖只写 `com.shanshui.nativelib:performance-*` 坐标，不引用主工程源码。

## 执行方式

从平台根执行 `bash android/gradlew -p android publishToMavenLocal`，将本次制品写入 Maven Local。进入 Android 目录后可继续使用以下 Windows 入口：

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-local')
.\gradlew.bat -p verification/modular-consumer assembleDebug assembleRelease `
  '--project-prop=performance.repo=C:/Users/shanshui/.m2/repository' `
  --no-daemon --console=plain --system-prop=kotlin.daemon.enabled=false
```

`performance.repo` 指向本轮 `publishToMavenLocal` 的验证仓库；Rhea 和 KOOM 等本地第三方传递依赖从用户 Maven Local 只读解析。可用 `-Pperformance.version=<独立验证版本>` 同时指定发布与消费者版本，默认仍为 `1.0.0`。消费者使用已发布 Maven 制品，不引用 SDK 源码。

Release 变体启用 R8。Debug/Release 消费者签名读取与示例 App 相同的四个 `performance.signing.*` Gradle 属性，回退到 Android 工程未跟踪的 `.signing/signing.properties`；证书路径必须为绝对路径，完整配置缺失时签名任务失败。仅复用原测试证书，本地配置与证书不进入版本控制，配置占位示例见[构建指南](../../docs/knowledge-base/11-构建测试与本地发布.md#当前工程入口与本地签名)。

## 验证边界

- Crash 消费者只能解析 Core、Transport、Crash；自带 Crash 的 JNI 与独立回溯程序，不携带 Rhea、KOOM 或 ShadowHook。宿主声明解压原生库，回溯程序从安装目录执行。
- Metrics 消费者只能解析 Core、Transport、Metrics，不触发 CMake 或 Native Tools 构建。
- Full 消费者解析 SDK 和 Native Tools，验证 JNI SO、consumer rules、R8 与重复 `libc++_shared.so` 合并规则。
- 本目录验证编译、Release 混淆、APK 和清单；三个 `SmokeActivity` 已在 Pixel 4 XL/API 33 上完成初始化/关闭/身份序列化/队列目录烟测，Full 消费者同时验证 JNI。Full 烟测使用关闭 Jank/Memory Leak 的精简配置，真实 Rhea/KOOM 设备链路仍由示例 App 覆盖；客户端真实服务地址联调仍未完成，服务端契约回归记录见项目知识库 09/11。

Crash 消费者的 `SmokeActivity` 还支持受控验收参数 `nativeProbe=hold`、`disable-next-start` 和 `signal`：先保持当前监听，再将下一进程设置为 `nativeEnabled=false`，最后发送真实 SIGSEGV；重新启动后检查旧暂存已恢复且未安装新 Native 监听。仅在独立模拟器使用这些入口。
