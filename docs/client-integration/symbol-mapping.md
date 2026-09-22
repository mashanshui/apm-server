# Android 符号表网页上传与生效语义

首版由网页管理员上传 Android 发布构建产生的 `mapping.txt`，客户端 SDK 不自动上传，也不需要在事件请求中携带 mapping 内容。服务端使用事件里的 `appId + buildId` 选择 mapping，因此客户端必须在 Crash 事件和卡顿 manifest 中稳定保存同次构建的 `buildId`。

网页上传步骤、请求字段、错误码和替换规则见[Android 符号表管理 API](../api/symbol-api.md)。上传前应确认 mapping 与发布 APK/AAB 使用同一次 R8/ProGuard 构建；服务端会用官方 R8 Retrace 校验格式，不能用任意文本或只包含几行示例的文件代替。

Crash 事件保存原始脱敏异常链。管理员上传或替换 mapping 后，打开任意旧 Crash 详情都会按当前版本重新 Retrace，页面不保存还原后的文本。卡顿 mapping 在 ZIP 上传解析时使用；替换不会重算已经保存的卡顿事件，只影响之后上传的 ZIP。

如果 mapping 暂时不可用，Crash 详情仍可查看原始异常链；卡顿上传会按已有的可选 mapping 规则继续或返回可重试的存储故障。客户端不应把网页上传 API 当作 Android 上报 API，也不应把 appKey 放入网页 URL、文件内容或日志。
