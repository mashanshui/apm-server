#!/bin/bash
set -euo pipefail

# 云端部署参数只允许受限字符，防止将路径或主机参数解释为远端命令。
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
remote_host='124.221.252.121'
remote_user='ubuntu'
remote_port='22'
identity_file="$HOME/.ssh/apm_cloud"
remote_directory='/home/ubuntu/apm-server'
skip_build=false

usage() {
  echo "用法：$0 [--skip-build] [--remote-host HOST] [--remote-user USER] [--remote-port PORT] [--identity-file PATH] [--remote-directory PATH]"
}

while (($#)); do
  case "$1" in
    --skip-build) skip_build=true; shift ;;
    --remote-host|--remote-user|--remote-port|--identity-file|--remote-directory)
      if (($# < 2)); then usage >&2; exit 2; fi
      case "$1" in
        --remote-host) remote_host="$2" ;;
        --remote-user) remote_user="$2" ;;
        --remote-port) remote_port="$2" ;;
        --identity-file) identity_file="$2" ;;
        --remote-directory) remote_directory="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "不支持的参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$remote_host" =~ ^[A-Za-z0-9._-]+$ ]] || { echo '云端主机名无效。' >&2; exit 2; }
[[ "$remote_user" =~ ^[A-Za-z0-9._-]+$ ]] || { echo '云端用户名无效。' >&2; exit 2; }
[[ "$remote_port" =~ ^[0-9]+$ ]] && ((remote_port >= 1 && remote_port <= 65535)) || { echo 'SSH 端口无效。' >&2; exit 2; }
[[ "$remote_directory" =~ ^/home/[A-Za-z0-9._-]+/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$ ]] || { echo '云端部署目录无效。' >&2; exit 2; }
[[ -f "$identity_file" ]] || { echo "找不到 SSH 私钥：$identity_file" >&2; exit 1; }
for command_name in ssh scp zip shasum docker; do
  command -v "$command_name" >/dev/null || { echo "缺少命令：$command_name" >&2; exit 1; }
done

