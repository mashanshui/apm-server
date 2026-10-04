# Agent Skill 交付

当前提供 [apm-crash-analyze](apm-crash-analyze/README.md)：宿主 Agent 直接分析已有 JVM Crash 任务，宿主自身读取、编辑和验证当前代码，随附 Python 仅接入任务、冻结证据、租约及报告回传。

## 生成安装包

在仓库根目录运行：

```sh
# 仅打包明确列出的公开文件，默认产物目录由 Git 忽略。
python3 scripts/package-agent-skill.py
```

生成 `build/agent-skills/apm-crash-analyze.zip`，解压后将完整目录放到宿主实际 Skill 安装目录。打包脚本需要 Python 3.9+。ZIP 包含 Skill、统一启动脚本、生产源码、锁定依赖；另外固定生成空的 config.local.json 并收录 Git 忽略规则，不读取用户配置；不包含本地凭据、虚拟环境、开发测试、旧执行器或真实验收数据。

## 开发位置

Worker 唯一源码位于 `apm-crash-analyze/worker`。根 `agent-worker` 是指向该目录的相对链接，原本地开发命令仍可使用；项目发现位置 `.agents/skills/apm-crash-analyze` 指向本 Skill。ZIP 不含链接，独立安装不依赖这些开发入口。

Worker 测试从 `agent-skills/apm-crash-analyze/worker` 执行 `uv run --locked pytest`。导出包只安装生产依赖，测试留在开发仓库，历史执行器说明仅作为验收档案保留。不要把开发目录递归压缩作为发布包，使用上述打包命令。

独立安装与启动验证见 [交付验收](../docs/analysis-validation/skill-package-validation.md)。其他宿主实际任务兼容、质量及生产能力需要分别验证。

当前最小配置及升级时保留配置步骤见[安装使用](apm-crash-analyze/README.md#最小配置)。首次配置不足时工具会提示文件位置，且不领取任务；doctor 不输出秘密。
