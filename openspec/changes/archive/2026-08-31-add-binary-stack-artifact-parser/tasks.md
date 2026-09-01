## 1. 实施门禁与依赖

- [x] 1.1 取得用户对原始二进制请求体、API 路径、同步解析边界、mapping 目录策略和本地 Maven 构件依赖的明确实施确认
- [x] 1.2 在 Gradle 中加入 `mavenLocal()` 和固定版本 `rhea-trace-processor` 依赖，验证构件包含 `StackParser` 公共接口
- [x] 1.3 增加堆栈解析配置属性、64 MiB 原始请求体限制和默认并发上限，并覆盖配置绑定测试

## 2. 上传解析实现

- [x] 2.1 实现项目隔离的 mappingId 校验和 `<mapping-root>/<projectId>/<mappingId>.txt` 安全解析，不泄露服务端路径
- [x] 2.2 实现基于单例 `StackParser` 的同步解析服务、并发信号量、输入流关闭和报告 Schema/类型校验
- [x] 2.3 新增 `POST /ingest/v1/stack-artifacts:parse` 原始二进制控制器，以版本化媒体类型和可选 `X-Mapping-Id` 接收受限请求流，复用项目 Key 鉴权并返回项目、mapping 状态和原始报告
- [x] 2.4 增加空请求体、媒体类型错误、`Content-Length` 或流式读取超限、mapping 非法/缺失/不匹配、无效产物和解析繁忙的稳定异常与 HTTP 错误映射

## 3. 自动化验证

- [x] 3.1 增加解析服务单元测试，覆盖合法报告、无 mapping、应用 mapping、报告结构错误、解析异常和并发释放
- [x] 3.2 增加 MockMvc 接口测试，覆盖鉴权、原始二进制媒体类型、`X-Mapping-Id`、成功响应、400、413、415、422 和 503 语义
- [x] 3.3 使用 btrace 的真实 `.rheatrace.zip` 样本执行实际 `StackAnalyzer` 回归，确认 `RHEA_STACK_REPORT`、`threads[].segments` 和 `threads[].callTree`

## 4. 文档同步

- [x] 4.1 新增中文二进制堆栈解析 API 文档，并从 `docs/api/README.md` 建立入口
- [x] 4.2 更新 Android 客户端卡顿接入文档和目录，说明 JSON 事件与 `.rheatrace.zip` 解析入口的适用边界、原始二进制请求示例、mapping 和错误处理
- [x] 4.3 同步知识库当前实现、总体架构、事件与存储、上报可靠性、安全、部署、测试、路线图、决策和待确认事项

## 5. 最终校验

- [x] 5.1 运行新增定向测试、完整 `gradlew.bat test` 和 `clean build`，区分真实 processor 回归、JVM 自动化与未完成生产容量验证
- [x] 5.2 运行 `openspec validate add-binary-stack-artifact-parser --strict`、`git diff --check` 和 Markdown 本地链接检查，并记录最终实现边界
