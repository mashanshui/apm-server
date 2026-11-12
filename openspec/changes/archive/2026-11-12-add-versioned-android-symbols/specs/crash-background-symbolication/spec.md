# Crash 后台版本隔离增量规格

## MODIFIED Requirements

### Requirement: 可靠唤醒与重启恢复
系统 MUST 持久化按 appId 和 appVersion 合并的后台工作、进展与有界候选轮转位置；默认或专用 mapping 变更 MUST 唤醒该版本，不扫描全部历史构建登记任务。仅内存线程或一次扫描 MUST NOT 成为唯一保证；重启、通知失败、并发变更及迟到事件 MUST 可恢复。版本之间 MUST 不共享租约和计数；同版本事件 MUST 分别按其构建选择专用或默认 mapping。无可处理来源时 MUST 等待，不热重试；活动对账 MUST 排除 SO 和无版本旧登记。

#### Scenario: 上传完成后重启
- **WHEN** 默认或专用 mapping 和对应应用版本工作已持久化，后台执行前进程退出
- **THEN** 重启后任务继续，上传成功的 mapping 不需要用户再次上传才能触发补处理

#### Scenario: 事件晚于扫描落库
- **WHEN** 一轮补处理结束后，同一应用版本的在途或迟到事件才完成接收，其应用构建仍为非空原值
- **THEN** 系统在后续唤醒或对账中处理该事件，不因旧游标已越过发生时间而永久遗漏

#### Scenario: mapping 被删除
- **WHEN** 删除后本版本待处理事件没有适用的专用或默认 mapping
- **THEN** 版本工作进入等待；重新上传默认或适用的专用文件后恢复尚未成功事件，若仍有其他可处理来源则继续处理它们

#### Scenario: 相同构建标识的不同版本
- **WHEN** 应用的两个版本使用相同 buildId，只有其中一个版本上传了 mapping
- **THEN** 只处理有适用专用或默认 mapping 的版本，另一版本保持等待，进度不混入

#### Scenario: 上传版本默认文件
- **WHEN** 同版本包含不同构建的 pending/failed 事件，管理员上传默认 mapping
- **THEN** 通过同一个版本工作恢复符合该默认来源的事件，不要求为所有历史构建分别上传或创建任务

### Requirement: 后台处理有界且失败不阻塞全部数据
后台 MUST 在数据库限定应用、appVersion、保留期、最新状态及游标分页，先取得最多 100 个轻量候选，再确定实际 mapping，仅为有限可处理身份读取载荷。版本条件 MUST 先于 LIMIT；MUST 不加载全量事件或堆栈。无匹配和同来源确定性失败 MUST 有界跳过并推进持久游标，不阻塞后页；到底后 MUST 轮转，常规唤醒不得反复重置第一页。临时失败 MUST 退避，实际来源变更可重试。MUST 保持查询、执行、并发及结果预算，只处理未过期、未成功的 JVM Crash。

#### Scenario: 大量历史待处理事件
- **WHEN** 某应用版本存在大量不同构建的 pending 事件并上传适用 mapping
- **THEN** 上传请求不等待历史数量相关的处理，后台逐批推进，不把该应用全部事件或堆栈加载到应用内存

#### Scenario: 单条异常与资源预算
- **WHEN** 一条事件无法解析或一次查询耗尽预算
- **THEN** 记录失败或延期并保留材料，其他合法事件能够继续；资源失败不能被报告成全部完成

#### Scenario: 最新状态筛选
- **WHEN** 同一事件存在旧 pending 和新 symbolicated 的物理版本
- **THEN** 先确定最新逻辑状态再筛选，已成功事件不被旧 pending 重新选中

#### Scenario: 大量其他版本候选
- **WHEN** 同 buildId 的其他版本有大量更早发生的事件，而当前版本有可处理事件
- **THEN** 数据库先限定当前版本再选取有限身份，当前版本不会被其他版本占满批次而永久遗漏

#### Scenario: 前页均无匹配文件
- **WHEN** 一个版本的前多页事件无可用 mapping，而后页事件有专用文件
- **THEN** 工作有界推进并最终处理后页，跳过事件仍保留原始材料，不能重复读取第一页导致饥饿

