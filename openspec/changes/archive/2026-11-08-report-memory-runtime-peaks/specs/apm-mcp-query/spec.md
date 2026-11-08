# Agent内存峰值查询规范增量

## ADDED Requirements

### Requirement: 内存只读查询使用运行峰值口径

Agent HTTP与MCP的内存概览/趋势 MUST 复用网页业务查询并保持相同授权边界，MCP保留get_memory_summary/get_memory_trend名称和既有筛选。结果 MUST 使用runCount、保留字节/null/状态/数据来源和查询范围，不提供sampleCount兼容别名；工具说明 MUST 明确每轮观测峰值分布、下次启动补报、按最后持久化采样时间归属，以及scene/foreground分别匹配每项峰值上下文。系统 MUST NOT 将历史普通样本转换为运行峰值、将运行数称为设备数或采样次数，也不得新增上传/修改工具。其他只读查询与错误/预算边界 MUST 保持。

#### Scenario: 网页与Agent查询同一固定集
- **WHEN** 相同授权应用与筛选经网页、Agent HTTP、MCP查询
- **THEN** 运行数、分位数、时间桶、零/null和独立峰值上下文筛选一致

#### Scenario: 存储失败与越权
- **WHEN** 内存查询失败或Token无权访问该应用
- **THEN** 返回既有明确错误，不伪造空峰值统计或扩大授权
