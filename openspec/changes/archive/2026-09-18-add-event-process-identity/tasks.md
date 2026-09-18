## 1. 协议与依赖核对

- [x] 1.1 核对三个入口及所有事件类型的身份映射、错误语义和现有内容冲突检测，交付入口到存储／详情的字段清单并与设计逐项对应。
- [x] 1.2 用包含 processId 的真实 ZIP fixture 核对 processor 是否接受并可供服务端读取该字段；以解析测试证据确认，外部依赖不满足时记录阻塞而不绕过。当前 `rhea-trace-processor:1.0.2` 已接受客户端主线程-only v3 ZIP 的 UUID v4 `processId` 与 `threadScope=main`，并可供服务端读取该字段。
- [x] 1.3 更新 JSON schema、批量校验、ZIP manifest 校验及内存报告 metadata 白名单；以合法 UUID、缺失、空值、数值 PID、非 v4 UUID 用例验证各入口错误边界。

## 2. 后端传递与存储

- [x] 2.1 为公共信封、领域命令、元数据、清洗与归一化链路增加 processId；用覆盖全部当前事件类型的映射测试验证 UUID 原值及主进程相等 ID 不丢失。
- [x] 2.2 新增版本化 ClickHouse 迁移并更新六类原始记录的读写与内存仓储；验证新记录往返一致、旧记录缺失值可读，迁移不改已发布脚本及去重键。
- [x] 2.3 将 processId 加入 Crash／卡顿详情响应；通过接口测试验证真实字段回传、历史缺失值及应用权限隔离。
- [x] 2.4 覆盖跨启动补传、重复上报及已有内容冲突规则；断言原始身份不被上传时身份替换，重复次数和现有统计口径不变。

## 3. 前端展示

- [x] 3.1 更新详情类型、模拟数据及两个详情页，分别展示设备／启动／进程 ID 并保留已有进程名称；通过组件测试验证标签、相同 ID 和缺失占位。
- [x] 3.2 增加完整值复制及成功／失败反馈；测试剪贴板成功、拒绝和空值场景，并确认没有新增关联列表、路由或 ID 跳转。

## 4. 文档同步

- [x] 4.1 同步根知识库的事件模型、当前边界和相关索引；说明本版仅身份基础与详情展示，通过本地 Markdown 链接检查验证。
- [x] 4.2 同步后端知识库的接收、存储及测试主题，前端知识库的详情展示和测试主题；核对与实际实现一致并区分自动化和外部验证。
- [x] 4.3 同步 Crash、卡顿、内存指标、内存报告 API 及客户端接入、manifest 文档和安全示例；核对三个入口均声明 processId 必填及永久错误，保留原有 sessionId 可选边界。
- [x] 4.4 更新固定数据及客户端交付清单，写明安装持久化、主进程复用启动 UUID、子进程独立 UUID、备份复制风险及补传身份保留；以文档审阅确认未宣称 SDK 已实现。

## 5. 综合验证与交付

- [x] 5.1 运行 `./backend/gradlew.bat -p backend test`，在 frontend 运行 `npm test` 和 `npm run typecheck`；记录实际命令与结果，定位并修复本次变更导致的失败。
- [x] 5.2 在可用的测试 ClickHouse 执行迁移与三个入口固定数据联调，核对进程字段存储和两个详情响应；环境不可用时明确保留待验收状态。
- [x] 5.3 检查两个详情页布局和复制反馈并保存截图，明确模拟接口或真实后端证据；不将构建成功当作浏览器验收。
- [x] 5.4 运行 `openspec validate add-event-process-identity --strict`，核对规格场景与证据，分别报告五类文档同步文件及 Android／processor 外部交付边界，不自动归档。

> 5.2 已完成：使用客户端实际产物 `F:/AndroidStudioProjects/btrace/btrace-android/build/device-zip-demo-jank-3539183284831898/demo-jank-3539183284831898.rheajank.zip`，在包含 `rhea-trace-processor:1.0.2` 的本地 Boot JAR 中直连云端 PostgreSQL/ClickHouse 完成迁移和三入口联调。六张承载表均确认存在 `process_id`；既有 JSON 批次、内存报告和 Crash 详情证据保持有效；该 ZIP 首次写入后同字节重试返回 `duplicate`，`apm_event_raw`、`apm_jank_event` 各有 1 行，`apm_jank_detail` 有 1 行，Jank 与 Crash 详情均 HTTP 200 且返回原始 `processId`。证据见后端[测试与质量保障](../../../backend/docs/knowledge-base/06-测试与质量保障.md#2026-09-15-云端-clickhouse-进程身份验收)和平台[测试与质量保障](../../../docs/knowledge-base/08-测试与质量保障.md#2026-09-15-云端-clickhouse-进程身份验收)。
