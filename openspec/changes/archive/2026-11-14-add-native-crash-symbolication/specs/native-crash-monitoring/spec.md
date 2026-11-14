# Native Crash 还原增量规范

## MODIFIED Requirements

### Requirement: Native 原始归组与统计保持当前精确口径
Native MUST 保持独立版本 `native-tmp-v1` 的既有临时指纹，绑定应用、应用 buildId、运行 ABI、信号/code 和有序主帧的模块身份及相对 PC；无可靠帧时使用明确的现场不足类别。绝对加载地址、故障地址、寄存器、日志、辅助线程、Java 栈及现场或后台函数显示名 MUST NOT 参与指纹。Native/Java 还原与后续补齐 MUST NOT 改变原指纹、版本、构建内问题归属或 `temporary` 类别，不承诺跨构建归组。

所有 Crash 查询 MUST 在数据库侧按既有应用、时间和维度范围纳入 JVM 与 Native fatal，复用同一 app_start 分母和精确去重；原事件重传不增加事件、崩溃会话或设备数，诊断不得成为统计事件。列表和聚合 MUST NOT 读取完整栈或诊断，MUST 保持返回数量和资源预算。

#### Scenario: ASLR 与诊断不同
- **WHEN** 同构建、同 ABI 和同主栈的两次 Native 崩溃仅加载地址、日志或其他线程不同
- **THEN** 归入同一 Native 临时问题，各自现场独立保存

#### Scenario: 混合会话固定集
- **WHEN** 固定集包含 JVM/Native、重复重试、同会话多个 Crash 以及没有 app_start 的窗口
- **THEN** 各查询精确匹配人工期望，崩溃会话去重，缺少分母仍不可计算，后台 JVM/Native/Java 还原不改变总数

#### Scenario: 大诊断下的摘要查询
- **WHEN** 有大量 Native 大诊断事件而只查询有限时间窗的概览或列表
- **THEN** 数据库只读取必要轻量投影并执行有界过滤/聚合/分页，不在应用内加载全量现场

#### Scenario: 分批还原保持同一问题
- **WHEN** 同一事件先还原部分 Native 帧，随后补齐其他 SO 和 Java mapping，最终主栈成功
- **THEN** 事件始终保留最初的指纹、native-tmp-v1、temporary 和问题归属，同一筛选下事件数、会话数及设备数不变

## ADDED Requirements

### Requirement: Native 帧按精确模块身份还原
系统 MUST 对主栈及其他 Native 线程中的可处理帧，按同应用、同应用版本、ABI 和完整 ELF Build ID 精确选择 SO，使用原相对 PC 取得函数信息及可用源码位置。MUST NOT 根据同名库、应用 buildId、其他版本或截短标识猜测匹配。MUST 保留原帧顺序、模块引用、精确地址、采集状态、信号和寄存器；不得伪造缺失的模块或回溯。

#### Scenario: 两个模块分批上传
- **WHEN** 主栈同时引用模块 A 和 B，而仅 A 有精确匹配的 SO
- **THEN** 只还原 A 的可处理帧，B 保留原始地址及等待原因，上传 B 后可继续补齐

#### Scenario: 同名 SO 身份不同
- **WHEN** 已上传 SO 与事件模块同名，但应用、版本、ABI 或完整 ELF Build ID 任一不同
- **THEN** 不使用该文件还原，不将名字相同视为匹配

#### Scenario: 原始地址与内联链
- **WHEN** 一个物理帧可还原为多个内联调用项，且地址超过 JavaScript 安全整数范围
- **THEN** 完整保留有序内联链并仍计作一个物理帧，地址保持精确字符串，不额外改写地址或生成采集帧

#### Scenario: 输入身份不可取得
- **WHEN** 某帧缺少可靠模块身份、完整 ELF Build ID 或相对 PC，或者采集结果没有帧
- **THEN** 明确表达不可还原或未取得帧，不猜测文件、不生成成功结果、不持续热重试

