# Native 只读查询扩展

## ADDED Requirements

### Requirement: 只读工具支持 Native 原始栈与完整诊断
Agent HTTP 与 MCP MUST 在现有 Crash 工具和应用授权边界内支持 Native 事件。MCP MUST 严格区分 JVM raw_chain/retrace_text 与 Native raw_native；Native 原始材料 MUST 携带 temporary、pending、`native_symbolication_deferred` 和空符号来源，不得把 Native 转换成 Throwable 或成功还原文本。

系统 MUST 保留 Native 信号、精确地址字符串、模块身份、主辅 Native 栈、Java 快照及共享诊断的状态和缺失语义。大材料 MUST 继续通过当前 stack/diagnostics section、字节上限、contentDigest 和续读规则返回；不得新增任意文件、符号管理或修复写工具。既有 JVM、认证、错误和旧格式拒绝行为 MUST 保持。

#### Scenario: Native 详情与续读
- **WHEN** 有效应用 Token 请求 Native 摘要及较大的主栈、诊断 section
- **THEN** 返回同一事件的原始格式、延期原因、精确字符串和可重建片段，后续读取绑定内容摘要且不丢弃 Native 线程

#### Scenario: 非法类型组合
- **WHEN** 后端返回 raw_native 却带 JVM 成功来源、悬空模块引用或不合法地址
- **THEN** MCP 报告后端协议错误，不把材料猜测转换成合法事件

#### Scenario: 越权及超限
- **WHEN** Token 无权读取目标事件，或请求超过既有结果预算
- **THEN** 保留资源隐藏或明确资源错误，不返回部分材料伪装完整成功
