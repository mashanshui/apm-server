# 卡顿网页规格增量

## ADDED Requirements

### Requirement: 卡顿分页支持不透明游标和显式恢复

卡顿 Issue 和事件列表 MUST 原样传递服务端 nextCursor，不解析或拼装指纹/事件 ID 游标。续页 MUST 保持首次实际时间窗和筛选上下文；筛选或应用变化 MUST 清空旧分页状态并隔离旧请求。收到 `400 INVALID_CURSOR` 时 MUST 停止继续使用旧游标，展示“重新查询”操作；用户触发后从第一页替换列表，MUST NOT 把第一页作为后续页追加。超时或资源超限 MUST 展示查询失败并允许缩小时间范围，不能显示无数据成功状态。

#### Scenario: 完整加载多页
- **WHEN** 用户连续点击加载更多且服务端返回非空不透明游标
- **THEN** 页面保持当前筛选和时间窗，按服务端顺序追加结果，直到 nextCursor 为空

#### Scenario: 游标失效后重新查询
- **WHEN** 服务端返回 INVALID_CURSOR，随后用户点击重新查询
- **THEN** 页面丢弃旧游标，从第一页重新加载并替换列表，不重复追加旧数据

#### Scenario: 查询预算耗尽
- **WHEN** 总览、趋势或列表返回 QUERY_TIMEOUT 或 QUERY_RESOURCE_LIMIT
- **THEN** 对应区域明确显示失败，提供缩小查询范围的提示，其他成功区域可继续展示