#### Scenario: 跳过后重启或较早迟到事件
- **WHEN** 工作推进游标后重启，或出现早于当前游标的迟到事件
- **THEN** 重启从持久位置继续，轮末回头发现较早事件；普通对账唤醒不会使后页永久无法到达

### Requirement: Native 原始事件不进入 JVM 符号还原
系统 MUST 将本期 Native 主栈作为 `raw_native` 原始材料持久化，状态为 pending、原因为 `native_symbolication_deferred`、归组为 temporary，实际符号来源及处理时间为空。该状态 MUST 表示本期尚未提供 Native 还原能力，不代表已排入 JVM 任务。Native 主栈、Native 辅助栈和同事件的 Java 辅助栈 MUST NOT 执行 R8，也不得因已有或后上传的 JVM mapping 改变。

Native 接收 MUST NOT 为其查询 mapping 或触发 JVM 工作；后台候选、恢复、对账和结果发布 MUST 排除 Native，即使其与 JVM 事件共享应用、appVersion 及 buildId。SO 上传、替换、删除和同内容重试 MUST NOT 登记任何还原任务或改写 Native 状态、原始主辅栈、指纹和诊断。现有 JVM 首次成功固定、辅助栈处理和恢复语义 MUST 保持。

#### Scenario: 同构建已有 mapping
- **WHEN** 一个应用版本构建已有 mapping，随后收到 Native 和 JVM 事件
- **THEN** JVM 按既有后台规则处理，Native 原始主辅栈不调用 R8，不产生虚假的成功来源

#### Scenario: mapping 变更及服务重启
- **WHEN** 上传、替换、删除 mapping 或重启后端
- **THEN** Native 状态、指纹、原始主辅栈和诊断保持不变，不被扫描成 JVM 待办

#### Scenario: 查询 Native 原始材料
- **WHEN** 查询本期 Native 事件
- **THEN** 返回明确的原始格式与能力延期原因，不启动还原或把无变化输出当成已还原

#### Scenario: 模块 SO 已上传
- **WHEN** 管理员为 Native 事件所属版本上传匹配 ABI 和完整 ELF Build ID 的 SO，随后替换或删除，事件应用构建仍必填
- **THEN** 只改变符号文件管理记录，Native 事件仍为原始 pending 状态，主辅栈和统计不变，也不生成 JVM 任务

## ADDED Requirements

### Requirement: mapping 来源确定性选择与固定
后台 MUST 统一执行同版本构建专用优先、版本默认其次的规则；应用构建仍必填，专用不存在时才用默认。子批 MUST 固定同一实际 symbolId/revision，失败重试根据实际来源和退避判断，来源新增、替换或删除后可重新选择；成功后 MUST 固定。MUST NOT 猜测其他专用文件、跨版本、SO 或旧无版本登记。升级 MUST 排空旧未确认结果和有效租约，保留原发布字节、来源及序列，不得清空后重新计算。

#### Scenario: 其他版本存在可用 mapping
- **WHEN** 事件版本没有 mapping，但相同 buildId 的另一版本存在合法文件
- **THEN** 当前事件保持原始可查询材料与等待状态，不执行另一版本文件的 R8

#### Scenario: 版本化切换时仍有未确认发布
- **WHEN** 数据结构升级前仍存在未确认首次成功发布或有效在途租约
- **THEN** 切换预检或迁移明确阻止继续，保留原内容，不能清空发布表、重置状态序列或改用新 mapping 重算

#### Scenario: 已成功事件遇到新版本登记
- **WHEN** 旧事件已成功固定，管理员随后按明确版本重新上传同字节或不同字节 mapping
- **THEN** 旧事件保持原 symbolId、修订号、摘要、主辅栈和正式指纹，新登记只影响按选择规则适用的尚未成功事件

#### Scenario: 默认失败后新增专用文件
- **WHEN** build-A 事件使用默认文件确定性失败，随后为 build-A 上传专用 mapping
- **THEN** 可按新的实际来源重试；其他构建仍用默认来源，已经成功事件不重算

#### Scenario: 精确来源不可读
- **WHEN** 专用登记存在但不可读，即使默认文件可用
- **THEN** 保存或报告存储失败并退避，不以默认文件掩盖故障或产生成功来源
