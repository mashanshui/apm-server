# Android 符号表版本管理技术设计

## Context

动机及范围见 [proposal.md](proposal.md)。本次收敛：事件上报保留非空应用 `buildId`；仅 mapping 上传可省略构建，省略表示同版本默认登记。mapping 专用优先、版本默认其次；SO 不接受应用 buildId，仅管理文件，不做 Native 还原。Rhea SDK 与 processor 均以 `1.0.4` 为准，不修改外部 btrace。

已核对的当前实现：

- `SymbolController` 只接收 `buildId + file`；`SymbolManagementService`、`SymbolRegistry.acquire`、活动唯一索引及 Crash 工作表均按应用与 buildId 定位。
- `SymbolRecordWriter` 已提供事务审计、revision 条件更新与任务唤醒；`SymbolFileStore` 已提供同卷暂存、不可覆盖发布、进程内文件租约和引用核对清理。
- `CrashBackgroundProcessor` 先恢复已登记结果，再固定一批 mapping；ClickHouse 事实已有 `app_version`，候选当前仅限定 buildId。首次成功状态及 publication 已持久化实际 symbolId/revision/SHA-256。
- `StackArtifactParseService` 已做有界 manifest 预检。processor 1.0.4 要求非空 buildId，回调不提供 appVersion；同一不可变 ZIP 的预检版本可用于选择，回调核对包名/构建，最终报告再核对完整身份，无需升级 SDK/processor。
- 公共事件、Crash/Jank 指标/Memory 校验、三份 JSON Schema 和 Jank manifest 保持构建必填。Android 保留原共享构建生成/配置和非空模型；Leak report 服务端原有选填字段不调整，SDK 继续上报非空构建。
- Crash 临时指纹、Issue 投影、前端及 MCP 保留现有构建语义，不增加 tmp-v2/native-tmp-v2 或可空事件适配。版本过滤仅用于选文件及后台候选，正式归组仍不变。
- Native 模块已有 ABI、完整 `elfBuildId` 和相对 PC。它们当前没有 SO 上传入口，且不会进入 R8。
- Spring multipart 默认文件/请求为 32/34 MiB；现有 Nginx 总入口已为 260 MiB。新 SO 能力必须显式调整应用接收上限，同时保留 mapping 自身 32 MiB 业务限制。

现有 `application-symbol-management` 明确拒绝 ZIP/SO、只按 buildId 匹配，与本变更目标冲突，故通过增量规格修改；Native 原始现场及 JVM 首次成功固定规则继续保留。部分知识库旧条目仍称列表在 Java 分页、没有删除入口，与当前数据库分页及 DELETE 实现不一致；实施同步时按实际代码原位纠正，不把本计划表述为当前事实。

## Goals / Non-Goals

**设计目标：**

- mapping 只提供应用版本即可登记默认文件；上报事件仍带构建，优先精确匹配，不跨应用版本选择文件。
- 复用现有单体、管理库和文件卷；SO 校验、冲突确认及恢复均有明确资源和事务边界。
- 保留历史来源、审计和已确定结果，明确无版本旧登记的处理方式。

**设计边界：**

- 每个应用版本一份默认 mapping、每个非空构建一份专用 mapping；不合并宿主/插件 mapping。
- 不解析 Native 地址、不改临时或正式指纹、查询契约和统计口径，不重写旧事件。
- 保留 JSON v2/Jank v3 的构建必填性和 Rhea 1.0.4，不改 SDK 队列或配套制品，不新增上报字段。
- 不提供符号文件下载、自动构建上传、对象存储和多实例共享卷协调。生产容量参数仍须独立验证。

## Decisions

### 1. mapping 登记构建可选，事件及 ELF 身份独立

`appVersion` 仍为必填的 1～128 字符原文字面值，拒绝首尾空白及控制字符，不做语义版本解析。仅 mapping 登记的 `buildId` 省略、null 或空白值统一表示默认范围；非空时保留大小写和原值，限制 256 字符，拒绝首尾空白及控制字符。符号登记同步采用这一上限，不截断标识进行匹配，标识仅用于元数据参数化查询，不参与文件路径。`versionCode` 不加入 mapping 唯一键，`revision` 标为“文件修订号”。

