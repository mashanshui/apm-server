#!/bin/bash
set -euo pipefail

# 所有路径均按脚本位置解析，允许从任意目录执行。
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/.env.local"
build_requested=false

usage() {
  echo "用法：$0 [--build]"
  echo '默认复用已有 JAR 和前端产物；--build 强制重新构建。'
}

while (($#)); do
  case "$1" in
    --build) build_requested=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "不支持的参数：$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ ! -f "$env_file" ]]; then
  echo '缺少 .env.local。请先配置数据库、ClickHouse、管理员及稳定的 APM_APP_KEY_ENCRYPTION_KEY。' >&2
  exit 1
fi
if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
  echo 'Docker Desktop 尚未就绪。' >&2
  exit 1
fi

# 校验启动必需配置；不输出任何密钥值。
python3 - "$env_file" <<'PY'
import base64
import pathlib
import sys

values = {}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    if not line or line.lstrip().startswith('#') or '=' not in line:
        continue
    name, value = line.split('=', 1)
    values[name.strip()] = value.strip().strip('"\'')
required = ('APM_DATABASE_NAME', 'APM_DATABASE_USERNAME', 'APM_DATABASE_PASSWORD',
            'CLICKHOUSE_DATABASE', 'CLICKHOUSE_USERNAME', 'CLICKHOUSE_PASSWORD',
            'APM_APP_KEY_ENCRYPTION_KEY', 'APM_DEVICE_HASH_SALT',
            'APM_BOOTSTRAP_ADMIN_EMAIL', 'APM_BOOTSTRAP_ADMIN_PASSWORD')
missing = [name for name in required if not values.get(name)]
if missing:
    sys.exit('缺少配置：' + ', '.join(missing))
try:
    if len(base64.b64decode(values['APM_APP_KEY_ENCRYPTION_KEY'], validate=True)) != 32:
        raise ValueError()
except (ValueError, KeyError):
    sys.exit('APM_APP_KEY_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节固定密钥。')
PY

# 当前镜像只打包预构建的 JAR 和 dist；缺失或明确要求时在本机生成。
jar_files=("$repo_root"/backend/build/libs/*-SNAPSHOT.jar)
if $build_requested || [[ ! -f "${jar_files[0]}" ]]; then
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
fi
if $build_requested || [[ ! -f "$repo_root/frontend/dist/index.html" ]]; then
  if command -v npm >/dev/null; then
    (cd "$repo_root/frontend" && npm ci --no-audit --no-fund && npm run build)
  else
    docker run --rm -v "$repo_root/frontend:/work" -v apm-node-modules:/work/node_modules -w /work node:22-alpine sh -c 'npm ci --no-audit --no-fund && npm run build'
  fi
fi

compose=(docker compose --env-file "$env_file" -f "$repo_root/compose.cloud.yaml" -f "$repo_root/compose.local.yaml")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --build --wait --wait-timeout 600
web_port="$(awk -F= '$1 == "WEB_PORT" {print $2}' "$env_file" | tail -n 1)"
echo "服务已就绪：http://127.0.0.1:${web_port:-8088}/login"
echo '停止服务：./scripts/stop-dev.sh'
