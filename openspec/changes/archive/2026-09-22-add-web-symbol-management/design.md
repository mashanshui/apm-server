## Context

动机和范围见 [proposal.md](proposal.md)。当前 CrashEventProcessor 写入 raw_only，CrashQueryService.event 直接返回原始异常链；StackArtifactParseService.parse 在临时 ZIP 上调用 processor 和 AppStackMappingResolver，JankArtifactReportMapper 保存堆栈字典、采样及调用树并计算指纹，finally 删除 ZIP；JankQueryService.event 只读取证据。上述为源码核对，不代表本次已执行运行验证。

现有 jvm-crash-monitoring 规范允许补充符号化指纹，与本次不修改指纹的选择不同；本 change 的 delta 明确替换该要求。现有卡顿规范使用手工文件路径，delta 改为注册表，不静默修改正式文档。

## Goals / Non-Goals

目标：在现有模块化单体、Session/CSRF、PostgreSQL、ClickHouse 与本地持久卷中完成闭环；只有 mapping 元数据和文件持久化，Crash 还原结果仅存在于请求内。

非目标：事件迁移、还原结果缓存、对象存储、任务队列、独立服务、自动上传、Native、卡顿重解析。旧数据不做迁移；只要既有 Crash 的原始结构仍满足当前契约，它自然可通过查询实时还原，不需要回填任务。

## Decisions

### 1. 独立符号表业务域

新增 symbol.api 暴露授权后的构建查询、固定文件版本使用接口和 Crash 文本还原能力；symbol.internal 管理文件、元数据、R8 和网页控制器。Crash/Jank 只依赖公共 API，symbol 不依赖它们的内部模型。Identity 继续负责成员鉴权，更新 ArchUnit 依赖白名单。避免把应用符号表业务放进通用 platform 或复制一套 Crash/Jank 管理流程。

### 2. 构建身份及上传契约

精确键为 appId + buildId；不新增 SDK mappingId，不按 appVersion 回退。注册入口采用现有 Jank 接受的 `[A-Za-z0-9._-]{1,128}`，排除单独的点和双点。不改动原事件接收契约：无法注册匹配的已有 Crash buildId 仍可看原始堆栈。网页只提交 buildId 和 file，应用由路径及服务端成员关系确定。普通 mapping 无法证明所属 APK，帮助文案要求使用同次构建文件；不把摘要校验当作 APK 身份校验。

拟定接口（实施时按现有 API 前缀风格落地）：

| 路由 | 请求及响应 |
|---|---|
| GET /api/apps/{appId}/symbols | buildId 精确筛选，游标分页；返回当前记录元数据 |
| POST /api/apps/{appId}/symbols | multipart buildId + file；首次 201，相同内容 200，不同内容 409 SYMBOL_CONFLICT |
| PUT /api/apps/{appId}/symbols/{symbolId} | multipart file + expectedRevision；仅经网页确认后调用；成功 200，版本冲突 409 SYMBOL_VERSION_CONFLICT |

记录返回 id、buildId、revision、originalFilename、sizeBytes、sha256、uploadedBy、updatedAt，不返回路径和内容。非法 buildId 返回 400 INVALID_BUILD_ID；过大 413；非支持载体 415；非法或不支持的 mapping 422 INVALID_MAPPING / UNSUPPORTED_MAPPING_VERSION；存储故障 503 SYMBOL_STORE_UNAVAILABLE。认证、CSRF 和无权限错误复用既有约定。更新的同内容请求仍先验证 expectedRevision，避免基于过期确认操作。

### 3. 文件与元数据一致性

PostgreSQL 新增应用构建当前记录与替换审计记录：当前记录含唯一键、revision、服务端生成 storageKey、摘要、大小、名称、操作者与时间；审计保存前后版本和摘要，不保存 mapping 内容。文件路径使用服务端生成 UUID，与用户路径隔离。

先受限流写临时文件并完成完整格式解析，再原子移动到同卷不可变文件路径，最后事务提交当前记录及审计。首次并发冲突重读当前摘要决定幂等或冲突；替换使用 revision 条件更新。事务失败清理新文件；进程崩溃遗留的未引用文件由启动清理回收。

首版单后端进程使用文件租约计数：查询注册表和获取文件租约与版本切换/回收协调，旧版本最后一个使用者释放后清理；旧文件清理失败留待启动回收，不回滚已成功替换。不得只用定时延迟删除来假设解析已经结束。启动清理只删除受控目录内明确不被当前记录引用的内部文件；数据库不可用时不清理。多实例共享文件卷不在首版部署范围内。

### 4. 官方 R8 与资源控制

后端嵌入固定版本 com.android.tools:r8，通过 Java API 读取 mapping、还原完整 Java 异常链；不自行编写名称替换器，不启动每请求命令行进程。上传使用完整定义读取和实际解析验证，不仅调用 builder；禁止实验性格式，诊断转换为安全错误。实施第一步验证选定发行版的可用 API、mapping 版本、processor 依赖树和 fat JAR 是否携带重复 R8 类；若无法消除冲突，报告阻塞，不擅自扩大为进程服务。