| 类型 | 活动身份 | 内容变更 |
|---|---|---|
| 版本默认 `MAPPING` | appId、appVersion，buildId=NULL | 同 SHA-256 幂等；不同内容确认替换 |
| 构建专用 `MAPPING` | appId、appVersion、非空 buildId | 同 SHA-256 幂等；不同内容确认替换 |
| `NATIVE_SO` 管理登记 | appId、appVersion、abi、elfBuildId，buildId=NULL | 同 SHA-256 幂等；同模块不同符号文件可确认替换 |

SO 模块本身通过 ABI 和完整 ELF Build ID 识别，应用版本只表示本次上传的发布归属。同一 SO 可在多个版本分别登记，本期不做跨版本物理去重，不引入版本关联框架；SO 任何接口、列表或确认条件都不依赖应用 buildId。mapping 不具备 ELF Build ID，仍保留可选构建来区分同版本不同混淆构建。

统一选择规则：事件必须有非空构建，存在对应版本专用记录时优先选中，否则只选择同应用版本的默认记录。mapping 登记为空表示适用范围，不改变事件构建，不为每个构建复制登记。默认不存在就保持无 mapping，不从某个“最新”或“唯一现存”的专用记录猜测。选中的专用文件不可读或存储查询失败返回存储错误，不退回默认文件。默认与专用登记是独立资源，各自 revision、审计和删除；专用被删除后尚未成功事件可重新选择默认，已成功结果不改变。

### 2. 扩展现有管理模型和文件卷

`app_symbol_file` 增加 `symbol_type`、可空 `app_version` 及 SO 专用字段，原 build_id 改为可空并支持 256 字符。旧行归类 MAPPING，app_version 保持 NULL。新 MAPPING 必须有版本，build_id 为 NULL 表示版本默认；SO 必须有版本、ABI、完整 ELF Build ID，build_id 必须为 NULL。审计保存相同身份快照，旧审计不伪造版本。

建立三类活动局部唯一索引：默认 mapping 的 `(app_id,app_version)`、专用 mapping 的 `(app_id,app_version,build_id)`、SO 的 `(app_id,app_version,abi,elf_build_id)`；以显式 NULL 条件处理默认身份，不能依赖普通 UNIQUE 对 NULL 的默认行为。无版本旧 mapping 不进入这些可匹配集合。数据库校验类型、版本与构建的互斥约束，替换和租约通过一次数据库选优及行锁固定实际来源。

列表继续数据库过滤和键集分页，mapping 支持 `mappingScope=default|build` 及可选 buildId，SO 支持 ABI/ELF 身份筛选，所有游标绑定完整筛选；SO 请求带应用构建筛选明确拒绝。不在 Java 加载所有记录后判断默认或精确匹配。

文件仍由服务端生成 UUID 键。启动清理和引用检查覆盖 mapping/SO，保留无版本旧登记引用。SO 不登记还原工作；默认或专用 mapping 变更在同一事务唤醒相应应用版本工作。

选择扩展同一表和现有服务边界，以复用权限、审计和删除。类型化校验分别实现，SO 不送入 R8，SO 字段不泄漏到 mapping 的选择条件。

### 3. SO ZIP 全量校验后原子提交

```text
Session + CSRF + role
          |
          v
upload permit --> bounded ZIP spool --> archive checks
                                            |
                                            v
                                   ELF metadata + SHA-256
                                            |
                                            v
                                   snapshot / conflicts
                                            |
                                            v
                                  publish immutable files
                                            |
                                            v
                              transaction: rows + audit
```

批次必填 appVersion，不接受应用 buildId。按该应用版本内的 ABI/完整 ELF Build ID 提取身份并排序；相同身份与摘要合并，不同摘要返回 `SO_ARCHIVE_IDENTITY_CONFLICT`。所有校验及目标身份检查先于发布及数据库修改。

初次上传只允许创建及等内容幂等。发现任一不同内容时，返回所有候选的有界快照，包含候选身份、传入摘要及当前 symbolId/revision/摘要，未存在项也明确表示。整批没有副作用。

用户确认后重新提交原 ZIP 及全部候选的预期快照，必须与本次重新校验出的身份集合完全一致。事务内按稳定顺序锁定已有行、检查 symbolId/revision 和预期不存在项，再统一写入元数据及审计；新建竞争由唯一约束兜底，冲突使整批回滚。不能只检查发生覆盖的那几项。相同内容不增加 revision 或审计；删除后同身份重传产生新 symbolId。

