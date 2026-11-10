# Crash 原生产物交付

## ADDED Requirements

### Requirement: Crash AAR 完整交付自有原生采集产物
Crash Release AAR MUST 携带自身 JNI 库、dumper、许可证与所需 consumer rules，覆盖 arm64-v8a、armeabi-v7a、x86、x86_64，不把其他功能的 arm64 限制扩散到 Crash 精简消费者。消费者通过发布坐标接入 MUST 不需要外部源码检出、本 SDK 的 CMake/NDK 配置或其他功能模块；文档 MUST 明确宿主原生库解压/打包要求。

产物 MUST 在 API 21 下限及当前目标系统的可执行路径、架构、链接和页大小规则下验证；不得依赖从应用可写目录执行临时复制的 dumper。未满足支持条件时 MUST 按 Native 初始化降级契约处理，不影响 JVM。已有宿主网络策略及其他模块的按需分发约束 MUST 保持。

#### Scenario: 独立 Crash 消费者
- **WHEN** 独立工程从 Maven Local 解析本轮 Crash 产物并构建 Debug/Release APK
- **THEN** 依赖、JNI/dumper 和混淆保留规则完整，不引入 Rhea、KOOM、ShadowHook 或 Native 工具，消费者不配置本 SDK 的原生编译

#### Scenario: 安装后执行
- **WHEN** 受支持系统安装符合文档打包约定的 APK 并触发真实 Native Crash
- **THEN** dumper 可从受控安装目录运行，报告可恢复；不能只凭 APK 内存在文件宣称运行验收通过

#### Scenario: 验证边界
- **WHEN** 某 ABI、最低 API 或真机本轮未运行
- **THEN** 交付资料明确区分产物检查、模拟器运行和真机证据，不用已有 JVM 或历史 xCrash 样例替代
