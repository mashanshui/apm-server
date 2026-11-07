## MODIFIED Requirements

### Requirement: 结果有界且统计语义完整
系统 MUST 返回结构化结果，保留单位、实际时间区间、筛选、统计状态、样本量及已有算法/符号表来源。Crash 结果 MUST 只解析当前单份 stack、持久化三态和临时/正式归组类别，不触发请求内 Retrace；成功来源必须来自实际处理记录，不能用当前 mapping 代替。MUST 删除 rawCrash、symbolicatedStackText 旧片段及别名，不将旧后端格式自动转换为当前结果。分页返回续读游标，非分页指标超限明确失败，不静默丢点。大事件证据 MUST 通过有限片段返回范围、截断标记与事件引用；Crash 片段使用当前 stack，保持内容摘要绑定，不能把原始和还原两个版本拼接成完整证据。

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
