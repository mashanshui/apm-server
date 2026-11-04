# Grafana/ClickHouse 安装与项目接入教程

本文维护可选 Grafana 的只读账号、插件、数据源和 JVM Crash Dashboard 接入。默认 Compose 不启动 Grafana；平台启停见[部署与运维](../knowledge-base/07-部署与运维.md)，数据库与后端操作统一见[ClickHouse 本地初始化](clickhouse-local.md)。

以下为 Windows Docker Desktop/PowerShell 的独立容器联调方式，不代表生产部署或本轮已重验；生产 HTTPS、备份、权限隔离及容量沿用[当前边界](../knowledge-base/00-当前实现与验证边界.md)。

## 一、准备 ClickHouse

先按[手工启动](clickhouse-local.md#手工启动独立容器)与[空库初始化](clickhouse-local.md#空库初始化与已有数据边界)完成 ClickHouse，确认 `apm-network`、`apm-clickhouse` 和当前终端的 `CH_ADMIN_PASSWORD` 可用。当前结构需要 `001～007`，已有数据环境不得重跑整套初始化；`004` 的空表前提不能绕过。

Grafana 使用本机 3000 端口，通过 Docker 网络的 `apm-clickhouse:8123` 查询。现有 Dashboard 位于 `backend/src/main/resources/grafana/dashboards/jvm-crash.json`，声明数据源类型为 `vertamedia-clickhouse-datasource`；下文使用与该资源一致的插件。

## 二、创建 Grafana 只读用户

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

## 三、启动 Grafana

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

## 四、在 Grafana 中添加 ClickHouse 数据源

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

## 五、导入项目 Dashboard

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

## 六、常见问题

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

## 七、停止与保留数据

仅停止 Grafana、保留数据卷：

```powershell
docker stop apm-grafana
```

再次使用时执行 `docker start apm-grafana`。停止后执行 `docker rm apm-grafana` 只删除容器、保留 `apm-grafana-data`。只有明确确认不再需要本地配置和 Dashboard 时，才删除该卷，不能作为普通重启步骤：

```powershell
docker volume rm apm-grafana-data
```

独立 ClickHouse 的容器与 `apm-clickhouse-data` 卷见[停止与保留数据](clickhouse-local.md#停止与保留数据)；Compose 服务则使用平台启停入口。

## 八、官方参考资料

- [ClickHouse 官方 Docker 镜像](https://hub.docker.com/r/clickhouse/clickhouse-server/)
- [Grafana Docker 安装文档](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)
- [Grafana Labs ClickHouse 数据源文档](https://grafana.com/docs/plugins/grafana-clickhouse-datasource/latest/configure/)
- [Altinity ClickHouse 数据源插件](https://grafana.com/grafana/plugins/vertamedia-clickhouse-datasource/)
- [Docker Compose 安装文档](https://docs.docker.com/compose/install/)
