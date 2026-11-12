# Jank mapping 版本匹配增量规格

## MODIFIED Requirements

### Requirement: 使用产物 buildId 安全选择 mapping
系统 MUST 在完整性和 packageName 校验后，使用认证 appId、已校验 appVersion 及必填非空 buildId 选择文本 mapping：存在同版本构建专用登记则优先，否则选同版本默认。均不存在 MUST 允许未解混淆解析，MUST NOT 猜测其他专用文件、跨版本、SO 或无版本旧登记。客户端 MUST NOT 提供 mapping ID、系统 appId 或路径。系统 MUST 在上传时解析并保存证据，详情只读，历史不回填，替换不改已保存堆栈、调用树、指纹及重复事件结果。

#### Scenario: 项目 mapping 存在
- **WHEN** 当前应用、appVersion 和 buildId 在注册表存在可用 mapping
- **THEN** 系统固定该版本用于此次解析并保存处理后的方法证据，不允许访问其他应用或版本的文件

#### Scenario: 项目 mapping 不存在
- **WHEN** 当前应用版本既没有适用的专用 mapping，也没有默认 mapping
- **THEN** 系统继续解析和落库未解混淆证据，不因 mapping 缺失要求客户端重传或提供路径

#### Scenario: 替换后查看已有卡顿
- **WHEN** 管理员替换某应用版本构建的 mapping 后查看已完成写入的卡顿
- **THEN** 返回已保存的证据，不重新还原；新上传事件按构建专用优先、版本默认其次选择当前文件

#### Scenario: 重复上传遇到新版本
- **WHEN** 已完整保存的同一事件在 mapping 替换后再次上传
- **THEN** 返回 duplicate 且不覆盖其已保存证据及指纹

#### Scenario: 注册表暂时不可用
- **WHEN** 上传时无法查询符号表注册表或已注册文件损坏不可读
- **THEN** 返回可重试的存储不可用错误，不把基础设施故障伪装成 mapping 缺失

#### Scenario: 相同构建标识属于另一版本
- **WHEN** manifest 为 appVersion=2.0，而只有 appVersion=1.0 的同 buildId mapping，2.0 也无默认文件
- **THEN** 不读取 1.0 文件，按无 mapping 规则保存未解混淆证据

#### Scenario: 版本缺失或预检内容不一致
- **WHEN** manifest 的 appVersion 缺失、类型或范围无效，或者解析回调及最终报告中的身份与同一归档的预检身份不一致
- **THEN** 返回不可重试的产物契约错误，不执行不匹配 mapping 的业务处理，不持久化成功事件

#### Scenario: 客户端沿用已有 v3 产物
- **WHEN** 现有 1.0.4 SDK 上传带 appVersion 和非空 buildId 的合法 v3 ZIP
- **THEN** processor 1.0.4 接受该产物；服务端使用同一 ZIP 的预检版本，核对回调包名/构建及最终报告身份，不修改外部配套制品

#### Scenario: 拒绝缺失构建的 Jank
- **WHEN** v3 manifest 省略 buildId、传 null 或空白，即使同版本已有默认 mapping
- **THEN** 沿用产物必填约束拒绝，不调用 mapping 选择器，不持久化事件；上传 mapping 可选不放宽上报

#### Scenario: 有构建但无专用登记
- **WHEN** 事件带 buildId，专用登记不存在而该版本有默认 mapping
- **THEN** 固定默认文件用于本次解析，保存实际来源和已解析证据

#### Scenario: 存储故障不得作为默认回退
- **WHEN** 专用登记存在但文件不可读，或注册表查询失败
- **THEN** 返回可重试存储错误，不改用默认文件，不把基础设施故障当作专用不存在
