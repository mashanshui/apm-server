# 实施前基线

记录时间：2026-08-27（Asia/Shanghai）。

## 工作树边界

实施前工作树已经包含登录、项目管理、Crash 查询与本地开发脚本等未提交改动。本变更只新增服务端卡顿监控、通用接收编排、ClickHouse 资源和对应 OpenSpec/知识库内容；不清理、不覆盖这些既有改动，也不修改 Android、btrace、Vue 或 Grafana 资源。

## JVM 测试基线

计划执行 `./gradlew.bat test`。当前环境的 Gradle Wrapper 在初始化发行版时因 `E:\AndroidSDK\.gradle\wrapper\dists\gradle-9.5.1-bin\...\gradle-9.5.1-bin.zip.lck` 无法打开而失败；直接调用已展开的 Gradle 发行版又因沙箱无法加载 `native-platform.dll` 失败。因此本文件记录的是环境阻断，不把本次实现前的测试结果误报为通过。代码修改后将再次尝试测试，并明确区分环境阻断与测试结果。

## 现有行为边界

- `/ingest/v1/batches` 使用项目 Key、JSON/JSON+gzip、部分接受、`projectId + eventId` 去重和存储不可用时的可重试 503。
- 现有事件类型为 `app_start` 和 JVM fatal `crash`；既有 Crash 响应、认证、项目成员授权和查询参数约束保持兼容。
- 默认生产存储配置为 ClickHouse，测试配置使用内存仓库；新增卡顿能力也保留内存实现以便固定数据集和契约测试。