### Requirement: Native 成功帧首次结果固定
系统 MUST 以原主栈 index 或系统 TID 加原 index 识别物理帧，并核对原模块引用和相对 PC。首次通过实际匹配 SO 取得有效函数名即为成功，MUST 固定完整结果、处理时间及 SO 身份、修订和摘要；源码文件或行号缺失不阻止成功。采集时已有函数名及全未知输出 MUST NOT 被当作后台成功。后续补齐只能修改未成功帧。

#### Scenario: 只有函数符号
- **WHEN** 匹配 SO 可提供函数名但没有源码行号
- **THEN** 该帧成功并明确行号未知，之后上传含行号的新修订也不升级已成功结果

#### Scenario: 替换或删除已使用 SO
- **WHEN** 帧已成功，随后原 SO 被替换、删除或以新 symbolId 重新上传
- **THEN** 已成功帧的结果、来源及时间保持不变，同事件其他未成功帧仍可选取适用的新来源

#### Scenario: 文件匹配但地址未知
- **WHEN** 实际文件匹配而该地址的函数信息全部未知，或者只存在采集时记录的函数名
- **THEN** 不声称后台成功，保留原始帧、失败原因和实际尝试来源，允许来源变化后的重试

### Requirement: Native 事件的 Java 栈独立使用 mapping 还原
系统 MUST 对可靠关联的主 Java 栈和其他 Java 线程快照分别执行完整栈还原，选择同版本构建专用 mapping，专用不存在才使用同版本默认。每条 Java 栈 MUST 独立固定首次完整成功文本、处理时间和实际来源；缺少 SO 或 Native 主栈失败不得阻止 Java 处理，反向同样成立。MUST 保留 Java ID、系统 TID 及采集时的关联关系，不因还原改变指纹。

#### Scenario: 只有 mapping
- **WHEN** Native 事件没有任何适用 SO，但其版本构建有 mapping 且带非空 Java 栈
- **THEN** 可先还原关联 Java 栈和 Java 快照，Native 帧继续等待 SO

#### Scenario: 只有 SO
- **WHEN** Native 帧有适用 SO，但 Java 栈没有适用 mapping
- **THEN** Native 可先完成，Java 原始帧继续可见，后续 mapping 上传后自动补处理

#### Scenario: Java 栈部分成功后补齐
- **WHEN** 某个 Java 线程已成功而另一线程失败，随后替换 mapping
- **THEN** 成功线程保持原文本与来源，仅未成功线程按实际新来源重试，主 Java 栈和 Native 栈互不覆盖

#### Scenario: 专用来源不可读与关联未知
- **WHEN** 构建专用 mapping 存在但不可读，或某 Java 快照与崩溃线程的关系为 unknown
- **THEN** 前者作为存储故障而不回退默认，后者可独立还原但仍显示关系未知，不被推断为崩溃线程

### Requirement: Native 主栈摘要与完整处理状态分别表达
系统 MUST 通过结构化 `native_stack` 返回原现场与当前逐帧结果，并分别返回关联 Java 栈、其他 Native/Java 线程的处理状态和实际来源。摘要保留 pending/failed/symbolicated 三态，只描述 Native 主栈；非空主栈所有帧成功才为 symbolicated。部分成功 MUST 以数量和逐帧状态表达，MUST NOT 等同整事件完成。Native 顶层单份符号来源 MUST 为空，实际来源归各处理单元。

#### Scenario: 主栈已完成但其他线程未完成
- **WHEN** Native 主栈所有帧成功，但其他 Native 或 Java 线程还等待文件
- **THEN** 摘要可为 symbolicated，辅助状态仍明确等待且后台继续处理，不声称所有线程完成

#### Scenario: 部分完成与不可处理帧
- **WHEN** 主栈含成功帧和等待帧，或只剩确定性失败、缺少身份的未成功帧
- **THEN** 前者摘要为 pending，后者为 failed，并准确返回各状态数量；原采集截断或失败事实仍独立保留

#### Scenario: 无主栈但有 Java 快照
- **WHEN** Native 主回溯为空而 Java 快照可还原
- **THEN** 主栈为 pending/capture_stack_unavailable，不伪造 Native 成功，Java 结果仍可独立发布和查看
