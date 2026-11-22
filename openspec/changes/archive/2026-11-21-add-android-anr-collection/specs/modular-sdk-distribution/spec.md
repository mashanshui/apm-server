# 模块化 SDK 分发规格增量

## MODIFIED Requirements

### Requirement: 所有生产模块可以通过发布坐标消费

九个生产模块（含新增 ANR）MUST 使用统一版本分别发布 Release AAR、sources JAR、POM 和 Gradle module metadata，并声明正确的编译与运行时依赖。完成迁移后 MUST 不再以旧单体产物作为接入前提。

#### Scenario: 独立工程解析本轮发布

- **WHEN** 独立消费者使用本轮唯一版本从 Maven Local 解析 SDK 各模块
- **THEN** 不依赖源码替换、复合构建或旧 nativelib 产物即可编译；所需直接与传递依赖均可由发布元数据解析

#### Scenario: 使用 SDK 的公开配置类型

- **WHEN** 消费者只声明全量 SDK 坐标并使用其公开配置与功能契约
- **THEN** 编译类路径包含所有公开签名需要的类型，无需补充因错误依赖作用域遗漏的内部模块坐标

## ADDED Requirements

### Requirement: ANR 产物独立交付采集依赖
ANR Release AAR MUST 携带首期 arm64-v8a JNI、来源许可证与 consumer rules，发布元数据 MUST 完整声明自身运行依赖。仅 ANR 消费者不得因 SDK 引入 Matrix、xHook、Rhea、KOOM、Crash、Metrics、Native 工具或网络传输模块，不要求外部源码检出和本 SDK 的 NDK/CMake 配置；minSdk 21 消费者可解析并按平台状态降级。

#### Scenario: ANR 消费者编译基线
- **WHEN** 宿主选择 ANR 或包含 ANR 的全量 SDK
- **THEN** 宿主使用 compileSdk 36 的工程可编译，使用 Kotlin 1.9 的消费者仍可编译，且 targetSdk、minSdk 不因 SDK 强制升级

#### Scenario: 独立 ANR 消费者
- **WHEN** 独立工程从 Maven Local 解析本轮 ANR 坐标并构建 Debug/Release 混淆 APK
- **THEN** 编译、JNI 绑定、报告字段和读取接口完整，依赖图不包含未选择业务能力，不靠宽泛 App keep 规则补救

#### Scenario: 不支持平台加载
- **WHEN** minSdk 21 消费者在低于采集下限或没有 ANR JNI 的 ABI 上运行
- **THEN** 应用仍可启动、读取旧报告并返回不支持状态，不把首期 arm64 采集限制扩散到其他模块

### Requirement: 多原生能力共存的最终产物可核验
全量或 ANR 与其他原生能力组合的最终依赖图及 APK MUST 使用一致且可解释的共享原生库版本，不得以随机保留同名文件掩盖不兼容。新增 ANR 后仅 Crash/仅 Metrics MUST 仍不携带新增 hook 依赖或 ANR SO。产物存在、运行通过和设备覆盖 MUST 分别留证。

#### Scenario: ANR 与 Jank 共存
- **WHEN** 同时选择 ANR、Jank 及 Native 工具并构建运行支持的 arm64 APK
- **THEN** 共享库版本一致、无重复打包错误，ANR 和现有能力均可初始化、工作和关闭

#### Scenario: 既有精简分发回归
- **WHEN** 检查仅 Crash 和仅 Metrics 的依赖、APK 和清单
- **THEN** 不出现由 ANR 新增的类、hook 依赖、SO 或服务声明

#### Scenario: 验证矩阵缺项
- **WHEN** 某系统版本、信号来源或物理设备未执行
- **THEN** 明确记录未验证，相关验收任务保持未完成，不用静态产物或手动信号替代真实系统 ANR 证据
