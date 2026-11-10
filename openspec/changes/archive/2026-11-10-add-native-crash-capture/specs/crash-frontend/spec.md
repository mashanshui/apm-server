# Native Crash 展示扩展

## MODIFIED Requirements

### Requirement: Crash 事件详情和堆栈展示
前端 MUST 展示单事件的应用、发生/接收时间、版本、构建、渠道、环境、系统、设备、会话、异常类型、当前指纹和持久化还原状态。JVM 主异常详情 MUST 只解析当前 stack 契约：pending/failed 为脱敏原始异常链，symbolicated 为完整还原输出；删除原始/实时还原双份切换及 raw_only、rawCrash、symbolicatedStackText 的旧版解析和展示分支。MUST 安全呈现完整原因链、内联和多候选，并展示失败原因、处理时间及实际符号表来源。mapping 删除后，已成功结果仍可查看；状态不得解释为所有帧都被正确还原。管理员 MUST 可从未完成 JVM 事件进入带当前 buildId 的符号表管理入口。不支持的响应格式 MUST 显示协议错误，不猜测或转换为旧视图。

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

### Requirement: 诊断分区可读且真实表达采集边界
详情 MUST 提供可展开的线程列表和堆栈视图；Java 线程按 Java ID、Native 线程按系统 TID 分别展示，同名不得合并。崩溃线程与主线程 MUST 清楚标记，Java ID、系统 TID、PID 与 UUID processId MUST 使用不同标签；Native 事件无法可靠关联的 Java 快照 MUST 显示关系未知。空 Java/Native 栈 MUST 显示没有取得帧的事实，不生成调用内容。

日志 MUST 可按已返回的缓冲区查看，内存 MUST 标明单位和指标口径，FD MUST 展示已知总数及已保存条目。所有分区 MUST 区分成功空结果、未采集、不可用、跳过、部分成功和截断，并呈现安全原因；零值不能显示成缺失。文本 MUST 转义，MUST NOT 作为 HTML 执行或加载诊断文本中的链接资源。缺失诊断 MUST 不阻塞主异常查看及符合事件类型的既有分析入口。应用、事件切换或会话失效 MUST 清除旧诊断和线程选择并拒绝迟到响应。页面 MUST NOT 为排除的三类内存转储提供空白占位或采集入口。

#### Scenario: 查看完整诊断
- **WHEN** 详情包含所有诊断分区
- **THEN** 用户可按线程查看当前栈，并分别查看日志、内存、FD 和环境，不需要在超长原文中手工定位

#### Scenario: 同名线程和独立还原状态
- **WHEN** 两个 JVM 辅助线程同名，一个已还原而另一个保留原始帧
- **THEN** 分别显示线程身份、当前栈和对应状态，不合并线程或沿用主异常成功标签

#### Scenario: 缺失、截断与合法零值
- **WHEN** 一个分区未采集，一个分区被裁剪，且内存或 FD 合法值为零
- **THEN** 分别显示未采集、截断和真实零值，不全部显示为空或失败

#### Scenario: 诊断文本包含脚本
- **WHEN** 日志、线程名或路径含 HTML、脚本或链接文本
- **THEN** 只作为已转义文本显示，不执行脚本、不产生隐式网络请求

#### Scenario: 切换事件时旧响应迟到
- **WHEN** 切换应用或事件后上一请求才返回
- **THEN** 旧诊断、缓冲区和线程选择不能覆盖当前事件

#### Scenario: Native 与 Java 快照并存
- **WHEN** Native 事件包含同名 Native/Java 线程及无法关联的 Java 快照
- **THEN** 按线程类型与各自 ID 分组，未知关系清楚可见，保留每线程采集状态和精确地址

## ADDED Requirements

### Requirement: Native Crash 事件详情展示
前端 MUST 展示事件的 Crash 类型、信号及 raw_native 的寄存器、模块和原始 backtrace；MUST 明示本期未提供 Native 符号还原，不显示 JVM mapping 上传、已还原来源及 JVM 专用分析任务创建入口。概览和导航名称 MUST 能涵盖 JVM 与 Native，混合查询保持当前统计语义。

#### Scenario: Native 原始详情
- **WHEN** 打开 raw_native 事件
- **THEN** 可查看信号、寄存器、模块、原始主栈及共享诊断，显示还原能力延期说明，不出现上传 mapping、已还原来源或创建 JVM 分析任务按钮
