## MODIFIED Requirements

### Requirement: 提供卡顿指标趋势和多维查询
系统 MUST 提供场景 FPS 和设备日挂起率的趋势与多维查询，至少支持时间、应用版本、渠道、环境、Android 版本、设备型号、场景和算法版本筛选。趋势查询 MUST 通过项目范围内的稳定 HTTP API 接受 `metric` 和 `interval`，其中 FPS 支持 `hour` 与 `day`，设备日挂起率仅支持 UTC `day`；不支持的指标、粒度或维度 MUST 返回稳定参数错误。响应 MUST 按时间桶和算法版本返回平均值、P50、P90、P99、总记录数、指标对应的有效记录数、状态和数据源，并保持时间桶左闭右开。挂起率趋势 MUST 先按项目、匿名设备、UTC 日期和查询维度合并设备日记录，再计算每个 UTC 日时间桶的分位数，不得按小时拆分或重复计算同一设备日。多维响应 MUST 在字段缺失时区分总上报数与该字段的有效上报数。

#### Scenario: 按场景查询 FPS 趋势
- **WHEN** 已授权用户指定场景、版本、时间范围和 `interval=hour|day` 查询 FPS
- **THEN** 系统按请求粒度和算法版本返回各时间桶的平均值、P50、P90、P99、总记录数和有效记录数

#### Scenario: 查询设备日挂起率趋势
- **WHEN** 已授权用户指定时间范围和 `interval=day` 查询设备日挂起率
- **THEN** 系统按 UTC 日期返回先合并设备日再计算的平均值、P50、P90、P99、原始区间数和有效设备日记录数

#### Scenario: 挂起率请求小时粒度
- **WHEN** 用户以 `metric=suspension_rate` 和 `interval=hour` 请求趋势
- **THEN** 系统返回稳定的 `INVALID_INTERVAL` 参数错误，不用小时区间伪造设备日挂起率

#### Scenario: 查询挂起率设备分布
- **WHEN** 已授权用户按设备型号或 Android 版本查询挂起率分布
- **THEN** 系统返回各维度值的设备日记录数、有效记录数和挂起率分位数

#### Scenario: 查询不支持的维度
- **WHEN** 用户请求未列入白名单的聚合字段
- **THEN** 系统拒绝请求并返回稳定的查询参数错误，不把任意字段拼接到数据库查询

#### Scenario: 指标趋势没有有效数据
- **WHEN** 某个时间桶没有记录、FPS 没有有效值或挂起率没有有效前台分母
- **THEN** 系统分别返回可判定的空桶或 `no_data`、`no_valid_data`、`denominator_insufficient` 状态，且不可计算的数值为 `null` 而不是零
