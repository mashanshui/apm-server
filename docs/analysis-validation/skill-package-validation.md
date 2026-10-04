# Skill 内置 Worker 交付验收

## 2026-10-03 当前代码包

新版仍为 20 文件，增加 repair、删除 repositories 示例和旧执行器。当前安装、启动返回码、独立依赖与真实项目证据见 [当前代码验收](current-code-validation.md)。下面 2026-10-02 的固定提交配置和测试数仅为历史记录。

## 历史范围

2026-10-02 将 Python Worker 单份源码移入 `agent-skills/apm-crash-analyze/worker`，根开发入口保留相对链接。交付 ZIP 无链接且只有当前宿主流程的 20 个公开文件，含忽略本地虚拟环境及配置的 .gitignore。Python 共用材料清理模块不再依赖旧 OpenCode/Docker 执行器。

当前宿主仍负责推理，平台身份、固定提交材料、引用核验、私有恢复和停止门禁保持原实现。此次只验证工具交付，不领取新任务、不调用模型或重复验收设备样本。

## 自动化验证

- Python 全量 98 项通过，包含原 92 项和六项新增交付检查。
- 额外合成秘密文件、虚拟环境、旧执行器及测试数据均被打包列表排除。
- 文件及父目录链接、缺失必需文件使打包失败；失败前不生成残缺输出。
- 启动器保持当前目录、参数列表、环境凭据与非零退出码，空格、中文和 Shell 字符不被解释执行。独立安装在含合成 .env 的调用目录运行，未自动加载其中变量；非法任务 UUID 返回 2。
- 缺少 uv 返回明确错误；不执行自动安装脚本。
- Skill 结构与 OpenSpec 严格校验通过。
- 54 份相关 Markdown 的 485 个本地链接有效，差异检查通过。

## 独立安装

将 ZIP 解压到仓库外的临时中文空格目录，通过系统 Python 3.9.6 启动脚本。uv 0.12.21 选择本机已有 CPython 3.12.14，为该安装目录新建独立虚拟环境并安装 12 个锁定生产包；help 返回 0，未配置环境时 doctor 返回 1 且仅显示两项 false。运行时导入实际来自解压目录，未加载历史 runner，未安装 pytest。

自动下载 Python 的首次尝试耗时较长后终止，本轮未验证下载成功；已有解释器选择及独立依赖安装已通过。测试复用了 uv 缓存，没有依赖原仓库虚拟环境。帮助与诊断未调用平台分析或模型。

支持 macOS/Linux；Windows 需 WSL，未进行其他系统或其他 Agent 的实际任务验收。首次安装需联网且目录可写。共享 uv 缓存可以复用，但安装环境不依赖仓库 `.venv`。

## 文档归属

同步 [Skill 安装](../../agent-skills/apm-crash-analyze/README.md)、[交付入口](../../agent-skills/README.md)、[Worker 说明](../../agent-skills/apm-crash-analyze/worker/README.md)、平台知识库及后端/前端已有 Worker 导航。API、客户端事件和 MCP 契约没有变化。既有 [宿主分析验收](host-analysis-validation.md) 和历史质量结果保持原边界。

## 2026-10-03 本地配置交付增量

当前 ZIP 改为 23 个公开文件：新增配置模块、Skill 根 .gitignore 及打包时生成的空 config.local.json，不读取磁盘实际配置。用户填写后须权限 600，首次缺少配置时先提示，不领取。升级排除配置的解压流程已在独立中文空格目录实际验证；配置与后台看护的受控 HTTP、Python 130 项结果见[增量验收](current-code-validation.md#skill-本地配置增量验收)。此前文件数、doctor 存在性检查和环境-only 描述是当时历史，不代表当前安装配置。
