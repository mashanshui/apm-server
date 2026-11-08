# Android 客户端并入平台仓库

## Why

Android Performance 与 apm-server 分属两个仓库，共同协议、样例、验收脚本和 OpenSpec 依赖本机相邻检出目录，跨端修改需要同时维护两套入口。将客户端及其 Git 历史纳入平台仓库，可以统一变更与文档管理，并保留原有工程独立构建和历史追溯能力。

## What Changes

- 将当前本地 Performance 分支及全部可达提交完整导入 `android/`，使用不带 `--squash` 的 Git subtree，保留原 commit ID、作者、时间和说明；原 Android 检出及其本地配置保留，后续开发以合仓后的工程为入口。
- **BREAKING（开发入口）**：当前工作树统一使用根 OpenSpec 与根 `.agents/skills/`；客户端三份正式规格移入根 `openspec/specs/`，两个历史归档完整保留，同名内存峰值归档的客户端版本改为 `2026-11-08-report-memory-runtime-peaks-android`。统一后业务正式规格基线为 23 份，历史归档为 21 份；本迁移变更另计。
- 以仓库内共享 fixture 替代客户端测试中的相邻仓库路径，调整联合验收脚本默认客户端位置、归档引用、文档导航和根/Android 协作规则。
- Android 保留自己的 Gradle Wrapper、模块、包名、Maven 坐标和独立消费者；保留后端、前端、MCP 的构建及部署边界。Rhea/KOOM 源码不并入，已有外部依赖按当前坐标获取和核验。
- Android 使用平台当前 Crash 分析技能，核验合仓 Git 根定位、带 `android/` 前缀的源码引用与 Android 构建工作目录；清理客户端旧技能副本和旧导出包。
- 示例签名改为读取未跟踪的本地配置，保持当前测试证书及安装连续性；保留原签名文件的本地副本，不生成或替换密钥。原始 Git 历史中的已有测试签名资产仍保留，不将本次迁移描述为历史敏感信息清理。
- 验收 Git 历史与文件映射、单一 OpenSpec 入口、共同契约测试、Android 构建与独立消费者、分析工具路径以及部署上下文；同步受影响知识库和接入资料，既有三项旧归档校验失败单独记录。
- 实施完整 check 时发现原内存队列比较器调用要求 API 24，与模块 minSdk 21 冲突；用户已于本次实施明确授权仅将该调用替换为交换比较参数的兼容实现，保持队列选择语义并重新验证。

## Capabilities

### New Capabilities

无。本次是仓库组织、构建入口、开发工具和文档迁移，`.openspec.yaml` 设置 `skip_specs: true`，不创建业务行为规格增量。

### Modified Capabilities

无。`modular-sdk-composition`、`modular-sdk-distribution`、`memory-runtime-peaks` 仅从客户端搬入；`current-code-crash-fix` 等现有契约保持不变。源码引用随文件位置使用新的相对路径，证据版本 3、报告版本 4 与现有 HTTP/SDK 行为继续生效。

## Impact

- 代码与配置：新增 `android/` 独立工程，调整其共同 fixture 测试、签名配置和本地开发入口；调整 `scripts/verify-memory-runtime-peaks.py` 的默认路径；根 OpenSpec、技能、`AGENTS.md`、忽略规则和必要的换行规则同步。
- 文档：根知识库的架构/状态/开发入口，Android 内部知识库，客户端接入与联合验收资料，以及含旧跨仓库引用的归档。后端知识库同步分析工具路径与验证说明；前端知识库、API 文档按引用影响检查，不改接口字段或页面行为。
- Git：以实施时再次核对的本地 HEAD 为导入来源。规划基线为服务端 `b6698b2035101ed9f0a037489fc168ee6ae649c6`（19 条可达提交）和客户端 `b6c4de327fd39c12b8e549e8d60b383512a2222a`（31 条）；两边相对本地记录的远端跟踪分支各领先 4 条，尚未刷新远端。subtree 导入会生成必要的本地合并提交；普通整理提交、推送、合并到主分支和发布另按用户指令执行。
- 边界：本轮只生成规划。实施不修改原 Android 检出，不升级依赖，不调整数据库或服务环境，不重新执行 80 分钟频率实验，也不以历史验收替代迁移后的路径和构建验证。详细取舍、回退及验收矩阵见 [设计](design.md)和[任务](tasks.md)。
