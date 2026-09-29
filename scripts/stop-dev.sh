#!/bin/bash
set -euo pipefail

# Compose 项目名称固定为 apm-server；down 只移除项目容器和网络，保留数据卷。
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/.env.local"
if [[ ! -f "$env_file" ]]; then
  echo '缺少 .env.local，无法可靠解析本地 Compose 配置。' >&2
  exit 1
fi
docker compose --env-file "$env_file" -f "$repo_root/compose.cloud.yaml" -f "$repo_root/compose.local.yaml" down
echo '本机 APM 服务已停止，数据库及附件数据卷已保留。'
