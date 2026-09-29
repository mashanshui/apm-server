# Grafana/ClickHouse 安装与项目接入教程

本文用于在本地启动 ClickHouse 和 Grafana，并接入本项目的 JVM Crash Dashboard。示例以 Windows Docker Desktop 和 PowerShell 为主；Linux/macOS 只需要把 PowerShell 的换行符和环境变量写法替换成对应 Shell 写法。

本教程面向本地开发、接口联调和 Dashboard 验收，不等同于生产部署方案。生产环境还需要固定镜像版本、配置 TLS、限制网络暴露、设置备份和监控，并完成多租户权限设计。

## 一、先了解本项目的默认状态

- 服务默认使用 `apm.storage.mode=clickhouse`，启动 Grafana/ClickHouse 后，上报数据会写入 ClickHouse；如需无外部依赖联调，可通过 `APM_STORAGE_MODE=memory` 临时覆盖。
- ClickHouse 表结构脚本是 `backend/src/main/resources/db/clickhouse/001_crash_schema.sql`。
- 现有 Dashboard 文件是 `backend/src/main/resources/grafana/dashboards/jvm-crash.json`。
- 本文中的 ClickHouse 容器通过 HTTP `8123` 端口接收 Spring Boot 和 Grafana 请求，也暴露 Native `9000` 端口供需要 Native 协议的客户端使用。
- 当前 Dashboard JSON 声明的是 Altinity/Vertamedia 数据源类型 `vertamedia-clickhouse-datasource`。如果只安装 Grafana Labs 的新插件 `grafana-clickhouse-datasource`，导入 JSON 时可能出现“未知数据源类型”；下文默认安装与仓库 JSON 兼容的 Altinity 插件，并在后文说明新插件的处理方式。

## 二、准备环境

安装 Docker Desktop 并确认 Docker 引擎正常运行：

```powershell
docker version
docker compose version
```

确保本机的 `3000`、`8123` 和 `9000` 端口没有被其他程序占用：

| 组件 | 容器端口 | 本机访问地址 | 用途 |
|---|---:|---|---|
| ClickHouse HTTP | 8123 | `http://localhost:8123` | Spring Boot、Grafana 查询 |
| ClickHouse Native | 9000 | `localhost:9000` | Native 协议客户端 |
| Grafana | 3000 | `http://localhost:3000` | Dashboard 页面 |

## 三、启动 ClickHouse

### 1. 创建 Docker 网络和管理员密码

Grafana 与 ClickHouse 都加入同一个 Docker 网络后，Grafana 可以通过容器名 `apm-clickhouse` 访问 ClickHouse。密码只保存在当前终端的环境变量中，不要把真实密码写进 Git。

```powershell
docker network create apm-network

$env:CH_ADMIN_PASSWORD = "请替换为本地管理员密码"
```

如果网络已经存在，`docker network create` 报错可以忽略。

### 2. 启动 ClickHouse 容器

```powershell
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
  clickhouse/clickhouse-server:latest
```

本地快速验证可以使用 `latest`。生产环境应改成经过验证的固定版本号，并在升级前验证 SQL、驱动和 Dashboard 兼容性。

等待容器完成启动后检查 HTTP 接口：

```powershell
(Invoke-WebRequest -UseBasicParsing http://localhost:8123/ping).Content
```

返回 `Ok.` 表示 ClickHouse HTTP 服务已经可以访问。若暂时连接失败，先查看日志并等待几秒：

```powershell
docker logs apm-clickhouse --tail 100
```

### 3. 执行项目表结构

在仓库根目录执行：

```powershell
Get-Content -Raw backend/src/main/resources/db/clickhouse/001_crash_schema.sql |
  docker exec -i apm-clickhouse clickhouse-client `
    --user apm_admin `
    --password $env:CH_ADMIN_PASSWORD `
    --multiquery
```

确认数据库和表已经创建：

```powershell
docker exec apm-clickhouse clickhouse-client `
  --user apm_admin `
  --password $env:CH_ADMIN_PASSWORD `
  --query "SHOW TABLES FROM apm"
```

应至少看到以下对象：

- `apm_event_raw`
- `apm_crash_detail`
- `apm_event_hourly`
- `apm_crash_issue_hourly`
- 两个物化视图

重复执行初始化脚本是安全的，脚本中的对象创建使用了 `IF NOT EXISTS`。如果修改了已有表结构，不能只重复执行脚本，应新增版本化迁移或按变更方案处理。

### 4. 创建 Grafana 只读用户