# 本地构建只使用 JDK 21 和当前用户 Maven Local；前端没有 npm 时使用 Docker Node 镜像。
if ! $skip_build; then
  if [[ -n "${APM_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$APM_JAVA_HOME"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  fi
  if ! "$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21([.\"]|$)'; then
    echo '需要 JDK 21；可设置 APM_JAVA_HOME。' >&2
    exit 1
  fi
  export GRADLE_USER_HOME="$repo_root/.gradle-local"
  bash "$repo_root/backend/gradlew" -p "$repo_root/backend" --no-daemon bootJar
  if command -v npm >/dev/null; then
    (cd "$repo_root/frontend" && npm ci --no-audit --no-fund && npm run build)
  else
    docker run --rm -v "$repo_root/frontend:/work" -v apm-node-modules:/work/node_modules -w /work node:22-alpine sh -c 'npm ci --no-audit --no-fund && npm run build'
  fi
fi

# 仅打包镜像构建必需文件；云端 .env.cloud 保持原位且不会进入部署包。
jar_files=("$repo_root"/backend/build/libs/*-SNAPSHOT.jar)
if ((${#jar_files[@]} != 1)) || [[ ! -f "${jar_files[0]}" ]]; then
  echo '后端部署 JAR 数量必须为 1，请先执行 bootJar。' >&2
  exit 1
fi
[[ -f "$repo_root/frontend/dist/index.html" ]] || { echo '缺少 frontend/dist，请先构建前端。' >&2; exit 1; }
run_id="$(date +%Y%m%d-%H%M%S)"
deploy_root="$repo_root/build/cloud-deploy"
staging_root="$deploy_root/staging-$run_id"
archive_path="$deploy_root/apm-server-cloud-$run_id.zip"
remote_archive="/tmp/apm-server-cloud-$run_id.zip"
mkdir -p "$staging_root/backend/build/libs" "$staging_root/backend/src/main/resources/db" "$staging_root/frontend" "$staging_root/mcp-server"
trap 'rm -rf "$staging_root"' EXIT
cp "${jar_files[0]}" "$staging_root/backend/build/libs/"
cp -R "$repo_root/frontend/dist" "$staging_root/frontend/"
cp -R "$repo_root/docker" "$staging_root/"
cp -R "$repo_root/backend/src/main/resources/db/clickhouse" "$staging_root/backend/src/main/resources/db/"
cp "$repo_root/mcp-server/Dockerfile" "$repo_root/mcp-server/package.json" "$repo_root/mcp-server/package-lock.json" "$repo_root/mcp-server/tsconfig.json" "$staging_root/mcp-server/"
cp -R "$repo_root/mcp-server/src" "$staging_root/mcp-server/"
cp "$repo_root/compose.cloud.yaml" "$repo_root/.dockerignore" "$staging_root/"
(cd "$staging_root" && zip -qr "$archive_path" .)
echo "部署包：$archive_path"
shasum -a 256 "$archive_path"

# 严格核验已知主机密钥；私钥与云端环境文件均不上传。
ssh_options=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -i "$identity_file")
scp "${ssh_options[@]}" -P "$remote_port" "$archive_path" "$remote_user@$remote_host:$remote_archive"
ssh "${ssh_options[@]}" -p "$remote_port" "$remote_user@$remote_host" bash -s -- "$remote_directory" "$remote_archive" <<'REMOTE'
set -euo pipefail
remote_directory="$1"
remote_archive="$2"
remote_stage="$(mktemp -d /tmp/apm-server-cloud.XXXXXXXX)"
trap 'rm -rf "$remote_stage" "$remote_archive"' EXIT
unzip -q "$remote_archive" -d "$remote_stage"
[[ -f "$remote_directory/.env.cloud" ]] || { echo '云端缺少 .env.cloud。' >&2; exit 1; }
mkdir -p "$remote_directory/backend/build" "$remote_directory/backend/src/main/resources/db" "$remote_directory/frontend"
rm -rf "$remote_directory/backend/build/libs" "$remote_directory/frontend/dist" "$remote_directory/docker" "$remote_directory/mcp-server" "$remote_directory/backend/src/main/resources/db/clickhouse"
cp -a "$remote_stage/backend/build/libs" "$remote_directory/backend/build/"
cp -a "$remote_stage/frontend/dist" "$remote_directory/frontend/"
cp -a "$remote_stage/docker" "$remote_directory/"
cp -a "$remote_stage/mcp-server" "$remote_directory/"
cp -a "$remote_stage/backend/src/main/resources/db/clickhouse" "$remote_directory/backend/src/main/resources/db/"
cp -a "$remote_stage/compose.cloud.yaml" "$remote_stage/.dockerignore" "$remote_directory/"
cd "$remote_directory"
sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml up -d --build

# 只在五项服务均为 running healthy 时报告部署完成，最长等待 10 分钟。
for ((attempt = 0; attempt <= 120; attempt++)); do
  all_healthy=true
  summary=''
  for service in postgres clickhouse backend mcp web; do
    container_id="$(sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml ps -q "$service")"
    if [[ -z "$container_id" ]]; then
      status='missing'
    else
      status="$(sudo -n docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$container_id")"
    fi
    summary="$summary $service=$status"
    [[ "$status" == 'running healthy' ]] || all_healthy=false
  done
  if $all_healthy; then
    echo 'PostgreSQL、ClickHouse、后端、MCP 和 Web 容器均已 healthy。'
    exit 0
  fi
  if ((attempt % 6 == 0)); then echo "等待云端服务健康：$summary"; fi
  if ((attempt < 120)); then sleep 5; fi
done
echo '云端服务在 600 秒内未全部健康。' >&2
sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml ps --all
exit 1
REMOTE
echo '云端部署和容器健康检查完成；业务接口需单独验收。'
