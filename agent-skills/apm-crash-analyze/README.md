# apm-crash-analyze 安装与使用

Skill 已包含 Python 工具、锁定依赖和启动器。当前宿主模型分析当前 Git 工作区（包含未提交内容）；用户明确要求修复时由宿主自身工具完成编辑和验证。网页提供单事件 taskId，不要求构建源码登记。

## 安装

解压完整 `apm-crash-analyze` 文件夹到宿主实际 Skill 目录。项目安装可放在目标项目 `.agents/skills/apm-crash-analyze`；其他宿主按其加载规则安装或明确加载 SKILL.md。仅复制 ZIP 而未解压不能使用。同名目录先核对，避免覆盖配置。

目录包含 SKILL.md、README.md、agents/openai.yaml、scripts/apm_analysis.py 与 worker/ 下的生产 Python 源码、pyproject.toml、uv.lock。无需另外检出服务端或 Worker。

支持 macOS/Linux，Windows 使用 WSL。需要 python3（3.9+）、uv 和 Git；首次调用由 uv 准备 Python 3.12 和锁定生产依赖，需要联网/缓存及安装目录可写。不要复制旧机器的 .venv。

```sh
# 替换实际安装路径；启动器保留宿主当前工作目录。
python3 /实际安装位置/apm-crash-analyze/scripts/apm_analysis.py --help
python3 /实际安装位置/apm-crash-analyze/scripts/apm_analysis.py doctor
```

## 最小配置

APM 后端需运行并显式启用分析功能（部署默认关闭）。管理员创建应用 Worker 凭据，OWNER/ADMIN/DEVELOPER 在 JVM fatal 事件详情创建任务，VIEWER 只读。

默认配置位于本 Skill 根目录 `config.local.json`，安装包提供两个空字符串。请由用户在本地编辑：

```json
{
  "platformUrl": "http://127.0.0.1:8080",
  "workerCredential": "<应用设置创建的完整 Worker 凭据>"
}
```

上例凭据是占位符，不能直接使用。包含凭据的配置须归当前用户所有且仅该用户可访问，在 macOS/Linux/WSL 执行 `chmod 600 /实际安装位置/apm-crash-analyze/config.local.json`。不把凭据发到聊天或加入源码。Python 直接读取配置，宿主无需读取文件内容；配置不进入宿主材料、日志、报告或导出包。

每次工具调用都先检查配置。空/缺失配置返回 CONFIG_MISSING，提示文件位置和缺少字段；无效 JSON、字段类型、地址、凭据格式及文件权限分别提示，失败前不连接平台、不领取任务、不采集源码。doctor 同时检查本地格式/权限，成功只表示本地配置有效，不证明平台权限或凭据尚未撤销。

保留环境入口：仅当 `APM_ANALYSIS_URL` 和 `APM_WORKER_CREDENTIAL` 两项同时提供时，整组覆盖文件；只提供一项会报配置不完整，不拼接其他来源。doctor 显示 file/environment 配置来源，不输出凭据。启动器不自动加载项目 .env。后台看护沿用前台此次解析的一组配置，不因用户编辑文件更换身份。

私有状态默认 `~/.local/state/apm-analysis`，更改时在所有动作使用同一 `--state-dir`。无需仓库 JSON、origin、提交 SHA、混淆声明、模型选择、独立模型密钥、OpenCode 或 Docker。项目从宿主当前目录定位，与 Skill 放在哪无关；其他项目须明确指定绝对路径。

## 更新时保留配置

更新前备份已有配置，在 Skill 父目录解压新版时排除本地配置，例如：

```sh
# 更新已有安装；排除配置文件，其余公开源码可覆盖。
unzip -o /下载位置/apm-crash-analyze.zip \
  -d /目标项目/.agents/skills -x apm-crash-analyze/config.local.json
```

此命令用于已有安装；首次安装正常解压以获得空配置。升级到 0.4.0 时清理原版本的 `worker/src/apm_agent_worker/material.py` 和 `repair.py`，解压覆盖不会自动删除旧文件。直接覆盖整个目录会覆盖配置，ZIP 本身无法替任意解压软件保护旧文件。更新不会修改 Python 私有状态目录；使用新的调用前需先结束旧分析并核验停止。

## 自然语言调用

```text
使用 apm-crash-analyze 分析任务 <网页 taskId>，只做分析。
```

```text
使用 apm-crash-analyze 分析并修复任务 <网页 taskId>。
项目是 /明确的/绝对项目路径，请完成相关编译和测试。
```

同一已领取只读任务不能升级为修复，需结束本次并完成停止核验后，在网页显式新尝试。工具流程见 [SKILL.md](SKILL.md) 和 [Worker 说明](worker/README.md)。

## 恢复与结果

报告基于当前代码，无法证明历史 APK 的完整源码。宿主直接搜索/读取/修改当前代码，Python 不遍历或复制全仓库；新报告版本 4 绑定 runId，源码片段仅在提交时核对当前位置，修改及测试均为宿主报告。分析、代码修改、宿主测试和整体停止分别显示。SUCCEEDED 只表示报告保存，不代表问题已解决。验证为 HOST_REPORTED，未知模型与用量保持未知。

准备后十分钟期限，20 秒看护、90 秒租约；超时验证如实记录未完成。宿主保留原改动与暂存区、自行管理编辑冲突与恢复；Python 不生成补丁日志或提供回滚。报告最多一 MiB、修改文件列表最多 20 条，这是回传上限。RESULT_PENDING 重传原结果字节。任务工具关闭不证明宿主停止，管理员仍须实际核验 UNKNOWN。

安装检查与真实宿主修复验收分别记录，其他宿主和生产质量需要单独证据。开发仓库的验收文档不进入安装包。

## 0.4.0 升级边界

服务器需支持版本 4 报告和仅 requestId 的领取协议；与 Skill 一起更新。旧活动/待回传任务先用原工具完成或停止核验，新入口不静默升级。已完成报告保持历史原值。新工具没有 search/read/apply/revert、固定快照、全仓库容量门禁或 Python 补丁日志；宿主自身权限与执行记录负责源码操作。
