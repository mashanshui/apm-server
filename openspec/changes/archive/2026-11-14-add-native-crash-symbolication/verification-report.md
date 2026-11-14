# 归档前核验记录

核验日期：2026-11-14。变更的 53 项任务均已勾选，规划材料齐全；用户在获知核验结果后要求同步正式规范并归档。归档仅保留当时的实现和验证记录，不表示下列问题已经修复，也不代表全部验收场景已经通过。

## 尚未修复的核验问题

1. **Native 后台轮次期限起点偏晚。** [NativeBackgroundProcessor.java](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/NativeBackgroundProcessor.java) 在重放未确认发布和查询候选页后才计算 30 秒截止时间；前述阶段耗时不会计入开始新事件的轮次预算。核验使用受控时钟复现了前置操作已耗时超过 30 秒后仍开始新事件。后续应从轮次开始计时，并覆盖重放和候选查询耗时。
2. **Native 首次发布前列表与详情摘要可能不一致。** [CrashQueryService.java](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/CrashQueryService.java) 会对原始状态做详情投影，列表则读取持久化状态；主帧全部不可处理时，详情可能为 `failed` 而列表仍为 `pending`。差异已记录在 Crash API 文档，后台首次发布后才会收敛。后续应在保持列表查询有界的前提下统一语义。
3. **Java 栈未明确显示已检查出的 mapping 缺失。** [NativeJavaStackBatch.java](../../../../backend/src/main/java/com/shanshui/apmserver/crash/internal/application/NativeJavaStackBatch.java) 在查找不到 mapping 时直接返回原结果，可能保留 `native_symbolication_deferred`，使页面无法区分尚未检查和确实缺少 mapping。后续应在完成查找后为适用栈表达缺失原因，并验证补传后可恢复。
4. **MCP 对 Native 摘要的校验不完整。** [server.ts](../../../../mcp-server/src/server.ts) 校验主栈状态，但未核对 `nativeFrameCounts` 与实际帧数，也未校验 Native 指纹版本。隔离协议探针确认伪造的帧计数和指纹版本可被接受；这不表示实际后端已产生此类数据。后续应拒绝不一致的后端响应并补充协议测试。
5. **Java 辅助线程来源字段校验不完整。** [前端校验](../../../../frontend/src/api/crashDiagnostics.ts)和 [MCP 校验](../../../../mcp-server/src/crashDiagnostics.ts) 对成功线程的来源 ID 和处理时间只检查非空，未校验 UUID 与时间格式。隔离探针确认错误格式可被接受；这不表示实际后端已产生此类数据。后续应与主 Java 栈来源校验保持一致。
6. **SO 上传成功后未自动展示后台进度。** [SymbolFilesView.vue](../../../../frontend/src/views/SymbolFilesView.vue) 展示批次保存和工作登记结果，但成功后未自动查询进度；用户仍可手动查看。该行为与增量规范中上传成功后展示批次结果及后台进度的场景存在差异，后续应补齐展示及相关测试。

## 正式规范目的说明差异

既有 [Native Crash 监控正式规范](../../../specs/native-crash-monitoring/spec.md) 的 `Purpose` 仍包含“符号文件上传与还原能力尚未提供”的阶段性描述，而当前工作树已经支持相应能力。本次同步按规则保留既有 `Purpose`，并在此记录该差异；该说明不应被理解为当前能力状态。

## 验证范围

- 归档前的 OpenSpec 严格校验通过；五份增量规范共涉及 32 项新增或修改的要求。124 个场景尚未完成逐条测试映射，不能据此宣称全部场景均已验证。
- 正式规范常规校验为 24 项通过；本次涉及的五份正式规范中，四份通过严格校验，`crash-frontend` 的既有“Crash 事件详情和堆栈展示”要求因正文超过 500 字触发严格校验警告，该要求在本次同步前后内容相同，未为消除警告而拆改。
- 归档前的后端定向测试共 60 项，其中 58 项通过、2 项因缺少相应外部样本而条件跳过；前端测试 193 项通过，MCP 测试 24 项通过。条件跳过不计为通过。
- 后端定向测试使用隔离数据库及生成的 ELF/调试符号夹具。真实设备、生产环境和本轮条件跳过的外部产物验收不由上述结果证明。
