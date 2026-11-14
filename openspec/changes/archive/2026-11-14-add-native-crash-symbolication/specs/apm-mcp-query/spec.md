# Native Crash 只读查询增量规范

## MODIFIED Requirements

### Requirement: 结果有界且统计语义完整
系统 MUST 返回结构化结果，保留单位、实际时间区间、筛选、统计状态、样本量及已有算法/符号表来源。Crash 结果 MUST 只解析当前单份 stack、持久化三态和临时/正式归组类别，不触发请求内还原；成功来源必须来自实际处理记录，JVM 来源归主异常，Native 来源分别归物理帧或 Java 栈，不能用当前活动 SO/mapping 代替。MUST 删除 rawCrash、symbolicatedStackText 旧片段及别名，不将旧后端格式自动转换为当前结果。分页返回续读游标，非分页指标超限明确失败，不静默丢点。大事件证据 MUST 通过有限片段返回范围、截断标记与事件引用；Crash 片段使用当前 stack，保持内容摘要绑定，不能把不同状态发布版本拼接成完整证据；同一 Native 快照中已还原帧与未成功原始帧并存属于合法部分完成。

#### Scenario: 分母不足和缺失采样
- **WHEN** Crash 分母不足或 Jank 缺少采样
- **THEN** 保留不可计算状态与质量，区分零和缺失，不把采样估算当精确耗时

#### Scenario: 证据超过工具结果限制
- **WHEN** 当前事件堆栈超过单次响应上限
- **THEN** 返回明确片段及续读位置，无法安全切片时返回 RESULT_TOO_LARGE，不损坏 JSON 或静默截断后宣称完整

#### Scenario: 符号表已删除
- **WHEN** 查询已成功还原的 Crash，而当前 mapping 已删除或重新上传
- **THEN** 返回固定结果和原处理身份，不重新还原，不声称它使用了新 mapping

#### Scenario: 分片期间后台还原
- **WHEN** Agent 已读取 pending 堆栈的一部分，随后事件成功还原
- **THEN** 旧摘要续读被识别为证据变化，要求重新读取，不将前后载荷混合

#### Scenario: 旧片段和后端格式不再支持
- **WHEN** 客户端请求 rawCrash 或 symbolicatedStackText 旧片段，或后端返回旧详情格式
- **THEN** 旧片段请求返回 INVALID_SECTION，旧详情被识别为后端协议错误，不返回兼容转换结果

#### Scenario: Native 增量期间续读
- **WHEN** Agent 读完 Native stack 或 diagnostics 的一部分后，对应分区又发布了补齐结果
- **THEN** 旧 contentDigest 续读明确报告证据变化并要求重读，不拼接两轮帧或混用不同来源

### Requirement: 只读工具支持 Native 原始栈与完整诊断
Agent HTTP 与 MCP MUST 在现有 Crash 工具及应用授权边界内支持结构化 native_stack、逐 Native 帧还原和独立 Java 栈结果，保持原始信号、精确地址、模块身份、线程身份及采集状态。Native MUST 保持 native-tmp-v1/temporary，即使主栈为 symbolicated；顶层单份符号来源为空，各成功单元必须有实际类型适用来源。MUST 严格校验状态、来源和模块引用，不把地址转成 Throwable，不将主栈成功理解为其他线程完成。

系统 MUST 继续通过现有 stack/diagnostics section、字节上限、contentDigest 和续读规则返回大材料，不新增任意文件、符号管理或修复写工具。既有 JVM、认证、错误和旧格式拒绝行为 MUST 保持；Native 已完成来源删除后仍按保存结果返回，不重新执行还原。

#### Scenario: Native 详情与续读
- **WHEN** 有效应用 Token 请求部分已还原的 Native 主栈及较大的诊断 section
- **THEN** 返回同一发布版本的逐帧结果、独立 Java 栈、精确地址、采集与处理缺失语义及实际来源，可通过摘要绑定片段重建，不丢失其他线程

#### Scenario: 非法类型组合
- **WHEN** 后端把 Native 帧标为成功但缺少实际 SO 来源，把 Java 栈标为成功却使用 SO 来源，或存在悬空模块引用、非法地址及伪造顶层单份来源
- **THEN** MCP 报告后端协议错误，不猜测转换为合法事件，不拿当前活动文件补来源

#### Scenario: 越权及超限
- **WHEN** Token 无权读取目标事件，或请求超过既有结果预算
- **THEN** 保留资源隐藏或明确资源错误，不返回部分材料伪装完整成功

#### Scenario: 主栈与 Java 独立完成
- **WHEN** Native 主栈成功但 Java 仍等待 mapping，或者 Java 已成功而 Native 仍缺 SO
- **THEN** 结果严格保留两种独立事实，Java 成功保留完整文本及实际 mapping，不把其中一种状态套到另一种
