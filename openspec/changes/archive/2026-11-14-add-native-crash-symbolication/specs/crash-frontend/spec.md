# Native Crash 页面增量规范

## MODIFIED Requirements

### Requirement: Crash 问题和事件下钻
前端 MUST 允许从问题排行进入指纹事件列表，再按 eventId 进入详情，保持当前应用和筛选上下文及服务端游标分页。问题和事件 MUST 清楚标记 temporary/resolved；临时问题 MUST 展示 buildId 和实际主栈处理状态，不能解释为跨版本稳定问题。Native 即使主栈已还原也保持原临时归组；页面 MUST 分开呈现归组和还原状态，不把 temporary 一律解释为尚未还原。补处理期间排行和归属允许变化，页面 MUST 保持既有数据加载、错误和非快照分页提示。

#### Scenario: 从问题排行查看事件
- **WHEN** 用户选择临时或正式问题
- **THEN** 展示该指纹下当前事件、版本、构建、设备及处理状态，临时问题有明确标记

#### Scenario: 加载下一页问题或事件
- **WHEN** 响应包含 nextCursor 且用户加载更多
- **THEN** 使用原应用与筛选请求下一页，不重复追加已有记录，并遵守后台归组变化的非快照语义

#### Scenario: 没有更多结果
- **WHEN** nextCursor 为空
- **THEN** 不再继续分页，不把加载结束误认为所有事件均已还原

#### Scenario: 临时问题已变化
- **WHEN** 用户打开已被补处理移空的临时指纹页面
- **THEN** 显示临时归组可能变化的说明和返回问题列表入口，不伪造唯一正式问题跳转，已知事件仍按 eventId 可达

#### Scenario: Native 已还原仍属临时问题
- **WHEN** Native 主栈已成功且保留 native-tmp-v1 和 temporary
- **THEN** 列表展示主栈已还原及构建内归组，不改问题链接、不声称跨构建正式问题

### Requirement: Native Crash 事件详情展示
前端 MUST 严格解析 native_stack，展示信号、精确寄存器、模块、主栈原始地址及逐帧当前还原结果、完整内联链、状态、源码位置和实际 SO 来源。部分成功、行号缺失、采集截断与还原失败 MUST 分别表达；已成功帧后续不升级的语义 MUST 清楚。OWNER/ADMIN 可进入精确 SO 或 Java mapping 管理，不提供 JVM 分析任务入口。未知格式必须报协议错误，不实时还原或猜测结果。

#### Scenario: Native 原始详情
- **WHEN** 打开尚无成功还原单元的 Native 事件
- **THEN** native_stack 展示原信号、寄存器、模块、主辅原始帧和独立等待原因，不再笼统提示还原能力未提供，不执行实时计算

#### Scenario: 从明确模块上传 SO
- **WHEN** 管理员选择带完整 ELF Build ID 的未成功模块进入 SO 上传
- **THEN** 预填 appVersion、模块 ABI 和完整 ELF Build ID，不带应用 buildId；目标不匹配明确报错，成功只说明保存和后台工作登记，不立即显示事件已还原

#### Scenario: 模块缺少 Build ID
- **WHEN** 模块没有可靠 ELF Build ID
- **THEN** 明确该模块缺少可匹配身份，可进入该版本通用 SO 管理，但不按名称判断已匹配或声称上传即可修复此帧

#### Scenario: 应用切换及只读成员
- **WHEN** 切换应用、事件、符号类型或会话失效，或者当前角色没有写权限
- **THEN** 清理旧线程选择、预填模块、文件、冲突和迟到结果，只读成员能看已存材料但没有上传、覆盖或删除操作

#### Scenario: 函数成功但无行号
- **WHEN** 一帧已用真实 SO 取得函数名，源码行号缺失
- **THEN** 显示帧已还原和行号未知，不显示失败，并说明后续符号替换不会升级这个已固定结果

#### Scenario: 分批上传后的详情刷新
- **WHEN** 第一轮只完成部分帧，第二轮补齐其他模块或线程
- **THEN** 刷新后展示同一已发布版本的完整当前结果，旧成功帧来源不变，不把两次响应拼成快照