文件在事务前以不可覆盖键发布；确认回滚后可回收未引用文件。数据库提交确认未知时保留所有可能已引用文件，返回可重试存储错误。客户端用不带旧确认快照的原批次普通上传对账；全内容相同时返回幂等成功。确认请求本身仍严格检查旧修订号，不把过期期望当成覆盖授权。重启按数据库引用清理孤立文件，不跨请求保存 ZIP 或待确认批次。

不选择逐条尽力提交，因为遇到中途失败或覆盖确认时难以说明批次结果，也不能保证管理员确认的是完整状态。

### 4. 使用受限归档读取和只读 ELF 元数据检查

为读取 ZIP 中央目录的 Unix 文件属性，新增固定依赖 `org.apache.commons:commons-compress:1.28.0`，只使用 ZIP 读取能力；不使用解压到归档原路径的接口。[官方发布页](https://commons.apache.org/proper/commons-compress/download_compress.cgi)和 [ZipArchiveEntry 文档](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipArchiveEntry.html)提供版本及外部属性接口依据。

归档检查先有界预读尾部目录信息，限制中央目录字节与声明条目数后再建立读取索引；逐项继续核对实际数量、路径、CRC 和解压字节。首版接受普通单卷、非加密 ZIP，明确拒绝 ZIP64、多卷、加密、中央目录与本地头冲突。拒绝绝对路径、盘符、反斜杠、`..`、重复规范路径、链接及特殊文件、嵌套归档。普通目录、`__MACOSX/` 下的普通辅助文件及 `.DS_Store` 可忽略，但仍计入条目、大小和路径安全预算；其他普通文件必须为 `.so` 候选。

ELF 使用 Java 只读随机访问，按 [ELF 文件头规范](https://gabi.xinuos.com/elf/02-eheader.html)校验魔数、类、字节序、ET_DYN、机器类型、表项长度和边界。仅识别 ARM/ELF32、AArch64/ELF64、i386/ELF32、x86-64/ELF64 的小端组合。所有偏移、长度、乘加均做溢出和文件边界检查，不按文件提供的大小无界分配；表项数量、字符串表和 note 读取均有硬上限。超出支持的扩展格式返回明确不支持错误。

GNU Build ID 从有效 note 中读取，保留 1～64 字节的完整值；多个来源必须一致。检测 `.symtab` 中实际定义的函数符号，及 `.debug_info/.debug_line` 的非空、合法范围，保存事实布尔值。至少存在完整函数符号或调试信息才接受，只有 `.dynsym` 不足。此处不解释 DWARF、不验证地址还原质量；界面只显示“检测到函数符号/调试信息/行信息”，不承诺能够还原每一帧。Android 对 SYMBOL_TABLE 与 FULL 的能力区分见[官方说明](https://developer.android.com/build/include-native-symbols)。

本方案不安装运行期 NDK/LLVM，不执行外部解析命令，不加载 SO。未来 Native 还原引擎属于独立变更。

### 5. 给上传和校验设置独立预算

以下为首版默认值，配置与实际读取双重执行，不能以 ZIP 声明长度代替实际字节；数值是约束，不是生产吞吐承诺。

| SO 预算 | 默认值 |
|---|---:|
| 压缩文件 | 256 MiB |
| SO 单文件 | 128 MiB |
| 实际解压总量，含忽略的辅助文件 | 512 MiB |
| 中央目录元数据 | 1 MiB |
| 全部归档条目 / SO 身份数 | 256 / 128 |
| 路径深度 / 路径 UTF-8 字节 | 8 / 1024 |
| 单实例并发接收及校验 | 1，不排队 |
| 获得许可后处理时限 | 60 秒，循环内检查 |

SO 路由在 multipart 解析前取得许可；网页写安全检查使用现有 Session/角色和 CSRF 请求头，缺少或无效头直接拒绝，不能为了读取 CSRF 表单参数先解析大文件。超出并发立即 503。上传中断或 deadline 到达应中止后续读取和校验并释放暂存；文件系统 I/O 的单次阻塞不宣称可被任意时刻强制终止，代理和 Servlet 的读取超时同时生效。

Spring multipart 统一调到文件 256 MiB、请求 260 MiB；mapping 仍以原业务限制 32 MiB 拒绝超限。沿用 Nginx 260 MiB 入口并验证真实 multipart 开销和读超时；同时回归共享 multipart 的内存报告入口。应用暂存上界为压缩体与解压文件合计 768 MiB，还需为 Servlet/代理缓冲及保留文件预留空间；低于一次完整预算的可用磁盘时拒绝接收，不靠无限堆内存解压。上传许可必须覆盖接收至清理的完整生命周期。

### 6. HTTP 和网页契约

| 接口 | 新行为 |
|---|---|
| `GET /api/v1/apps/{appId}/symbols` | 增加类型、版本及 mappingScope；buildId 仅筛选专用 mapping，默认范围显式选择；SO 只支持版本、ABI/ELF 身份筛选，保留 cursor/limit |
| `POST /api/v1/apps/{appId}/symbols` | appVersion 与文本 file 必填，buildId 选填；缺失登记版本默认 mapping，响应中 buildId 为 null |
| `PUT /api/v1/apps/{appId}/symbols/{symbolId}` | 继续替换有版本 mapping 的文件与摘要，版本/构建/类型不可原位修改；SO 通过批次接口确认覆盖 |
| `POST /api/v1/apps/{appId}/symbols/native-batches` | appVersion、ZIP file 必填；可选 targetAbi + targetElfBuildId 及 expectedEntries，不接受应用 buildId part |
| `DELETE /api/v1/apps/{appId}/symbols/{symbolId}` | 两类及无版本旧登记均使用 expectedRevision 条件软删除；只有有版本 mapping 触发 JVM 工作 |
| `GET /api/v1/apps/{appId}/symbols/{symbolId}/crash-symbolication` | 仅支持有版本活动 mapping，返回其应用版本本轮进度，涵盖默认及专用 mapping；SO 或无版本记录返回 422 SYMBOL_PROGRESS_NOT_APPLICABLE |

SO 批次成功返回当前 items 及 createdCount/replacedCount/unchangedCount；有新建项为 201，其余为 200。409 响应沿用标准错误结构，在 details 中提供有界批次快照。`expectedEntries` 对每个 `{abi,elfBuildId}` 明确预期现有 `{symbolId,revision}` 或不存在，拒绝重复、漏项及多项。目标 ABI 和 Build ID 必须成对提供；ZIP 至少包含该准确模块才可提交，其他合法模块可一并保存。所有身份仍由服务端读取文件得出，路由预填值不作为证据。

新增稳定错误至少包括 `INVALID_APP_VERSION`、`INVALID_SYMBOL_ARCHIVE`、`INVALID_SO`、`UNSUPPORTED_SO_FORMAT`、`SO_BUILD_ID_MISSING`、`SO_SYMBOLS_MISSING`、`SO_TARGET_MISMATCH`、`SO_ARCHIVE_IDENTITY_CONFLICT`、`INVALID_SYMBOL_CONFIRMATION`、`SYMBOL_TYPE_MISMATCH` 和 `SYMBOL_PROGRESS_NOT_APPLICABLE`。格式错误为 400/422，字节超限为 413，不同内容和过期期望为 409；暂存/数据库/磁盘不可用为可重试 503，校验并发或预算繁忙使用独立安全原因。错误不返回绝对路径、文件内容或底层异常堆栈。

网页共用应用版本输入。MAPPING 提供选填 buildId，留空明确显示“此版本默认 mapping”，列表区分默认与构建专用；SO 表单及列表不显示应用 buildId，只显示 ABI 与“ELF Build ID”。批次确认仍逐项展示身份、新旧摘要及动作；切换到 SO 时清除应用构建筛选和冲突。只有 mapping 显示有界轮询的应用版本进度，并明确计数涵盖本版本所有构建，不冒充所选文件独有处理量。

JVM 详情带 appVersion/type 和原 buildId，管理员可在 mapping 页面清空构建以选择版本默认范围。Native 入口仅带 appVersion、可取得的 ABI/完整 ELF Build ID，不带应用 buildId。模块身份未知时只能进入版本通用 SO 管理。应用构建沿用现有非空展示；不显示“已还原”，不为全部模块自动扫描或循环请求。

### 7. 复用现有上报和构建身份

#### 7.1 上报与查询保持原契约

JSON v2、Jank v3、Android 非空事件模型和持久队列保留原契约。收回无构建事件、空串/null 存储转换、FPS 快照格式升级、可空查询和临时指纹 v2 改造；不因符号上传可选而修改各信号采集、幂等摘要或恢复逻辑。Leak report 服务端原有选填构建不扩展，SDK 继续提供构建。

mapping 新元数据允许空构建，仅表示文件适用范围；事件查询、Issue 和 MCP 沿用已有表示。版本和构建必须在符号选择之前校验，Jank 原文不裁剪或自动补造。

#### 7.2 按应用版本调度及无匹配事件公平推进

`SymbolRegistry.acquireVersioned(appId,appVersion,buildId)` 在数据库一次选出专用或默认记录并固定租约；不存在才走下一优先级，存储错误不回退。活动索引只轮转有版本 MAPPING。`CrashSymbolicationWork` 按 `(appId,appVersion)` 合并，默认文件变更只需一次持久化唤醒，不查询或创建全部历史构建任务。

若注册表确认该版本没有任何活动默认或专用 mapping，工作直接等待，不扫描遥测；查询存储故障须退避，不能当作没有文件。

每个版本工作持久保存轻量候选游标 `(event_time,event_id)`。数据库先关联事实并限定应用、appVersion、30 天窗口、JVM 类型及最新 pending/failed 状态，再按游标取最多 100 个轻量身份和必填构建；此时不读 stack/diagnostics。对本页有限构建解析实际 mapping 元数据；缺失、同来源确定性失败和未到退避时间的条目有界跳过，其余才按有限 eventId 读取载荷并分成同一实际文件的子批。

每个子批固定一个文件租约，保留现有并发、输出及处理时限。尝试来源按实际 symbolId/revision 比较；默认换版、专用新增/删除引起来源改变时，失败事件可重试，成功事件始终排除。每页在安全完成后推进游标，遗漏确认时允许重放同一页；扫描到底后回到首部。重启恢复游标；常规唤醒和周期对账不能反复重置到第一页，否则后方有匹配事件会饥饿。迟到的更早事件在后续轮转发现。等待状态表示本轮没有可处理来源，不证明全量成功。

成功 publication 仍以 `(app_id,event_id)` 固定，仅增加 appVersion 用于版本租约门禁；事件实际非空 buildId 保留在原状态中。恢复先重放已登记字节，再选择新文件。默认与专用 mapping 的进度接口读取同一个应用版本工作，明确计数范围，不扫描遥测计算全量总数。

#### 7.3 Jank 1.0.4 与统一选择器

SDK 和 processor 固定 1.0.4，不修改 btrace，不以新配套制品为前置条件。服务端对同一临时 ZIP 做有界预检，取得包名、appVersion、非空 buildId；processor 完整性校验后的回调核对包名和 buildId，以预检 appVersion 调用统一选择器，最终 sourceManifest 再核对全部身份后落库。无需在 callback metadata 新增 appVersion，也不修改原 ZIP。

无 mapping 时按既有规则继续；存储失败不回退，不把构建必填误解为必须上传专用 mapping。已保存 Jank 证据和指纹不回算。使用实际 1.0.4 JAR 与有构建 ZIP 验证预检、回调、报告、文件租约与幂等。

### 8. 验证与必要文档同步

- 文件校验：人工构造的 ELF32/64 边界夹具，以及从最小无业务代码源码和可复现脚本在临时目录生成的四 ABI SO；只提交夹具源码和生成步骤，不提交编译产物。核对完整 Build ID、符号剥离、损坏 note/表/整数溢出和 CRC，不依赖真实设备数据。LLVM/readelf 仅可用于测试产物对照，不加入运行镜像。
- PostgreSQL：真实 Flyway/JPA 校验；默认 NULL 唯一性、专用与默认并存、两级来源切换、SO 多 ABI、批次竞争/提交未知、旧登记及迁移守卫，H2 不代替迁移验证。
- SDK/协议：回归公共事件及 Jank 缺失/null/空白构建被拒绝，既有有构建事件仍正常接收；Android 默认及显式构建配置、队列恢复测试保持原行为。Leak report 沿用原有服务端字段约束，不加入全信号无构建验收。
- 查询：10 万符号元数据的 EXPLAIN ANALYZE 及原百万级 Crash 基线，包含无匹配事件占满前页、多版本共用构建、默认/专用来源、重启与迟到数据；证明轻量分页公平推进、版本过滤先于 LIMIT/载荷并遵守原预算，记录规模和边界。
- 端到端：隔离 HTTP、实际 processor 1.0.4、1280px 网页及有效 Token MCP，验证有构建事件使用默认/专用 mapping 及 SO 无应用构建的批次管理；SO 操作前后 Native 原始材料不变。现有事件查询保持原契约，不要求无构建模拟器验收。
- 实施后同步符号表/Jank API、相关客户端说明和各端受影响知识库；核对公共事件、查询、MCP 和 Schema 保持原契约，删除此前可空改造的描述。按唯一归属原位更新，记录实际版本及边界，不新增操作流水。

## Risks / Trade-offs

- [同版本多次混淆却共用默认 mapping] → 默认只是管理员明确登记的版本文件，不保证同版本所有构建同源；存在不同 mapping 时填写专用 buildId，页面明确匹配优先级及首次成功固定边界。
- [SO 静态符号检查不能证明 DWARF 可用于每个地址] → 元数据只报告存在性，所有 Native 事件继续显示尚未还原。
- [同模块在多个版本重复占用磁盘] → 接受首版简单隔离；用受控卷空间和上传限制治理，不引入跨版本共享存储抽象。
- [大 ZIP 消耗接收缓冲及磁盘] → 在 multipart 前限制并发、限制中央目录和实际解压量、检查磁盘余量并释放暂存。
- [版本字符串与上传输入不一致] → 拒绝隐式裁剪和大小写改写，详情预填原值，API 示例明确精确匹配。
- [迁移中断或旧版本重新启动误读新数据] → 停旧进程后离线切换；迁移守卫、保留备份和协调前后端部署，不混跑新旧二进制。
- [增加 ZIP 依赖] → 固定版本并检查依赖树与安全公告；若实施时需升级同一库的小版本，记录原因并跑归档失败分支，不扩展归档格式。
- [把上传字段可选误解为上报字段可选] → 上报校验、Schema、SDK 及查询保留原契约，仅 mapping 登记可空；以负向上报测试与真实 1.0.4 ZIP 验证边界。
- [版本工作前页全无 mapping] → 先读有限轻量身份并持久推进游标、循环回头，常规唤醒不重置；通过后页可处理与重启故障用例验证无饥饿。

## Migration Plan

1. 在隔离 PostgreSQL 验证新增版本迁移及升级前后数据计数。正式环境切换前备份管理库与符号卷，暂停后续接收和符号写入，让原后台完成未确认 publication，停止所有后端实例，等待原数据库租约释放或过期。
2. 迁移使用数据库守卫：存在未确认 publication 或仍有效工作租约就明确失败，不自动删除、重写或重新计算。保留全部已确认 publication、状态版本序列、遥测事实和状态。
3. 文件与审计追加类型/版本字段并允许 build_id=NULL，旧 MAPPING 的 app_version 保持 NULL。建立默认、专用、SO 三类索引；旧无版本记录不是版本默认文件，继续保留引用并要求按明确版本重新上传。
4. 工作表增加稳定 job_id、可空 app_version 及扫描游标，以 job_id 为主键，建立 `(app_id,app_version) WHERE app_version IS NOT NULL` 唯一索引。旧构建工作保留 app_version=NULL、原构建及计数，退役为 waiting_mapping/legacy_unversioned 并清除过期租约；领取排除旧行。新版本工作 build_id=NULL，通过部分唯一索引合并，不猜测旧工作所属版本。
5. publication 增加可空 app_version；旧已确认行保留 NULL，新未确认行必须有非空版本。先前守卫保证没有需要猜测版本的旧在途载荷。既有成功按 eventId 幂等记录继续可读。
6. 确认 SDK 与 processor 均为 1.0.4，协调部署后端/网页和 multipart 配置；既有有构建事件和队列继续使用原身份，不发布新的队列格式或可空上报。检查旧登记隔离、默认/专用选择及 Native 原始状态；MCP 不因本变更修改契约。
7. 回滚只能在停止新进程后恢复同一备份点的管理库、文件卷与旧应用版本；有新版本写入后不能只回退二进制或单边回退数据库。ClickHouse 已产生的新成功结果不应被回滚成旧原始状态；跨库恢复需另行制定一致快照和恢复演练，未经验证不宣称可无损回滚。
