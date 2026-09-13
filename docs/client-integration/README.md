# 客户端接入文档

本目录统一保存面向 Android SDK、客户端开发和联调人员的接入文档。内容覆盖事件构造、客户端持久化、批量上传、重试和联调边界，不包含移动端 UI。

## 文档目录

- [Android 卡顿监控上传接入](jank-monitoring.md)
- [Android 卡顿压缩包上传接入](stack-artifact-upload.md)
- [Android 卡顿压缩包 manifest v3 契约](jank-artifact-manifest.md)
- [Android JVM Crash 上传接入](crash-client-integration.md)
- [Android 内存指标上传接入](memory-metrics.md)
- [Android 内存泄漏报告上传接入](memory-leak-reports.md)
- [Android/processor 后续改造清单](android-processor-follow-up-checklist.md)

服务端请求/响应、查询、统计和错误语义统一见[服务端 API 文档](../api/README.md)。

## 应用 Key 获取与绑定

联调前先在网页创建应用并填写唯一的全小写 Android application ID；服务端自动生成 UUID v4 `appId`，再由应用 `OWNER`/`ADMIN` 在设置页查看完整 `X-App-Key`。appKey 与包名永久绑定、不过期且不可轮换；JSON v2 事件和卡顿 manifest v3 都必须显式携带相同的 `packageName`。完整 appKey 不得进入日志、URL、Web Storage、截图或版本库。
