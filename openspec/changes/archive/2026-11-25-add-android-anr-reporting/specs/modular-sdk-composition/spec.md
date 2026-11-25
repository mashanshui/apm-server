# 模块化 SDK 组合增量规格

## MODIFIED Requirements

### Requirement: ANR 可独立装配且不依赖网络凭据

SDK MUST 支持仅 ANR 和全量显式启用 ANR 两种方式。本地采集无需 App Key、服务地址、传输会话或其他业务模块。全量默认 ANR 关闭，仅 Crash 或仅 Metrics 不得因该能力新增原生 hook 依赖或启动 ANR。公开接口 MUST 使用 SDK 自有类型。ANR 上传 MUST 独立显式启用，并使用有效配置的传输会话。

#### Scenario: 无网络的独立 ANR 接入

- **WHEN** 宿主只选择基础设施和 ANR 并提供共享上下文
- **THEN** 可以初始化、采集、读取报告和关闭，无需构造网络配置或引入 Crash/Jank/Leak/Metrics/Native 工具

#### Scenario: 全量默认与显式启用

- **WHEN** 分别使用全量默认配置和显式开启 ANR 的配置
- **THEN** 前者不安装 ANR handler 或启动其监控线程，后者按平台状态启动，其他功能原有默认行为保持

#### Scenario: 既有精简接入

- **WHEN** 消费者仅选择 Crash 或 Metrics
- **THEN** 不因 ANR 新增 hook 框架、ANR 类与原生产物，且不会注册 ANR 信号监听

#### Scenario: 仅接入 ANR 并上传

- **WHEN** 宿主独立装配 ANR 并显式启用上传，提供可用的 ANR 传输配置
- **THEN** ANR 可采集、恢复和上传，无需启用或引入 Crash 等其他业务能力

#### Scenario: 全量刷新与传输所有权

- **WHEN** 全量入口启用了 ANR 上传，调用共享刷新并随后关闭 SDK
- **THEN** ANR 参与共享刷新，组件只释放自己持有的资源。借用的网络会话仍归原持有者，独立采集和已保存报告可按原生命周期处理

#### Scenario: 临时不可上传或目标配置变更

- **WHEN** 网络不可用、配置未就绪，或重启后使用了不同服务地址或应用凭据
- **THEN** 采集可独立运行，原目标的待上传事件保留原身份，不发送到新目标
