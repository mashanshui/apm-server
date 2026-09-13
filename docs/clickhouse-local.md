# ClickHouse 本地初始化

完整的 Grafana/ClickHouse 安装、只读用户、Dashboard 导入和 Spring Boot 接入流程见[《Grafana/ClickHouse 安装与项目接入教程》](grafana-clickhouse-install.md)。

服务默认使用 `apm.storage.mode=clickhouse`，启动前需要准备 ClickHouse，并按版本顺序执行 `001_crash_schema.sql`、`002_jank_schema.sql`、`003_jank_sampling_quality.sql`、`004_application_identity_schema.sql` 和增量表 `005_memory_metrics.sql`。`004` 要求旧业务表为空，并将租户身份切换为 UUID `app_id`；旧项目数据不会自动迁移。`005` 只新增内存采样表，不改写既有迁移。如需不依赖外部数据库的临时联调，可显式设置 `APM_STORAGE_MODE=memory`：

```powershell
docker run --name apm-clickhouse -d `
  -p 8123:8123 -p 9000:9000 `
  -e CLICKHOUSE_DB=apm `
  clickhouse/clickhouse-server:latest

Get-Content backend/src/main/resources/db/clickhouse/001_crash_schema.sql |
  docker exec -i apm-clickhouse clickhouse-client --multiquery
Get-Content backend/src/main/resources/db/clickhouse/002_jank_schema.sql |
  docker exec -i apm-clickhouse clickhouse-client --multiquery
Get-Content backend/src/main/resources/db/clickhouse/003_jank_sampling_quality.sql |
  docker exec -i apm-clickhouse clickhouse-client --multiquery
Get-Content backend/src/main/resources/db/clickhouse/004_application_identity_schema.sql |
  docker exec -i apm-clickhouse clickhouse-client --multiquery
Get-Content backend/src/main/resources/db/clickhouse/005_memory_metrics.sql |
  docker exec -i apm-clickhouse clickhouse-client --multiquery
```

生产配置至少需要：

```properties
apm.storage.mode=clickhouse
apm.clickhouse.enabled=true
apm.clickhouse.url=http://clickhouse:8123
apm.clickhouse.database=apm
apm.clickhouse.username=apm_admin
apm.clickhouse.password=${CLICKHOUSE_PASSWORD}
```

当前代码通过 ClickHouse HTTP 适配器持久化事件；应用账号需要同时具备 `SELECT` 和 `INSERT` 权限，Grafana 仍应使用独立的只读账号。

## 本地后端连接云端调试数据库

当前云端仅用于调试，`compose.cloud.yaml` 已将 PostgreSQL 5432 和 ClickHouse HTTP 8123 映射到公网。本地后端可在根目录被 Git 忽略的 `.env.local` 中配置：

```dotenv
APM_DATABASE_URL=jdbc:postgresql://124.221.252.121:5432/apm
APM_DATABASE_USERNAME=apm
APM_DATABASE_PASSWORD=<云端 PostgreSQL 密码>
APM_STORAGE_MODE=clickhouse
CLICKHOUSE_URL=http://124.221.252.121:8123
CLICKHOUSE_DATABASE=apm
CLICKHOUSE_USERNAME=apm_admin
CLICKHOUSE_PASSWORD=<云端 ClickHouse 密码>
```

然后执行：

```powershell
.\scripts\start-dev.ps1
```

当前脚本默认等价于 `-StorageMode clickhouse -DatabaseMode remote`。

脚本会跳过本地 PostgreSQL 容器；本地后端的 Flyway、网页 Session、应用管理、上报和查询都使用云端调试数据库。数据库公网映射只适用于当前调试环境，正式环境应删除并改用内网、VPN 或 SSH 隧道。
