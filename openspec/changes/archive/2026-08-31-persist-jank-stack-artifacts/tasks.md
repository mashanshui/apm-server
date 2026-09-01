## 1. Processor 契约与真实夹具

- [x] 1.1 确认并记录 `rhea-trace-processor:1.0.0` 的严格 v2 manifest 契约：processor 读取必填的 `attemptedSampleCount`，服务端映射、统计、指纹和存储均忽略该值，并由服务端构建实际解析验证
- [x] 1.2 将服务端 processor 依赖固定到已验证版本，保持 fat JAR 非传递依赖边界，并校验服务端实际解析到的 JAR 哈希和公共 `parseWithMappingResolver` API
- [x] 1.3 基于 `F:\AndroidStudioProjects\btrace\btrace-android\build\test-stack\demo-jank-2070003140242350.rheajank.zip` 生成仓库测试夹具，验证 ZIP 固定条目、manifest 字段、文件大小和 SHA-256
- [x] 1.4 使用更新后的 processor 解析真实夹具，固定断言主线程 454 条有效 segment、`expectedSampleCount=460`、`parsedSampleCount=454`、`missingSampleCount=6`，并证明顶层 `recordCount` 和其他线程不会改变该口径

## 2. 卡顿领域模型与存储字段

- [x] 2.1 将卡顿载荷、分析、查询响应和固定数据集的质量字段改为 `expectedSampleCount`、`parsedSampleCount`、`missingSampleCount`，删除 attempted/successful/dropped 的对外语义和旧 JSON jank 输入 Schema
- [x] 2.2 新增版本化 ClickHouse 脚本，为 `apm_jank_event` 增加 parsed/missing 质量列并保留旧列用于回滚；同步更新 schema 结构测试和本地初始化说明
- [x] 2.3 更新 ClickHouse 写入、读取和内存仓库适配，使新质量字段、归一化载荷、分析摘要和详情能够完整往返，且现有 Issue 与指标聚合公式不受影响
- [x] 2.4 更新 PC 控制台卡顿详情类型、展示标签和测试，从“尝试/成功/丢弃”调整为“期望/已解析/缺失”

## 3. ZIP 报告归一化与指纹

- [x] 3.1 新增卡顿产物报告映射器，严格校验 `RHEA_STACK_REPORT`、v2 `sourceManifest` 核心字段、消息区间、processId 和唯一目标主线程，并忽略 attempted 字段
- [x] 3.2 从目标主线程有效 segments 构建稳定 stackId 字典、采样时间片和调用树，保留 processor 的估算覆盖及 warnings，并对异常偏移、空栈和证据规模超限返回稳定 422 错误
- [x] 3.3 计算 messageDuration、expected/parsed/missing 质量字段和 `jank-artifact-v2` 算法标识，复用现有文本脱敏、匿名设备保护和服务端指纹逻辑生成 `StoredEvent`
- [x] 3.4 为映射器增加单元测试，覆盖多线程排除、所有有效事件类型计数、parsed 大于 expected、重复栈去重、主线程缺失、空栈、纳秒整数边界和不静默截断

## 4. 上传接口与幂等落库

- [x] 4.1 直接修改 `/ingest/v1/stack-artifacts:parse`：媒体类型改为 `application/vnd.shanshui.rheajank+zip`，删除 `X-Mapping-Id` 和旧报告响应，返回仅含 success/status 的新 DTO
- [x] 4.2 将解析服务改为在并发许可内调用一次 `parseWithMappingResolver`，使用认证项目和 source manifest buildId 安全选择可选 mapping；mapping 缺失继续未解混淆解析，路径越界仍拒绝
- [x] 4.3 将归一化后的单个卡顿事件交给 `EventRepository.append`，把 accepted/duplicate 映射为最小成功响应，并验证重复请求能复用现有部分写入修复且不会放大统计
- [x] 4.4 在 `/ingest/v1/batches` 对 `eventType=jank` 返回稳定的事件级永久传输方式错误，同时保持 Crash、app_start、FPS 和前台挂起汇总的既有部分接受行为
- [x] 4.5 完善错误映射和指标，覆盖 401、413、415、422、解析繁忙 503、存储故障 503，并保证响应和日志不包含项目 Key、ZIP、完整堆栈或服务端路径

## 5. 接口、客户端和知识库同步

- [x] 5.1 更新 `docs/api/stack-artifact-api.md`、`docs/api/jank-server-api.md` 和 API 目录，发布新媒体类型、当前严格 manifest 字段、最小响应、幂等、质量字段及错误语义
- [x] 5.2 更新 `docs/client-integration/jank-artifact-manifest.md`、`stack-artifact-upload.md`、`jank-monitoring.md` 和客户端目录，明确 attempted 仍需上传但服务端忽略，删除旧 JSON jank 构造步骤和旧解析报告处理流程
- [x] 5.3 同步知识库当前实现、事件存储、上报可靠性、安全、部署、测试和路线图，明确代码实现、自动化验证、真实样例回归与生产容量边界
- [x] 5.4 更新固定数据集、示例和前端知识库中的采样质量口径，检查 API 与客户端文档相互链接及所有受影响 Markdown 本地链接

## 6. 自动化与真实链路验证

- [x] 6.1 增加接口集成测试，覆盖真实夹具首次 `accepted`、重复 `duplicate`、事件详情读回、attempted 不参与质量计算、旧媒体类型/v1 拒绝、空请求、超限和并发繁忙
- [x] 6.2 增加 mapping 存在、缺失、非法 buildId、跨项目和符号链接逃逸测试，确认缺失 mapping 可落库且其他路径不会泄露
- [x] 6.3 扩展 ClickHouse 仓库测试，验证原始事实、卡顿事实、详情三阶段写入、新质量列、eventId 去重和部分写入修复
- [x] 6.4 运行后端全部测试和构建；运行前端单元测试、类型检查和构建；记录命令、结果与未覆盖的生产容量边界
- [x] 6.5 使用用户提供的原始样例与更新后夹具做来源对照回归，运行 OpenSpec strict 校验、`git diff --check` 和文档断链检查，确认仓库没有临时解析产物