首版不缓存 mapping 解析对象或还原结果。所有上传校验和 Crash 还原共享有界并发许可；默认建议 2 个，上传文件默认 32 MiB，还原输出默认 1 MiB，均可配置，并与 Spring multipart、Nginx 限制一致。Crash 无许可直接降级 parser_busy，上传无许可返回可重试 503 SYMBOL_PARSER_BUSY。输出超限丢弃派生输出并返回原始内容，不冒充完整成功。

Java API 中断不等于计算已停止，因此本设计不承诺强制执行超时；许可直到实际计算结束才释放。先用真实 mapping、长堆栈、畸形文件测试 CPU/堆内存和耗时，再调整默认上限。若要求严格硬超时或解析隔离，需要另行设计，不用 HTTP 超时伪装完成资源回收。

### 5. Crash 请求时处理

详情先做 Session/成员授权并读取原始事件，再查当前 mapping、取得固定文件租约、执行还原，finally 释放资源。延续现有 crash 原始结构，新增 symbolicatedStackText（可空）、symbolFileId/revision（可空）、symbolicationReason，symbolicationStatus 表示本次响应而非持久状态。成功处理使用 symbolicated，但文案说明不保证每帧均被还原；raw_only + mapping_missing 表示缺失；failed + mapping_unavailable/parser_busy/retrace_failed/output_limit 表示降级。不通过字符串是否变化判定成功或捏造行号。

R8 输入保留完整异常链顺序、原因链和已有行号；核对现有结构和脱敏是否丢失上下文，不虚构缺失的信息。还原输出再次按现有安全规则处理并在前端转义，保留多候选和内联输出。所有路径禁止写回事件、指纹或还原字段。详情响应使用 Cache-Control: no-store；页面每次进入或刷新重新请求，不用跨页面还原缓存。一次已开始的请求可返回其固定的旧版本，替换完成后开始的新请求必须取新版本。

Crash 列表不执行还原，移除将 raw_only 显示为“缺少 mapping”的推断，改为“详情实时解析”提示；统计及 Issue 继续使用原始字段。

### 6. 卡顿只调整 mapping 来源

在既有 manifest/包名检查之后由共享注册表取得固定版本文件并传给 processor，租约覆盖整个解析。缺失继续未解混淆处理；注册表故障或文件损坏不可伪装缺失，转成可重试 503。上传解析仍可能受 mapping 内容影响；不变更已有指纹算法，不承诺卡顿跨 mapping 版本聚合一致。已有完整事件的重复请求不能用新版本覆盖证据；检查写协调器的幂等和失败修复边界。卡顿详情不读取符号表，界面说明后补或替换仅影响后续上传的事件。

### 7. 网页交互

应用设置新增符号表页面，展示列表、筛选、上传进度和错误；详情上传入口自动带 buildId。不同内容冲突后展示旧、新摘要和“只影响后续卡顿、Crash 下次查看生效”说明，管理员确认后携带 expectedRevision 替换。冲突要求重新加载并确认，不能自动覆盖。成员隐藏写操作，服务端仍独立授权。应用切换取消旧请求，使用已有 HTTP/CSRF 和组件样式。

## Risks / Trade-offs

- 每次查看增加计算和首屏等待 → 通过文件/输出/并发限制控制，执行真实样本基线，不提前承诺毫秒级时延。
- mapping 可能被手工绑定到错误 buildId → 明确构建对应要求，保留替换审计；格式验证不能解决语义错配。
- mapping 与数据库分离 → 原子文件、条件事务、租约和孤立文件回收；两者共同备份。
- Jank 后补表不更新旧证据 → 方案 A 明示页面边界，不暗示与 Crash 一样实时生效。
- 现有 API 文档把 raw_only 当持久状态 → 同步详情和列表的不同语义，前后端联调覆盖。

## Migration Plan

仅新增 Flyway 迁移和持久目录配置，不修改已发布迁移，不清空业务数据。移除旧手工 mapping-root 的查询依赖，不迁移旧目录；需要的文件由网页重新注册。先部署后端及持久卷，再部署前端，使用受控应用上传真实构建 mapping 验证。回退应用版本时保留新表与文件，不执行破坏性回滚；旧版本不会读取新注册表，需明确回退时还原功能不可用。本任务只写规划，不连接云端部署。

## 文档与验证归属

实施同步根知识库的当前边界、架构、安全及查询主题；后端知识库的解析、持久化、配置和测试；前端知识库的页面与联调；新增 docs/api/symbol-api.md 并更新 crash-api.md、stack-artifact-api.md 与入口；新增 docs/client-integration/symbol-mapping.md 说明手工上传及 buildId 对齐，链接 Crash/Jank 接入和入口。不将计划写成已完成。

验收覆盖权限/CSRF、幂等/并发替换、失败回收、旧租约、真实 R8 输出、连续两次调用且无数据库写入、补传替换、降级、调用树和卡顿重复回归、前端错误/切换/转义，以及受控 PostgreSQL 和本地持久卷重启。计时记录冷读取、重复查看、并发上限，区分模拟测试和真实依赖验证。
