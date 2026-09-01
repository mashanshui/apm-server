# JVM Crash 灰度、开关与回滚

## 灰度顺序

1. 执行 src/main/resources/db/clickhouse/001_crash_schema.sql，验证原始表、明细表和两个小时聚合对象可重复初始化。
2. 生产默认使用 ClickHouse；如需隔离外部依赖，可临时以 `APM_STORAGE_MODE=memory` 联调 API 和固定数据集，验收后恢复 ClickHouse。
3. Android SDK 只灰度发送 JVM fatal Crash，保留本地队列并观察 accepted、rejected、duplicate 和临时失败重试。
4. 通过 Grafana 检查总览、趋势、问题排行、版本对比、无数据、分母不足和详情下钻。
5. 逐步扩大项目或设备比例，关注 /actuator/metrics 中的接收量、拒绝量、重复量、上报延迟、Crash 可见延迟和分母不足次数。

## 能力开关

~~~properties
apm.ingest.enabled=true
apm.storage.mode=clickhouse
apm.clickhouse.enabled=true
~~~

`apm.ingest.enabled=false` 会停止 App Key 对应的 Crash 上报。`APM_STORAGE_MODE=memory` 是无外部依赖的临时开发模式；ClickHouse 模式必须配置 URL、数据库、用户名和密码。

## 回滚

- 发现客户端字段问题：关闭 SDK Crash 发送开关，保留本地队列，不删除事件。
- 发现服务端存储故障：将服务切回维护页或 apm.ingest.enabled=false，客户端按临时错误语义保留并退避重试。
- 发现 Dashboard 查询异常：只下线 Crash Dashboard，不改写已写入的原始事件和事件 ID。
- 修复后先用固定数据集验证去重和分母，再恢复灰度。

回滚不使用 git reset 或删除 ClickHouse 表；历史原始事件和聚合数据按保留策略继续保存。正式生产环境还需要把开发期请求头权限替换为真实登录、Session/OIDC 与项目成员授权。
