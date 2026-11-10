# Native 与 JVM 后台工作隔离

## ADDED Requirements

### Requirement: Native 原始事件不进入 JVM 符号还原
系统 MUST 将本期 Native 主栈作为 `raw_native` 原始材料持久化，状态为 pending、原因为 `native_symbolication_deferred`、归组为 temporary，实际符号来源及处理时间为空。该状态 MUST 表示本期尚未提供 Native 还原能力，不代表已排入 JVM 任务。Native 主栈、Native 辅助栈和同事件的 Java 辅助栈 MUST NOT 执行 R8，也不得因已有或后上传的 JVM mapping 改变。

Native 接收 MUST NOT 为其查询 mapping 或触发 JVM 工作；后台候选、恢复、对账和结果发布 MUST 排除 Native，即使其与 JVM 事件共享应用及 buildId。现有 JVM 首次成功固定、辅助栈处理和恢复语义 MUST 保持。

#### Scenario: 同构建已有 mapping
- **WHEN** 一个 buildId 已有 mapping，随后收到 Native 和 JVM 事件
- **THEN** JVM 按既有后台规则处理，Native 原始主辅栈不调用 R8，不产生虚假的成功来源

#### Scenario: mapping 变更及服务重启
- **WHEN** 上传、替换、删除 mapping 或重启后端
- **THEN** Native 状态、指纹、原始主辅栈和诊断保持不变，不被扫描成 JVM 待办

#### Scenario: 查询 Native 原始材料
- **WHEN** 查询本期 Native 事件
- **THEN** 返回明确的原始格式与能力延期原因，不启动还原或把无变化输出当成已还原
