## MODIFIED Requirements

### Requirement: 使用产物 buildId 安全选择 mapping

系统 MUST 在产物完整性和 packageName 匹配校验完成后，使用 App Key 认证得到的 appId 和已校验的 sourceManifest.buildId 从统一符号表注册表选择当前应用的 ProGuard/R8 mapping。客户端 MUST NOT 通过请求头或 manifest 提供 mapping ID、系统 appId 或文件路径；mapping 缺失时系统 MUST 允许以未解混淆状态继续解析，并在内部记录符号化状态。系统 MUST 保持上传时解析还原并保存证据、详情直接读取的处理方式，不在卡顿详情查询时应用 mapping，不回填历史卡顿。符号表替换 MUST 不覆盖已完成事件的堆栈、调用树或指纹，重复事件仍遵循已有幂等语义。

#### Scenario: 项目 mapping 存在
- **WHEN** 当前应用和 buildId 在注册表存在可用文件
- **THEN** 系统固定该版本用于此次解析并保存处理后的方法证据，不允许访问其他应用文件

#### Scenario: 项目 mapping 不存在
- **WHEN** 当前应用没有与 buildId 匹配的 mapping 文件
- **THEN** 系统继续解析和落库未解混淆证据，不因 mapping 缺失要求客户端重传或提供路径

#### Scenario: 替换后查看已有卡顿
- **WHEN** 管理员替换 mapping 后查看已完成写入的卡顿
- **THEN** 返回已保存的证据，不重新还原；新上传事件使用当前版本

#### Scenario: 重复上传遇到新版本
- **WHEN** 已完整保存的同一事件在 mapping 替换后再次上传
- **THEN** 返回 duplicate 且不覆盖其已保存证据及指纹

#### Scenario: 注册表暂时不可用
- **WHEN** 上传时无法查询符号表注册表或已注册文件损坏不可读
- **THEN** 返回可重试的存储不可用错误，不把基础设施故障伪装成 mapping 缺失