## ADDED Requirements

### Requirement: AGP Native 符号包上传入口与能力说明
SO 上传页面 MUST 明确支持直接选择 AGP 生成的 `native-debug-symbols.zip`，说明包内支持的 `.so`、`.so.sym`、`.so.dbg` 及当前大小限制，不要求用户逐文件上传或自行改名。MUST 沿用应用版本、实际目标预填、OWNER/ADMIN 写权限、整批冲突确认、会话清理及上传成功与后台完成分离。页面 MUST 说明函数符号不保证源码行号，源码位置取决于实际调试材料，且首次函数成功固定后不因后续替换自动补齐行号；不得仅凭文件名或后缀宣称完整调试信息。

#### Scenario: 管理员选择 AGP 符号包
- **WHEN** 管理员打开 SO 上传弹窗并选择 `native-debug-symbols.zip`
- **THEN** 可直接提交整个 ZIP，页面明确应用版本和支持的包内格式；成功展示批次登记结果及后台进度，不声称当前事件立即还原

#### Scenario: 上传前识别行号能力边界
- **WHEN** 用户准备上传仅含函数符号的 `.so.sym` 或替换为调试信息更丰富的符号文件
- **THEN** 上传页面提示源码行号取决于实际材料，并说明已有成功帧不升级；不要求用户填写无法从文件名判断的符号等级，不显示未经校验的能力结论

#### Scenario: 从详情上传目标模块与发生冲突
- **WHEN** 管理员从未成功 Native 模块进入上传，选择多 ABI AGP ZIP 后遇到内容冲突
- **THEN** 保留该版本和准确目标 ABI/Build ID，不带应用 buildId，沿用整批快照确认；目标缺失或确认过期明确报错，不按包内同名库视为匹配

### Requirement: Native 事件的 Java 与其他线程独立展示
Native 详情 MUST 分别展示关联 Java 栈、Java 快照及其他 Native 线程的采集和处理状态，Java 成功使用完整还原文本和实际 mapping 来源，未成功保留原始帧和原因。MUST 保留 Java ID、系统 TID、空栈及未知关联关系，不将主栈成功沿用到辅助线程。管理员可为未成功 Java 栈进入预填 appVersion 和原 buildId 的 mapping 管理，且可选择该版本默认 mapping。

#### Scenario: Native 已成功而 Java 等待
- **WHEN** Native 主栈全部成功但 Java 栈缺 mapping
- **THEN** 显示主栈成功及 Java 等待，提供对应 mapping 上传入口，不要求重传 SO、不显示全部线程完成

#### Scenario: Java 先完成
- **WHEN** Java 栈有 mapping 而 Native 缺 SO
- **THEN** Java 完整还原文本可读，Native 仍显示原始地址及 SO 等待，不以主栈未完成隐藏 Java 结果

#### Scenario: 同名与未知关联
- **WHEN** 多个 Native/Java 线程同名，某个已还原 Java 快照关系仍为 unknown
- **THEN** 按各自 ID 展示独立结果，关系继续未知，不把还原后的 Java 栈认作崩溃线程

### Requirement: SO 版本进度不冒充事件完成
SO 页面 MUST 展示该版本 Native 工作的执行、等待、退避与空闲，并将已确认状态发布次数、失败单元次数与唯一事件数区分；同版本不同 SO 共用进度。上传成功不代表还原成功，空闲不代表所有帧完成。mapping 页的 JVM 进度 MUST 不被用作 Native Java 完成证明；详情以实际已存的逐单元状态为准。

#### Scenario: 多轮增量与共享版本进度
- **WHEN** 同一版本两个 SO 先后上传，使同一 Native 事件多轮更新
- **THEN** 两个 SO 的页面显示相同版本进度，发布次数按实际更新说明，不显示为两个已完成事件

#### Scenario: 轮询结束
- **WHEN** 到达轮询时限或任务空闲但仍有未成功单元
- **THEN** 停止自动轮询并允许手动刷新，保留缺失或失败提示，不宣称后台永久全部完成
