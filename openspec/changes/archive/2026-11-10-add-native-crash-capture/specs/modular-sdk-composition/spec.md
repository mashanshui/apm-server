# Crash Native 能力装配

## ADDED Requirements

### Requirement: Native 采集归 Crash 模块独立管理
Crash 能力 MUST 提供 nativeEnabled 开关，默认 true，并受现有 enabled 总开关约束。nativeEnabled=false MUST 保留 JVM 采集及已存在 Native 记录恢复；enabled=false MUST 不注册本次 Crash handler。Native 不支持或初始化失败 MUST 明确记录安全原因并保持 JVM 功能和其他模块可用。

Native 监控 MUST 只依赖 Crash 自有产物和既有基础设施，不得要求引入 Jank、Leak、Native 工具或其重型引擎，不得因本功能启动额外的 Java/ANR handler。

#### Scenario: 默认 Crash 接入
- **WHEN** 在受支持设备使用默认 Crash 配置初始化
- **THEN** JVM 与 Native 监控均启用，不隐式启用其他监控能力

#### Scenario: 关闭 Native 后补传
- **WHEN** nativeEnabled=false 且存在之前保存的 Native 报告
- **THEN** 不注册 Native 信号处理器，JVM 与旧 Native 报告恢复上传继续可用

#### Scenario: 初始化降级
- **WHEN** Native 产物不可加载或系统条件不支持
- **THEN** 应用和 JVM 监控继续运行，安全日志可区分失败原因，不宣称 Native 已启动

### Requirement: Native 初始化和关闭不覆盖其他持有者
重复初始化 MUST 不重复注册 handler、创建 dumper 资源或生成多个上传器。初始化中途失败 MUST 回收本次资源；关闭 MUST 幂等，只释放自己持有的资源和信号处理权，不覆盖后来安装的第三方 handler。重新初始化 MUST 不破坏已有队列及原事件身份。

#### Scenario: 重复初始化和关闭
- **WHEN** 同配置重复初始化后连续关闭两次
- **THEN** 最多保留一套活动采集资源，资源仅释放一次，旧队列不删除

#### Scenario: 第三方后来注册
- **WHEN** SDK 启动后另一组件注册了信号处理器，再关闭 SDK
- **THEN** 不把后来注册的处理器覆盖回 SDK 之前的版本
