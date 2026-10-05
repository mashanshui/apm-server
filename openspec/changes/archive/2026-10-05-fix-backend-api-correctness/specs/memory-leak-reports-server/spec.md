# 内存泄漏查询规格增量

## MODIFIED Requirements

### Requirement: 授权查询问题与趋势

系统 MUST 提供 GET /api/v1/apps/{appId}/memory-leaks/issues 和 /trend 并校验 Session 应用查看权限。公共参数为 from/to、appVersion、deviceModel、processName、scene、manufacturer、sdkInt、dumpReason、anonymousDeviceId、signature、keyword；除 keyword 对类名/引用字段/原因作字面子串匹配外均为精确筛选，未知参数拒绝。时间按 occurredAt 的 UTC 左闭右开区间，默认最近24小时，最长31天。

issues MUST 支持 page/pageSize（默认1/20，最大100）、sort=occurrences|affectedDevices|lastOccurredAt、order=asc|desc，默认次数降序且以 signature 稳定排序；返回 total、总次数/设备数、dataSource、items，每项包含 signature、leakClass、leakReason、gcRoot、path、lastOccurredAt、occurrences、occurrenceRatio、affectedDevices、deviceRatio、versions。trend MUST 支持 interval=5m|hour|day，默认 hour、最多2000桶，返回 UTC 桶起点、occurrenceCount 和 affectedDeviceCount，空桶补零。空结果与查询失败 MUST 区分；不提供泄漏内存量、复现率、用户统计或累计趋势。


持久化查询 MUST 在数据库侧完成报告逻辑去重、引用链筛选、问题聚合、排序及分页，仅返回统计和当前页展示所需数据。系统 MUST NOT 为查询一页问题或趋势在应用内加载时间窗内全部报告正文。全范围总数、设备数、占比、去重版本集合及完整当前页引用链 MUST 保持既有语义；同一 signature 的最新报告时间相同时，MUST 以 eventId 升序选择代表引用链，保证重复查询确定性。

page MUST 为 1 到 2147483647 的整数，pageSize MUST 为 1 到 100 的整数；偏移计算 MUST 不发生整数溢出。超过末页 MUST 返回空 items 并保留匹配范围的总数。趋势超过 2000 桶 MUST 在访问事件存储前返回 400。关键词 MUST 作为字面子串处理，单引号、反斜杠、百分号及下划线不得改变筛选语义或 SQL 结构。

查询 MUST 复用服务端配置的执行时间、扫描量、内存及响应大小预算；不新增客户端必须提供的预算参数。超时 MUST 返回 `408 QUERY_TIMEOUT`，资源超限 MUST 返回 `422 QUERY_RESOURCE_LIMIT`，存储不可用 MUST 返回可重试的 `503 EVENT_STORE_UNAVAILABLE`。引用链或 versions 超过响应预算时 MUST 明确失败，不静默截断。网页与只读 Agent MUST 使用相同业务口径。

#### Scenario: 分页与空桶
- **WHEN** 问题数超过一页，某时间桶无问题上报
- **THEN** 各页使用相同全范围占比分母、稳定排序，趋势空桶为零，跨桶设备数不累加作为总设备数

#### Scenario: 越权与非法查询
- **WHEN** 用户无应用权限，或请求未知参数、超长时间范围、超量桶
- **THEN** 分别返回授权错误或 400，不泄露其他应用报告

#### Scenario: 小页查询较多报告
- **WHEN** 匹配报告数远大于 pageSize，调用方仅请求第一页
- **THEN** 应用只接收聚合结果和当前页所需的引用链，不加载所有报告正文，统计分母覆盖全部匹配问题

#### Scenario: 去重与筛选先于分页
- **WHEN** 数据含重复上报、同报告重复 signature、空路径、跨应用记录，并使用 signature 或 keyword 筛选
- **THEN** 系统按原有每报告每 signature 一次的口径统计，只在授权应用及匹配路径范围计算分母后分页

#### Scenario: 最新路径时间相同
- **WHEN** 同一 signature 的多个报告拥有相同的最大 occurredAt
- **THEN** 系统稳定选择 eventId 升序的报告作为路径和原因来源

#### Scenario: 请求超过末页或超大页码
- **WHEN** 用户请求超过末页但仍合法的 page，或请求大于 2147483647 的 page
- **THEN** 前者返回空 items 和正确总数，后者返回 400，均不发生溢出或 500

#### Scenario: 超量时间桶
- **WHEN** 时间窗和 interval 的组合会生成 2001 个或更多 UTC 桶
- **THEN** 系统在读取报告存储前返回 400，不进行无效的大范围扫描

#### Scenario: 查询或结果超过预算
- **WHEN** 报告扫描、聚合或包含完整路径及版本集合的结果超过配置预算
- **THEN** 系统返回明确的 408 或 422，不伪造空列表、部分占比或截断路径
