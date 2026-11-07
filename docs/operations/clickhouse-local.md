# ClickHouse 本地初始化

本文维护独立 ClickHouse 的手工启动、空库初始化与后端连接。默认五服务 Compose 的启停见[部署与运维](../knowledge-base/07-部署与运维.md)；使用 Compose 时不必再创建以下独立容器。Grafana 的只读账号、数据源和 Dashboard 见[Grafana 接入教程](grafana-clickhouse-install.md)。

以下手工示例使用 Windows Docker Desktop 和 PowerShell，在仓库根目录执行；macOS 全栈入口使用平台部署页的 Shell 脚本。命令是操作方法，不代表本轮已重验数据库或部署。

## 手工启动独立容器

先检查 Docker 及本机 8123/9000 端口，确认没有与现有 Compose 冲突：

```powershell
docker version
docker compose version
```

Grafana 和 ClickHouse 使用同一个 `apm-network`，Grafana 可通过容器名 `apm-clickhouse` 连接。网络已存在时复用，不重复创建；管理员密码只放在当前终端环境变量中，不提交到 Git。

```powershell
docker network create apm-network

# 只在当前终端保存本地管理员密码，后续初始化命令复用此变量。
$env:CH_ADMIN_PASSWORD = "请替换为本地管理员密码"

docker run -d `
  --name apm-clickhouse `
  --network apm-network `
  --restart unless-stopped `
  --ulimit nofile=262144:262144 `
  -p 8123:8123 `
  -p 9000:9000 `
  -e CLICKHOUSE_DB=apm `
  -e CLICKHOUSE_USER=apm_admin `
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 `
  -e CLICKHOUSE_PASSWORD=$env:CH_ADMIN_PASSWORD `
  -v apm-clickhouse-data:/var/lib/clickhouse `
  clickhouse/clickhouse-server:26.7.3.19

(Invoke-WebRequest -UseBasicParsing http://localhost:8123/ping).Content
```

示例镜像与仓库 Compose 保持同一固定版本。`/ping` 返回 `Ok.` 只证明 HTTP 可访问；需要排障时查看 `docker logs apm-clickhouse --tail 100`。

## 空库初始化与已有数据边界

当前应用需要完整的 `001～008` 结构，不能只执行 Crash 的 `001`。仅对确认无业务数据的新库，按以下顺序执行：

```powershell
# 严格按版本排序；004 会校验十张受影响业务表为空并删除重建对象。
$schemaFiles = @(
  "001_crash_schema.sql",
  "002_jank_schema.sql",
  "003_jank_sampling_quality.sql",
  "004_application_identity_schema.sql",
  "005_memory_metrics.sql",
  "006_memory_leak_reports.sql",
  "007_event_process_identity.sql",
  "008_background_crash_symbolication.sql"
)

# 任一步失败立即停止，不继续执行后续迁移。
foreach ($schemaFile in $schemaFiles) {
  Get-Content -Raw -ErrorAction Stop "backend/src/main/resources/db/clickhouse/$schemaFile" |
    docker exec -i apm-clickhouse clickhouse-client `
      --user apm_admin `
      --password $env:CH_ADMIN_PASSWORD `
      --multiquery
  if ($LASTEXITCODE -ne 0) {
    throw "ClickHouse 初始化失败：$schemaFile"
  }
}

docker exec apm-clickhouse clickhouse-client `
  --user apm_admin `
  --password $env:CH_ADMIN_PASSWORD `
  --query "SHOW TABLES FROM apm"
```

`004` 将应用身份切换为 UUID `app_id`，旧数据不会自动迁移；有数据时不得重跑整套初始化或通过清空表绕过门禁。`005/006` 新增内存相关表，`007` 为六类事件/报告表追加 `process_id`，不改变排序键、TTL、去重键或聚合维度。008 独立新增当前 Crash 事实/状态，旧数据不转换或删除；已有 V7 库只增量执行 008。已有库应先核对当前结构、已执行版本和数据，再按[持久化与数据库迁移](../../backend/docs/knowledge-base/03-持久化与数据库迁移.md)处理。不能把部分脚本的 `IF NOT EXISTS` 推广为全套可安全重跑。

## 后端连接

当前默认使用 `apm.storage.mode=clickhouse`。本机后端用 `http://localhost:8123`；同一手工 Docker 网络中的后端用 `http://apm-clickhouse:8123`，Compose 内部则使用其服务名。后端账号必须具备 `SELECT` 和 `INSERT`，Grafana 使用独立只读账号；`apm.clickhouse.read-only-user` 不能替代后端实际连接账号。

独立后端仍需 PostgreSQL 和身份配置，完整要求见[后端构建与运行](../../backend/docs/knowledge-base/05-构建配置与本地运行.md)。以下示例在创建容器的同一 PowerShell 窗口执行：

```powershell
# 连接本机独立 ClickHouse，密码复用当前终端已设置的值。
$env:APM_STORAGE_MODE = "clickhouse"
$env:APM_CLICKHOUSE_ENABLED = "true"
$env:CLICKHOUSE_URL = "http://localhost:8123"
$env:CLICKHOUSE_DATABASE = "apm"
$env:CLICKHOUSE_USERNAME = "apm_admin"
$env:CLICKHOUSE_PASSWORD = $env:CH_ADMIN_PASSWORD

./backend/gradlew.bat -p backend bootRun
```

若仅需无外部遥测数据库的临时联调，可显式设置 `APM_STORAGE_MODE=memory`；这不等于完成真实 ClickHouse 验收。

## 停止与保留数据

独立容器可先停止、下次再启动；数据仍保存在 `apm-clickhouse-data` 卷中：

```powershell
docker stop apm-clickhouse
docker start apm-clickhouse
```

停止后执行 `docker rm apm-clickhouse` 只删除容器，保留数据卷。只有明确确认不再需要本地事件和表结构时，才删除该卷；不能将其作为重启、升级或迁移失败的默认处理：

```powershell
docker volume rm apm-clickhouse-data
```

上述仅针对手工创建的独立容器；Compose 服务使用平台启停入口，数据卷名称和保留约束见[部署与运维](../knowledge-base/07-部署与运维.md#数据保护与迁移)。

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

上述配置用于单独运行本机后端，不适用于 `scripts/start-dev.sh` 全栈脚本。全栈脚本固定使用本机 Docker 数据库。需直连云端调试库时，在当前 shell 中导出相应变量，再执行 `bash ./backend/gradlew -p backend bootRun`；此时本地后端的 Flyway、网页 Session、应用管理、上报和查询都使用云端调试数据。数据库公网映射只适用于当前调试环境，正式环境应删除并改用内网、VPN 或 SSH 隧道。
