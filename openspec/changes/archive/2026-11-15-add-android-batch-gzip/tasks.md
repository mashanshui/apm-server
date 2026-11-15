# 实施任务

## 1. Android 共享批次编码与回归

- [x] 1.1 在 `performance-transport` 实现并由自建 `TransportSession` 安装目标批次 gzip 拦截器，按规范补充方法/变量注释；用 MockWebServer 验证准确路径、POST、JSON 类型、基础路径前缀及各类非目标请求原样通过。
- [x] 1.2 实现原始 1 MiB、压缩输出 2 MiB 的有界临时缓冲、完整 gzip 尾部和压缩后长度；测试小于 1 KiB、压缩膨胀、Unicode JSON、原长度头清理、缓冲上限和解压后逐字节一致，确认全部批次 gzip、没有阈值或回退。
- [x] 1.3 保留已有单独 gzip 请求且不二次包装，拒绝不支持的预编码；用传输测试验证编码/正文一致、外部客户端和 Retrofit 不被替换且关闭时不释放借用资源。
- [x] 1.4 将 gzip 准备 I/O 失败接入既有网络失败路径，加入受控成功/失败日志；故障注入验证失败发生于 HTTP 发出之前、日志含大小/耗时或失败阶段且不含凭据和正文，日志关闭时不输出可选明细。
- [x] 1.5 为真实 Crash/Metrics Retrofit API 增加 gzip 请求回归，覆盖 JVM/Native Crash、启动、FPS、内存峰值及全量/按需共享行为；前台挂起使用合成 JSON 验证传输与服务端接收，不新增采集上传链路；运行 `bash android/gradlew -p android :performance-transport:testDebugUnitTest :performance-crash:testDebugUnitTest :performance-metrics:testDebugUnitTest`，确认线上头和解码内容，而非仅 mock 发送函数。

## 2. 后端有界解码与协议回归

- [x] 2.1 在 `IngestController` 收紧全部 Content-Encoding 头的校验，保持现有鉴权和媒体类型边界，按规范补充注释/必要日志；测试无头、空白、identity、gzip、大小写/空白、未知子串、叠加与重复头，分别验证正常接收或不可重试 415。
- [x] 2.2 在现有线上与解压后受限流内完成 gzip EOF/CRC/尾部校验后再解析分派；新增完整 JSON 搭配损坏 CRC、错误长度或截断尾部的测试，确认 400 INVALID_BATCH 且仓库没有该批写入。
- [x] 2.3 补齐线上长度预检、未知长度实际读取上限、解压字节边界和合法 JSON 后超量空白测试，确认超限 413、普通 JSON 限制保持、默认 1 MiB/4 MiB 配置未扩大；验证无效 App Key 在正文读取前被拒绝。
- [x] 2.4 以有效合成事件验证 gzip 首次接受、重复确认、部分接受、内部非法 JSON和 400/413/415 不可重试语义；运行 `bash backend/gradlew -p backend test`，记录环境条件跳过，确认未将领域错误改为通用成功或普通 500。

## 3. 上传可靠性与跨端集成验证

- [x] 3.1 扩展 Crash/FPS/内存上传器故障回归，覆盖压缩准备失败、网络异常、503、不完整业务确认和服务端接受后响应丢失；确认保留原 eventId/载荷/身份、按原退避重试、收到合法 accepted/duplicate 后才删除，413/415 沿用永久错误策略且不回退普通 JSON。
- [x] 3.2 在临时端口与隔离存储启动真实 Servlet，用 SDK 自建会话发出的合成 gzip 批次验证服务端接收和重复去重；复用现有 Nginx 配置搭建隔离代理验证固定长度与未知长度请求、损坏 gzip 和 413，保留可复用自动化或测试夹具，不改动现有本地/云端数据或部署。
- [x] 3.3 对合成小批次、典型重复诊断及接近预算的批次记录原始/gzip 字节数、耗时与最高预算膨胀导致 413 的边界；结果保留在实施验证输出，不把测量写成固定压缩率或真机功耗承诺。
- [x] 3.4 运行受影响 Android 测试与 APK 构建 `bash android/gradlew -p android :performance-transport:testDebugUnitTest :performance-crash:testDebugUnitTest :performance-metrics:testDebugUnitTest :performance-jank:testDebugUnitTest :performance-leak:testDebugUnitTest :app:assembleDebug`；核对各增量规格场景的测试/证据映射、`git diff --check` 与 `openspec validate add-android-batch-gzip --strict`，报告未执行项和外部环境限制。

## 后续工作流

- 本任务表不授权部署、发布或归档；实施完成后进入显式验证/归档流程。
- 普通实施不自动更新知识库。归档时在选定主规格同步完成后、移动变更目录前调用并等待 knowledge-sync，核对根/后端/Android 知识库、API 与客户端文档，明确前端不受影响的依据并检查本地链接；未完成则保留变更目录。
