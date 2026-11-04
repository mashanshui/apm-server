# 部署与运维资料

从[平台部署与运维](../knowledge-base/07-部署与运维.md)开始：该页维护默认 Compose 五服务启停、云端发布、数据保护与回退。环境最近核验统一见[平台状态](../knowledge-base/00-当前实现与验证边界.md#环境最近核验)。

| 专题 | 维护内容 |
|---|---|
| [SSH 连接说明](cloud-ssh.md) | 连接参数、主机指纹、私钥约定和严格校验 |
| [ClickHouse 本地初始化](clickhouse-local.md) | 独立容器、空库初始化、后端连接与云端调试数据库 |
| [Grafana/ClickHouse 接入](grafana-clickhouse-install.md) | 可选 Grafana、只读账号、插件、数据源和 Dashboard |
| [Crash 灰度、开关与回滚](crash-rollout.md) | 上报灰度、功能开关及保留数据的回退原则 |

手工 ClickHouse/Grafana 步骤用于独立联调；默认 Compose 已管理 ClickHouse，不应同时再创建同端口容器。历史执行结果见[验收资料](../validation/README.md)，不在操作说明中追加部署流水。

返回[项目文档导航](../README.md)。
