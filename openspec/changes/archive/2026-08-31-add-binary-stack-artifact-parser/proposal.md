## Why

现有卡顿接收协议要求客户端把采样堆栈展开为 JSON `stackDictionary`，大型或高唯一度堆栈会受到单事件体积限制，也无法直接复用 btrace 已生成并校验的 `.rheatrace.zip` 二进制产物。服务端需要一个独立、受项目上报 Key 保护的上传解析入口，在不改变现有 JSON 批量事件契约的前提下，将原始产物解析为可查询或展示的完整堆栈报告。

## What Changes

- 新增版本化原始二进制堆栈产物解析 API，以 `application/vnd.shanshui.rheatrace+zip` 请求体接收 `.rheatrace.zip` 并返回 `RHEA_STACK_REPORT` JSON。
- 复用 `rhea-trace-processor` 的 `StackParser`，由其负责 ZIP、manifest、SHA-256、Sampling v5、sampling mapping、时间窗口和耗时证据解析。
- 在业务层补充项目 Key 鉴权、压缩文件大小限制、空文件检查、稳定错误码和解析报告结构校验。
- 支持可选 `X-Mapping-Id` 请求头，只从项目隔离的本地 mapping 根目录解析 ProGuard/R8 mapping，禁止客户端提供任意文件路径。
- 保持 `/ingest/v1/batches`、现有 JSON/gzip 卡顿事件和 ClickHouse 卡顿统计契约不变；本次解析结果同步返回，不自动写入现有 `jank` 事件表。
- 同步发布中文服务端 API、Android 客户端接入、知识库、配置和验证边界说明。

## Capabilities

### New Capabilities

- `binary-stack-artifact-parsing`: 定义项目隔离的 btrace 二进制堆栈产物上传、可选 mapping 选择、解析报告、大小限制和稳定错误语义。

### Modified Capabilities

无。

## Impact

- 新增 `rhea-trace-processor` Maven 依赖及本地/制品仓库获取要求。
- 新增上传控制器、解析服务、mapping 解析器、配置项、异常类型和接口测试。
- 新增原始请求体大小限制；解析过程会使用临时文件、CPU 和堆内存，但不引入 multipart、对象存储、任务队列或异步持久化。
- 影响 `docs/api/`、`docs/client-integration/` 和 `docs/knowledge-base/` 中的接收协议、安全、测试、部署与当前能力说明。