Grafana 不应使用 ClickHouse 管理员账号。先进入 ClickHouse 客户端：

```powershell
docker exec -it apm-clickhouse clickhouse-client `
  --user apm_admin `
  --password $env:CH_ADMIN_PASSWORD
```

在客户端中执行下面的 SQL，并把占位密码替换成自己的本地密码：

```sql
CREATE USER IF NOT EXISTS apm_grafana;
ALTER USER apm_grafana IDENTIFIED WITH plaintext_password BY '<本地只读密码>';
GRANT SELECT ON apm.* TO apm_grafana;
```

执行 `exit` 退出客户端。只读用户只需要访问 Dashboard 所用的库表；如果使用数据源的系统表浏览、自动补全或插件自带的 ClickHouse 运维 Dashboard，再按实际需要补充 `system.tables`、`system.columns` 等最小权限。

## 四、启动 Grafana

仓库现有 Dashboard 使用 Altinity/Vertamedia 数据源类型，因此本地快速接入时预装对应插件：

```powershell
$env:GRAFANA_ADMIN_PASSWORD = "请替换为本地 Grafana 管理员密码"

docker run -d `
  --name apm-grafana `
  --network apm-network `
  --restart unless-stopped `
  -p 3000:3000 `
  -e GF_SECURITY_ADMIN_USER=admin `
  -e GF_SECURITY_ADMIN_PASSWORD=$env:GRAFANA_ADMIN_PASSWORD `
  -e GF_PLUGINS_PREINSTALL=vertamedia-clickhouse-datasource `
  -v apm-grafana-data:/var/lib/grafana `
  grafana/grafana:latest
```

浏览器打开 <http://localhost:3000>，使用上面设置的 `admin` 密码登录。Grafana 数据保存在 `apm-grafana-data` 卷中，删除容器不会删除该卷。

## 五、在 Grafana 中添加 ClickHouse 数据源

进入 `Connections` → `Add new connection`，搜索并选择 `ClickHouse`，填写以下参数。不同插件版本的字段名称可能略有差异，但连接含义相同：

| 配置项 | 值 |
|---|---|
| Server/Host | `apm-clickhouse` |
| Protocol | `HTTP` |
| Port | `8123` |
| Username | `apm_grafana` |
| Password | 创建只读用户时设置的密码 |
| Default database | `apm` |
| TLS/Secure connection | 本地关闭 |

Grafana 在容器中运行，`Server/Host` 必须填 `apm-clickhouse`，不能填 `localhost`；对 Grafana 容器来说，`localhost` 指的是 Grafana 自己。

点击 `Save & test`。测试通过后，可以在 `Explore` 中执行：

```sql
SELECT count() AS event_count
FROM apm.apm_event_raw;
```

没有上报数据时返回 `0` 是正常的；这只能证明连接和表权限正常。

## 六、导入项目 Dashboard

1. 进入 `Dashboards` → `New` → `Import`。
2. 选择 `Upload dashboard JSON file`，上传 `backend/src/main/resources/grafana/dashboards/jvm-crash.json`。
3. 在数据源映射下拉框中选择刚创建的 ClickHouse 数据源。
4. 导入后把 `应用` 变量设置为目标应用的 UUID `appId`，并选择合适的时间范围。
5. 通过 `Explore` 或接口上报一批测试事件后，再查看总览、小时趋势、问题排行和版本对比。

### 官方 Grafana ClickHouse 插件的选择

Grafana Labs 目前维护的插件 ID 是 `grafana-clickhouse-datasource`，支持 HTTP/Native 两种协议。若希望使用该插件，可以将启动 Grafana 时的预装变量改为：

```powershell
-e GF_PLUGINS_PREINSTALL=grafana-clickhouse-datasource
```

但当前仓库 JSON 使用的是 `vertamedia-clickhouse-datasource`。使用官方插件时有两种做法：

- 导入时把数据源类型映射为官方 ClickHouse 数据源；
- 复制一份 JSON，将所有 `vertamedia-clickhouse-datasource` 替换为 `grafana-clickhouse-datasource` 后再导入。

如果替换后面板仍然报查询格式错误，应在 Grafana 查询编辑器中检查查询格式和时间序列字段。不要同时让同一个 Dashboard 依赖两个 ClickHouse 插件，避免数据源类型和宏行为混淆。

## 七、让 Spring Boot 使用 ClickHouse

当前应用默认使用 ClickHouse。若 Spring Boot 在本机运行，使用 `localhost`；若 Spring Boot 也放在 Docker 网络中，则把 URL 改成 `http://apm-clickhouse:8123`。

