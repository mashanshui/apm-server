# Android/processor 后续改造清单

本清单对应 apm-server 的应用身份协议收敛。本轮只修改 apm-server；Android SDK、Android 示例应用和 `rhea-trace-processor` 工程不在当前工作区内，不在本轮直接改动。服务端已固定 `io.github.mashanshui:rhea-trace-processor:1.0.2`，客户端设备 ZIP 的 UUID v4、`threadScope=main` 兼容性和云端落库已完成验收；本清单剩余内容是 Android producer 生命周期、客户端持久队列、生产制品仓库、真机长期运行和容量治理，不应把本次固定 ZIP 验收扩大为完整 SDK 交付。

## 1. 公共事件与 App Key

- [ ] 公共事件模型和序列化版本升级到 `schemaVersion=2`。
- [ ] 将事件中的包名字段统一为 `packageName`，删除旧 `appId` 字段、读取回退和默认包名补值。
- [ ] 上传请求头改为 `X-App-Key`，客户端配置字段改为 `appKey`，Key 格式使用 `apm_ak_` 前缀。
- [ ] App Key 只从应用安全的本地配置注入请求头，不进入 URL、日志、异常、事件正文、ZIP、诊断文件或版本库。
- [ ] 使用服务端创建应用后获得的完整 App Key 和绑定包名联调；不要把网页展示的系统 UUID `appId` 当作 Key。

## 2. 卡顿 ZIP producer

- [ ] ZIP `manifest.json` 升级到 `schemaVersion=3`、`artifactType=RHEA_JANK`。
- [ ] manifest 使用 `packageName`，删除旧 `appId` 字段和 v2 兼容回退；严格拒绝未知字段的协议变体（若 producer 自身负责 schema 校验）。
- [ ] 保持 `eventId`、`occurredAt`、`buildId`、采样计数和 `files` 摘要语义稳定；声明大小与 SHA-256 必须对应 ZIP 内实际字节。
- [ ] 新增并冻结安装级 `anonymousDeviceId`、启动级 `sessionId` 和 UUID v4 `processId`；主进程可令 `processId=sessionId`，子进程每次创建独立生成，禁止写入 Android 数值 PID。
- [ ] 重新生成真实 `.rheajank.zip`，核对 `manifest.json`、`sampling.bin`、`sampling-mapping.bin` 条目、声明大小、摘要和解析输入字节。
- [ ] 确认 processor 输出的 `sourceManifest.packageName` 与 producer 一致，应用帧判定使用 Android 真实包名，不使用系统 UUID `appId`。

## 3. processor 制品与服务端门禁

- [ ] 将支持 manifest v3 UUID v4 `processId` 的真实 `rhea-trace-processor:1.0.2` 制品发布到生产使用的受控 Maven 仓库；本地 Maven Local 和云端测试已验证，生产制品治理仍待完成。
- [x] 在服务端记录已确认的 Maven 坐标、版本、开发机仓库位置和 SHA-256 校验值，并登记对应真实 v3 fixture；生产仓库位置仍待 Android/发布流程确认。
- [x] 将 apm-server 的 processor 依赖升级到已确认的 v3 版本；服务端不通过兼容代码伪造结果。
- [x] 使用包含 UUID v4 `processId` 的真实 v3 fixture 运行 processor 集成测试，确认报告 schema、包名、主线程证据和解析结果；客户端设备 ZIP 已通过 `rhea-trace-processor:1.0.2` 验证。
- [ ] 比较客户端原始 ZIP 字节、服务端接收字节、processor 报告关键字段和归一化结果；报告异常时不得把模拟 parser 结果当作真实兼容证明。

## 4. 持久重试与确认

- [ ] 复用现有应用内持久化队列和配置，不因字段改名引入新的队列框架或额外 WorkManager 配置。
- [ ] `200 accepted` 和 `200 duplicate` 才能确认该 `eventId` 的 ZIP 已被服务端接受；同一次重试保留原始 ZIP 字节和 `eventId`。
- [ ] 将 `INVALID_APP_KEY`、manifest 版本/字段错误和 `PACKAGE_NAME_MISMATCH` 视为永久失败，不循环重试。
- [ ] 将 `APP_AUTH_UNAVAILABLE`、服务端存储不可用和 parser 繁忙按响应中的临时错误语义重试，并遵守 `Retry-After`。
- [ ] 验证网络中断、服务重启、首次 `accepted` 后重复 `duplicate`、鉴权失败和包名错误的队列状态不会丢失或误确认。

## 5. 交付验收证据

- [x] 提供支持 UUID v4 `processId` 的真实 v3 processor 制品坐标、版本、校验值和 fixture 文件名。
- [ ] 提供 Android 客户端视角的 JSON v2 与卡顿 v3 首次接受、重复、错误 Key、错误包名、网络重试和服务重启联调记录；服务端本地 HTTP 验收记录已登记在 API 文档。
- [ ] 提供 Key 脱敏检查结果，证明完整 Key 未出现在日志、URL、构建产物和持久诊断数据中。
- [ ] 在 Android producer、真实设备和客户端队列验收完成前，客户端联调交付仍保持未完成；服务端任务 6.6、6.7、10.5 已完成。
