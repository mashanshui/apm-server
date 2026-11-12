# Crash 符号表入口增量规格

## MODIFIED Requirements

### Requirement: Crash 事件详情和堆栈展示
前端 MUST 展示单事件的应用、发生/接收时间、版本、构建、渠道、环境、系统、设备、会话、异常类型、当前指纹和持久化还原状态。JVM 主异常详情 MUST 只解析当前 stack 契约：pending/failed 为脱敏原始异常链，symbolicated 为完整还原输出；删除原始/实时还原双份切换及 raw_only、rawCrash、symbolicatedStackText 的旧版解析和展示分支。MUST 安全呈现完整原因链、内联和多候选，并展示失败原因、处理时间及实际符号表来源。mapping 删除后，已成功结果仍可查看；状态不得解释为所有帧都被正确还原。管理员 MUST 可从未完成 JVM 事件进入带 appVersion、mapping 类型及原 buildId 的管理入口；管理员可清空上传表单中的构建以登记版本默认 mapping。不支持的响应格式 MUST 显示协议错误，不猜测或转换为旧视图。

详情 MUST 增加独立诊断区域，显示崩溃线程、其他线程及各自当前堆栈、日志、内存、文件描述符和运行环境；MUST 保持主异常与辅助线程状态分开，不能将“主异常已还原”解释为诊断完整或所有线程已还原。

#### Scenario: 查看原始堆栈事件
- **WHEN** JVM 事件状态为 pending 或 failed
- **THEN** 展示已存原始异常链、等待或失败原因，以及可用诊断，允许按角色进入 JVM 分析任务，不在详情触发同步还原

#### Scenario: 查看不存在或无权限的事件
- **WHEN** 接口返回资源不存在或访问被拒绝
- **THEN** 显示统一提示，不泄露其他应用数据

#### Scenario: 查看持久化还原结果
- **WHEN** JVM 事件为 symbolicated，当前 mapping 甚至已删除
- **THEN** 展示固定完整输出及当时来源，诊断仍可查看，不实时调用还原，不显示原始堆栈切换，也不要求重新上传

#### Scenario: 补传后刷新
- **WHEN** 管理员上传 JVM mapping 后立刻返回 JVM 详情
- **THEN** 后台未完成时仍显示当前 pending/failed；刷新到成功后才显示固定结果，应用切换后旧响应不能覆盖新应用

#### Scenario: 旧详情格式被拒绝
- **WHEN** 后端响应仅有旧双份字段或使用 raw_only 状态
- **THEN** 页面报告协议不匹配，不渲染旧视图或调用实时还原补齐数据

#### Scenario: 从 JVM 事件上传准确版本
- **WHEN** 管理员从 appVersion=2.0、buildId=release 的未成功事件进入符号表页
- **THEN** 预填两个原值并选择 mapping 类型，不使用当前其他版本、最近上传记录或文件修订号代替应用版本

### Requirement: Native Crash 事件详情展示
前端 MUST 展示 Crash 类型、信号、raw_native 寄存器、模块及原始回溯，明示未提供 Native 还原，不显示 JVM mapping、已还原来源或 JVM 分析任务入口；概览名称与混合统计保持。OWNER/ADMIN MUST 可从模块进入 SO 管理，只预填 appVersion 和可取得的 ABI、完整 ELF Build ID，不传应用 buildId；SO 页面 MUST 没有应用构建输入。已知模块身份 MUST 校验 ZIP 包含该身份，未知身份不得猜测。SO 保存成功 MUST 不显示事件已还原。

#### Scenario: Native 原始详情
- **WHEN** 打开 raw_native 事件
- **THEN** 可查看信号、寄存器、模块、原始主栈及共享诊断，显示还原能力延期说明，不出现上传 mapping、已还原来源或创建 JVM 分析任务按钮

#### Scenario: 从明确模块上传 SO
- **WHEN** 管理员选择带完整 ELF Build ID 的模块进入 SO 上传
- **THEN** 管理页预填事件版本与模块 ABI、完整 ELF Build ID，不带应用构建；目标不匹配时报错，成功只显示文件已保存

#### Scenario: 模块缺少 Build ID
- **WHEN** 模块没有可靠 ELF Build ID
- **THEN** 页面明确模块身份未知，可进入该版本构建的通用 SO 管理，但不以文件名判断已有匹配或宣称上传文件与该模块一致

#### Scenario: 应用切换及只读成员
- **WHEN** 用户切换应用、事件或符号类型，或当前角色没有写权限
- **THEN** 清除不再适用的预填模块、文件、冲突和旧请求结果，只读成员没有上传、覆盖或删除操作