在同一个 PowerShell 窗口中设置：

```powershell
$env:APM_STORAGE_MODE = "clickhouse"
$env:APM_CLICKHOUSE_ENABLED = "true"
$env:CLICKHOUSE_URL = "http://localhost:8123"
$env:CLICKHOUSE_DATABASE = "apm"
$env:CLICKHOUSE_USERNAME = "apm_admin"
$env:CLICKHOUSE_PASSWORD = $env:CH_ADMIN_PASSWORD

./backend/gradlew.bat -p backend bootRun
```

macOS 本地全栈可直接使用仓库启动脚本。脚本通过 Compose 读取 `.env.local`，启动本机 PostgreSQL、ClickHouse、后端和 Web；配置格式见[部署与运维](knowledge-base/07-部署与运维.md)。

```bash
./scripts/start-dev.sh
```

本项目的 Crash 与卡顿 ClickHouse 适配器共享 `ClickHouseHttpClient`，并使用 `apm.clickhouse.username` 完成写入和查询，因此这里必须使用具备 `SELECT` 和 `INSERT` 权限的应用账号，不能把 Grafana 的 `apm_grafana` 只读账号直接填给 Spring Boot。生产环境应进一步拆分应用写入账号、后端查询账号和 Grafana 账号；当前配置中的 `apm.clickhouse.read-only-user` 只是账号约定，不能替代 Spring Boot 实际使用的连接账号。

启动后再次上报事件，并在 ClickHouse 中检查：

```powershell
docker exec apm-clickhouse clickhouse-client `
  --user apm_admin `
  --password $env:CH_ADMIN_PASSWORD `
  --query "SELECT count() FROM apm.apm_event_raw"
```

如果数量仍为 `0`，先确认客户端确实请求成功，再检查应用日志、App Key、请求应用 UUID 和时间范围。Grafana Dashboard 不再内置默认应用。

## 八、常见问题

| 现象 | 原因和处理 |
|---|---|
| Grafana 报 `connection refused` | ClickHouse 尚未启动完成，或把容器内地址写成了 `localhost`；Grafana 数据源主机应为 `apm-clickhouse`。 |
| `http://localhost:8123/ping` 失败 | 检查 `docker ps`、`docker logs apm-clickhouse` 和本机端口占用。 |
| Grafana 报未知数据源类型 | 现有 JSON 使用 `vertamedia-clickhouse-datasource`；安装 Altinity 插件，或按上文改用官方插件并替换 JSON 类型。 |
| `Not enough privileges` | 使用 `SHOW GRANTS FOR apm_grafana` 检查权限，确认已执行 `GRANT SELECT ON apm.* TO apm_grafana`。 |
| Dashboard 没有数据 | 还没有事件、连接配置错误，或显式设置成了内存模式；确认 `APM_STORAGE_MODE=clickhouse` 并重启 Spring Boot。 |
| 初始化脚本重复执行后仍缺表 | 现有数据卷可能来自旧版本；先查看容器日志和表名，不要直接删除生产数据卷。 |
| 重新创建容器后数据还在 | 数据保存在 Docker volume 中，这是预期行为。 |

查看容器状态：

```powershell
docker ps -a --filter "name=apm-clickhouse" --filter "name=apm-grafana"
```

## 九、停止、重启和清理

停止但保留数据：

```powershell
docker stop apm-grafana apm-clickhouse
```

下次继续使用：

```powershell
docker start apm-clickhouse apm-grafana
```

如果只想删除容器、保留数据卷：

```powershell
docker rm -f apm-grafana apm-clickhouse
```

只有在明确确认不再需要本地数据时，才删除数据卷：

```powershell
docker volume rm apm-grafana-data apm-clickhouse-data
```

删除 ClickHouse 数据卷会永久清除本地事件和表数据，生产环境不要执行这条命令。

## 十、官方参考资料

- [ClickHouse 官方 Docker 镜像](https://hub.docker.com/r/clickhouse/clickhouse-server/)
- [Grafana Docker 安装文档](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)
- [Grafana Labs ClickHouse 数据源文档](https://grafana.com/docs/plugins/grafana-clickhouse-datasource/latest/configure/)
- [Altinity ClickHouse 数据源插件](https://grafana.com/grafana/plugins/vertamedia-clickhouse-datasource/)
- [Docker Compose 安装文档](https://docs.docker.com/compose/install/)
