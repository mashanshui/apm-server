# Android 符号表网页上传与生效语义

由网页管理员上传 Android 发布构建产生的 `mapping.txt`，客户端 SDK 不自动上传，也不在事件请求中携带 mapping 内容。服务端按认证应用与事件 `buildId` 选择 mapping，因此客户端必须在 Crash 事件和卡顿 manifest 中稳定保存同次构建的 `buildId`。

发布时将 APK、`build-info.json`、`buildId` 和 mapping 一起归档；当前自动导出范围与核对方法见 [Android 构建信息导出](../../android/docs/knowledge-base/11-构建测试与本地发布.md#打包时导出-buildid)。上传前确认 mapping 来自同次 R8/ProGuard 构建；AAB 的构建信息导出按该页边界另行核对。网页步骤、请求字段、错误码与替换规则统一见[符号表 API](../api/symbol-api.md)。

Crash 上报保存 pending 原始脱敏异常链和事实后即可确认，详情不执行实时还原。上传或替换成功只表示文件已持久化且构建工作已登记，通过独立进度接口查看排队、运行、等待 mapping、退避及当前空闲；不必因后台等待或失败重新生成事件。

格式合法不能证明 mapping 与 APK 匹配。Crash 首次成功后的还原文本固定，错误 mapping 一旦得到成功结果，替换、删除和同构建重传均不会重算该事件。后台状态、文件租约和审计细节见[符号表实现](../../backend/docs/knowledge-base/symbol-mapping.md)，不据网页上传成功推断历史事件已重算。

当前材料为原始 raw_chain 或成功后的 retrace_text，不提供双份堆栈。卡顿仍在 ZIP 上传解析时使用当前 mapping；删除后按既有无 mapping 可选解析，既存卡顿事件和指纹不回算，也不进入 JVM Crash 后台队列。

尚未成功的事件在 mapping 暂时不可用时仍可查看原始链；成功事件继续查看固定还原文本；卡顿上传会按已有的可选 mapping 规则继续或返回可重试的存储故障。客户端不应把网页上传 API 当作 Android 上报 API，也不应把 appKey 放入网页 URL、文件内容或日志。
