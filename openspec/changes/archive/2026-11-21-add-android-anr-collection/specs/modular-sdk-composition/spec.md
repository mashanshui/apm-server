# 模块化 SDK 组合规格增量

## ADDED Requirements

### Requirement: ANR 可独立装配且不依赖网络凭据
SDK MUST 支持仅 ANR 和全量显式启用 ANR 两种方式，独立接入无需 App Key、服务地址、传输会话或其他业务模块。全量默认 ANR 关闭；仅 Crash/仅 Metrics 不得因该能力新增原生 hook 依赖或启动 ANR。公开接口 MUST 使用 SDK 自有类型，不暴露底层采集框架类型。

#### Scenario: 无网络的独立 ANR 接入
- **WHEN** 宿主只选择基础设施和 ANR 并提供共享上下文
- **THEN** 可以初始化、采集、读取报告和关闭，无需构造网络配置或引入 Crash/Jank/Leak/Metrics/Native 工具

#### Scenario: 全量默认与显式启用
- **WHEN** 分别使用全量默认配置和显式开启 ANR 的配置
- **THEN** 前者不安装 ANR handler 或启动其监控线程，后者按平台状态启动，其他功能原有默认行为保持

#### Scenario: 既有精简接入
- **WHEN** 消费者仅选择 Crash 或 Metrics
- **THEN** 不因 ANR 新增 hook 框架、ANR 类与原生产物，且不会注册 ANR 信号监听

### Requirement: ANR 共享身份且独立拥有生命周期
同次运行的 ANR MUST 复用公共设备标识、sessionId、processId 与应用元数据，不要求启用 Crash。相同配置重复初始化 MUST 复用，不同配置须报告冲突。ANR 失败 MUST 明确降级，其他功能继续；全量关闭 MUST 按 Matrix 原有语义关闭自身 ANR 资源并保留报告，不终止外部持有资源。原实现无法保证的重新启动或信号所有权不得伪称成功。

#### Scenario: 共同身份与恢复
- **WHEN** ANR 与其他组件共同运行并在下次启动读取旧报告
- **THEN** 当前运行的组件身份一致，旧报告仍保留原运行身份，不被新上下文覆盖

#### Scenario: ANR 启动失败
- **WHEN** ANR 原生库加载或 hook 安装失败
- **THEN** ANR 返回失败或局部降级状态，按原实现处理已有资源，已启用的 Crash、Jank 和指标保持可用

#### Scenario: 重复初始化与全量关闭
- **WHEN** 重复提交相同或冲突 ANR 配置，随后关闭全量运行时
- **THEN** 相同请求不重复安装，冲突请求不改变活动会话；关闭按 Matrix 原有逻辑执行，报告保留可读，并如实反映重复启停的实际状态

#### Scenario: 借用外部组件
- **WHEN** 全量入口借用了外部已启动的采集资源
- **THEN** SDK 不销毁借用的外部组件；Matrix 原有信号处理器恢复行为按实际结果记录
