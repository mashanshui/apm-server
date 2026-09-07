# ClickHouse 本地初始化

完整的 Grafana/ClickHouse 安装、只读用户、Dashboard 导入和 Spring Boot 接入流程见[《Grafana/ClickHouse 安装与项目接入教程》](grafana-clickhouse-install.md)。

服务默认使用 `apm.storage.mode=clickhouse`，启动前需要准备 ClickHouse，并按版本顺序执行 `001_crash_schema.sql`、`002_jank_schema.sql`、`003_jank_sampling_quality.sql` 和 `004_application_identity_schema.sql`。`004` 要求旧业务表为空，并将租户身份切换为 UUID `app_id`；旧项目数据不会自动迁移。如需不依赖外部数据库的临时联调，可显式设置 `APM_STORAGE_MODE=memory`：

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
