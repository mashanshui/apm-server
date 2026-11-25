# 公共批次传输增量规格

## MODIFIED Requirements

### Requirement: SDK 自建会话对 JSON 批次统一使用 gzip

Android SDK 自建网络会话 MUST 对批量事件 POST 请求的 UTF-8 JSON 正文执行且仅执行一层 gzip。该规则 MUST 覆盖Crash、启动、FPS、内存峰值与 ANR 上传；前台挂起汇总 MUST 通过合成批次验证编码与接收，不要求新增 Android 采集上传链路，不区分全量或按需接入。SDK MUST NOT 设置压缩大小阈值、按压缩率回退或因服务端拒绝而改发普通 JSON。宿主完整提供的外部网络会话 MUST 保持借用语义，其传输策略不由 SDK 自动改写。

#### Scenario: 小批次和压缩后变大的批次
- **WHEN** SDK 自建会话提交小于 1 KiB 的合法 JSON 批次，或者 gzip 后字节数大于原 JSON
- **THEN** SDK 仍发送完整 gzip 正文，不发送普通 JSON，也不跳过该批次

#### Scenario: 各事件类型使用一致编码
- **WHEN** 全量或按需接入通过 SDK 自建会话上传JVM/Native Crash、启动、FPS、内存峰值或 ANR 批次，或测试客户端提交合成前台挂起批次
- **THEN** 线上正文均为单层 gzip，解压后的字节与本次批次原 UTF-8 JSON 一致

#### Scenario: 外部网络资源由宿主管理
- **WHEN** 宿主完整提供外部网络客户端和 API 会话
- **THEN** SDK 不替换或修改其传输策略，关闭时也不释放宿主资源

#### Scenario: ANR 批次确认丢失

- **WHEN** 服务端已持久化 gzip 批次中的 ANR，客户端未收到有效业务确认
- **THEN** SDK 保留冻结事件，后续按相同应用、事件 ID、原身份和载荷重试；重复确认不使 ANR 重复计数
