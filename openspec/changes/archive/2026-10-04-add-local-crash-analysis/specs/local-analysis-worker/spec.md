> 历史规范（2026-10-04）：本文件仅保留旧方案要求，已被当前代码方案替代；归档时未同步到正式规范，当前行为见[正式规范](../../../../../specs/current-code-crash-fix/spec.md)。

## Purpose

为开发者提供宿主 Agent 直接推理的单事件本地只读分析，以 Python 管理独立身份、固定材料、引用核验、任务生命周期与幂等回传，明确宿主模型控制不可观察的边界。

## ADDED Requirements

### Requirement: 自包含 Skill 交付
Skill MUST 随附当前宿主流程所需 Python 源码、锁定依赖和统一启动入口，无需用户另外检出 Worker 工程。启动 MUST 按自身位置定位运行时，保持调用者目录、参数和 CLI 返回码；不自动读取调用目录中的 .env。ZIP MUST 使用明确文件列表，拒绝包内链接，排除凭据、本地配置、虚拟环境、日志、测试、旧执行器及真实验收数据。平台身份、仓库映射与私有恢复配置仍 MUST 单独提供。

#### Scenario: 独立安装运行
- **WHEN** 将完整 ZIP 解压到仓库以外、含空格或中文的安装目录，宿主具有 Python、uv 与 Git
- **THEN** 启动脚本准备锁定生产依赖，并可运行 help/doctor，无需原仓库或独立模型环境

#### Scenario: 私有文件不进入交付
- **WHEN** Skill 开发目录包含 .env、私有配置、虚拟环境或临时结果
- **THEN** 打包只收录明确公开列表，缺失或链接文件使打包失败

### Requirement: 独立执行身份
系统 MUST 提供限定应用、仓库和类型、有期限且可撤销的独立 Worker 凭据；仅存摘要。凭据 MUST 留在可信 Python 进程，不返回模型、对话或命令参数。查询 Token、App Key 和网页 Cookie MUST NOT 替代执行身份。证据和结果 MUST 绑定自身分配 Run，禁止直连数据库。

#### Scenario: 撤销或跨应用
- **WHEN** 身份撤销或请求其他应用任务
- **THEN** 拒绝访问；不泄露材料，不将本地工具关闭当宿主模型已停止

### Requirement: 宿主手动分析固定源码
系统 MUST 通过 apm-crash-analyze Skill 接收用户指定的已有 taskId，由当前宿主直接分析。Python MUST 先核验任务策略、仓库身份、完整提交与冻结证据摘要，提供固定提交的脱敏只读材料，不调用独立 OpenCode 或 DeepSeek。不得改变原工作区，已识别签名和私钥文件 MUST 排除并记录；链接越界、不可完整读取子模块和 LFS MUST 阻断。

#### Scenario: 工作区有修改
- **WHEN** 指定提交可读取但原工作区存在未提交修改
- **THEN** 材料仅来自固定提交，保留原工作区

#### Scenario: 旧任务策略
- **WHEN** 任务冻结输入仍为旧固定执行器策略
- **THEN** 拒绝按宿主策略领取，引导网页创建新任务，不暗改旧输入或结果

### Requirement: 有界只读工具和读取事实
Python MUST 提供 prepare、read、search、submit、status、stop 的任务命令。读取与搜索 MUST 绑定任务、当前 Run、平台和私有目录，禁止任意 Shell/编辑/网络操作；每次材料交付 MUST 校验有效分配、租约、取消和期限。源码引用 MUST 被同 Run 实际 read 行范围覆盖，片段与摘要 MUST 由 Python 重建。搜索结果不能作为实际 read 证明。工具 MUST 限制文件、行数、输出、搜索数与累计材料大小；权限范围不能仅由 Prompt 声明。

#### Scenario: 非法路径和假引用
- **WHEN** 请求目录外文件、未读行范围或其他任务引用
- **THEN** 拒绝操作或结果，不回传有效分析

#### Scenario: 源码提示扩权
- **WHEN** 注释或堆栈要求读取秘密、编辑、构建或调用其他模型
- **THEN** 宿主不执行这些指令，本任务工具拒绝扩大操作范围

### Requirement: 心跳与幂等恢复
Python MUST 在有限生命周期看护进程维护心跳，不能依赖宿主每次手动续租。领取 MUST 使用稳定请求 ID，结果 MUST 原子保存原文与摘要；未知回传 MUST 对账同 Run，不重新分析。旧租约和取消 MUST 拒绝迟到结果，恢复 MUST 沿用原任务、平台与私有目录。新尝试 MUST 网页显式重试并保留历史。

#### Scenario: 回传响应丢失
- **WHEN** 平台已保存但本地未收到确认
- **THEN** 同摘要恢复得到原确认，不调用宿主重新推理

#### Scenario: 看护失联
- **WHEN** 看护退出、系统暂停或租约失效
- **THEN** 不继续交付材料或接受新结果，不假定宿主模型停止

### Requirement: 真实预算与停止事实
工具 MUST 强制材料、操作资源、租约与总期限，无法控制的宿主请求数、Token 和中止能力 MUST 明确标为不受本工具保证。localToolsStopped 与 hostStopState MUST 分别记录；stopConfirmed MUST 仅在本地工具停止且实际宿主停止核验成立时为 true。模型自报 MUST NOT 作为核验依据。UNKNOWN MUST 阻止新尝试及原身份新领取；管理员确认 MUST 保留真实核验依据。

#### Scenario: 报告完成但宿主停止未知
- **WHEN** 有效结果已保存且本地材料工具关闭，宿主停止不可核验
- **THEN** 可保存 SUCCEEDED 报告，标记宿主 UNKNOWN、stopConfirmed=false，网页显示两类事实并继续阻止新尝试

#### Scenario: 取消或超时
- **WHEN** 任务取消、凭据撤销或期限到达
- **THEN** 关闭本地工具和拒绝后续提交，保留宿主停止未知，不自动重跑

### Requirement: 宿主来源与凭据配置
执行结果 MUST 记录 HOST_AGENT 与非敏感宿主来源；模型/版本自报 MUST 标为 HOST_REPORTED，无法取得 MUST 为 null/UNKNOWN。用量 MUST 来自可信宿主统计，否则 null，不估算为零。Skill MUST 不要求独立 DeepSeek 密钥、Docker 或 OpenCode；已有 Worker 身份、仓库映射和私有状态配置仍须保护。

#### Scenario: 模型信息不可取得
- **WHEN** 宿主未提供可核验实际模型和用量
- **THEN** 保存未知，不复制旧任务固定模型或从回答猜测执行版本

### Requirement: Skill 输入、反馈与宿主验收
Skill MUST 对缺失、无效、多候选 UUID 请求补充；仅事件链接 MUST 引导网页创建。Skill MUST 指导宿主实际 read、异常链推理与结构化提交，反馈平台实际结果，不补造未返回事实。至少一个宿主 MUST 完成真实读取、推理、引用核验、回传和网页验收；其他宿主兼容与质量 MUST 分别声明，原执行器验收不替代新流程。

#### Scenario: 自然语言实际分析
- **WHEN** 宿主收到明确已有任务且前提满足
- **THEN** 通过 Python 准备及读取工具分析、提交结构化结果，并如实反馈回传状态和未知事实
