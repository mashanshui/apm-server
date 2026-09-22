[CmdletBinding()]
param(
    [string]$RemoteHost = '124.221.252.121',

    [string]$RemoteUser = 'ubuntu',

    [int]$RemotePort = 22,

    [string]$IdentityFile = (Join-Path $env:USERPROFILE '.ssh\apm_cloud'),

    [string]$RemoteDirectory = '/home/ubuntu/apm-server',

    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$frontendRoot = Join-Path $repoRoot 'frontend'
$gradleWrapper = Join-Path $backendRoot 'gradlew.bat'
$frontendPackage = Join-Path $frontendRoot 'package.json'
$frontendNodeModules = Join-Path $frontendRoot 'node_modules'
$deployRoot = Join-Path $repoRoot 'build\cloud-deploy'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$stagingRoot = Join-Path $deployRoot "staging-$runId"
$archiveName = "apm-server-cloud-$runId.zip"
$archivePath = Join-Path $deployRoot $archiveName

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "找不到命令 $Name，请先安装并配置对应工具。"
    }
}

function Test-Java21Home {
    param([string]$JavaHome)

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $false
    }

    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        return $false
    }

    $version = (& $javaExecutable -version 2>&1 | Out-String)
    return $version -match 'version\s+"21(?:[.\"]|$)'
}

function Resolve-Java21Home {
    $configuredJavaHome = [Environment]::GetEnvironmentVariable('APM_JAVA_HOME', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($configuredJavaHome)) {
        $configuredJavaHome = $configuredJavaHome.Trim()
        if (-not (Test-Java21Home -JavaHome $configuredJavaHome)) {
            throw "APM_JAVA_HOME 必须指向可用的 JDK 21：$configuredJavaHome"
        }
        return (Resolve-Path -LiteralPath $configuredJavaHome).ProviderPath
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add($env:JAVA_HOME.Trim())
    }

    $javaInstallRoot = Join-Path $env:ProgramFiles 'Java'
    if (Test-Path -LiteralPath $javaInstallRoot) {
        foreach ($javaHome in @(Get-ChildItem -LiteralPath $javaInstallRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
            $candidates.Add($javaHome.FullName)
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Java21Home -JavaHome $candidate) {
            return (Resolve-Path -LiteralPath $candidate).ProviderPath
        }
    }

    throw '未找到可用的 JDK 21。请设置 APM_JAVA_HOME，或安装到 C:\Program Files\Java。'
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$FailureMessage,
        [string]$WorkingDirectory
    )

    if ([string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        & $FilePath @ArgumentList
    } else {
        Push-Location $WorkingDirectory
        try {
            & $FilePath @ArgumentList
        } finally {
            Pop-Location
        }
    }

    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage（退出码 $LASTEXITCODE）"
    }
}

function Invoke-RemoteCommand {
    param([Parameter(Mandatory = $true)][string]$Command)

    $sshArguments = @(
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=15',
        '-o', 'IdentitiesOnly=yes',
        '-i', $IdentityFile,
        '-p', $RemotePort.ToString(),
        ("{0}@{1}" -f $RemoteUser, $RemoteHost),
        $Command
    )
    & ssh.exe @sshArguments
    if ($LASTEXITCODE -ne 0) {
        throw "云端命令执行失败（退出码 $LASTEXITCODE）。"
    }
}

Assert-Command -Name 'ssh.exe'
Assert-Command -Name 'scp.exe'
Assert-Command -Name 'npm.cmd'

if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "找不到 SSH 私钥：$IdentityFile"
}
if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "找不到 Gradle Wrapper：$gradleWrapper"
}
if (-not (Test-Path -LiteralPath $frontendPackage)) {
    throw "找不到前端 package.json：$frontendPackage"
}
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'compose.cloud.yaml'))) {
    throw '找不到 compose.cloud.yaml。'
}
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'docker'))) {
    throw '找不到 docker 部署目录。'
}
if ($RemoteHost -notmatch '^[A-Za-z0-9._-]+$') {
    throw "RemoteHost 不是允许的主机名或 IP：$RemoteHost"
}
if ($RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
    throw "RemoteUser 不是允许的用户名：$RemoteUser"
}
if ($RemoteDirectory -notmatch '^/home/[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+$') {
    throw "RemoteDirectory 不是允许的云端项目目录：$RemoteDirectory"
}

$env:JAVA_HOME = Resolve-Java21Home
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.gradle-local'

# 保留外部环境中已有的 Gradle 镜像等选项，只在没有指定时补充系统 Maven Local。
# 内部 processor 由发布流程安装到当前 Windows 用户的 Maven Local，不能指向仓库内不存在的临时目录。
$systemMavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
$mavenRepositoryOption = "-Dmaven.repo.local=$systemMavenRepository"
if ([string]::IsNullOrWhiteSpace($env:GRADLE_OPTS)) {
    $env:GRADLE_OPTS = $mavenRepositoryOption
} elseif ($env:GRADLE_OPTS -notmatch [regex]::Escape('-Dmaven.repo.local=')) {
    $env:GRADLE_OPTS = "$env:GRADLE_OPTS $mavenRepositoryOption"
}

if (-not $SkipBuild) {
    Write-Host '开始构建后端 Boot JAR（不运行测试）。'
    Invoke-CheckedCommand -FilePath $gradleWrapper -ArgumentList @('-p', $backendRoot, '--no-daemon', 'bootJar') -FailureMessage '后端 Boot JAR 构建失败' -WorkingDirectory $repoRoot

    if (-not (Test-Path -LiteralPath $frontendNodeModules)) {
        Write-Host '前端依赖不存在，执行 npm ci。'
        Invoke-CheckedCommand -FilePath 'npm.cmd' -ArgumentList @('ci', '--no-audit', '--no-fund') -FailureMessage '前端依赖安装失败' -WorkingDirectory $frontendRoot
    }

    Write-Host '开始构建前端静态资源（不运行测试）。'
    Invoke-CheckedCommand -FilePath 'npm.cmd' -ArgumentList @('run', 'build') -FailureMessage '前端构建失败' -WorkingDirectory $frontendRoot
}

$jarDirectory = Join-Path $backendRoot 'build\libs'
$jarFiles = @(Get-ChildItem -LiteralPath $jarDirectory -Filter '*-SNAPSHOT.jar' -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -notlike '*-plain.jar'
})
if ($jarFiles.Count -ne 1) {
    throw "后端部署 JAR 数量应为 1，实际为 $($jarFiles.Count)。请先执行 bootJar。"
}

$frontendDist = Join-Path $frontendRoot 'dist'
$clickHouseScripts = Join-Path $backendRoot 'src\main\resources\db\clickhouse'
if (-not (Test-Path -LiteralPath $frontendDist)) {
    throw '找不到 frontend/dist，请先执行前端构建。'
}
if (-not (Test-Path -LiteralPath $clickHouseScripts)) {
    throw '找不到 ClickHouse 初始化脚本目录。'
}

New-Item -ItemType Directory -Path $deployRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'backend\build\libs') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'frontend') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'backend\src\main\resources\db') -Force | Out-Null

# 只复制运行镜像和 ClickHouse 初始化所需文件，不复制源码、.env.cloud、缓存和 IDE 文件。
Copy-Item -LiteralPath ($jarFiles[0].FullName) -Destination (Join-Path $stagingRoot 'backend\build\libs')
Copy-Item -LiteralPath $frontendDist -Destination (Join-Path $stagingRoot 'frontend') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'docker') -Destination $stagingRoot -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'compose.cloud.yaml') -Destination $stagingRoot
Copy-Item -LiteralPath (Join-Path $repoRoot '.dockerignore') -Destination $stagingRoot
Copy-Item -LiteralPath $clickHouseScripts -Destination (Join-Path $stagingRoot 'backend\src\main\resources\db') -Recurse

Compress-Archive -Path (Join-Path $stagingRoot '*') -DestinationPath $archivePath -CompressionLevel Optimal -Force
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
$remoteArchive = "/tmp/$archiveName"
$remoteStage = "/tmp/apm-server-cloud-$runId"

Write-Host "部署包：$archivePath"
Write-Host "部署包 SHA-256：$archiveHash"
Write-Host ("上传到：{0}@{1}:{2}" -f $RemoteUser, $RemoteHost, $remoteArchive)

$scpArguments = @(
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ConnectTimeout=15',
    '-o', 'IdentitiesOnly=yes',
    '-i', $IdentityFile,
    '-P', $RemotePort.ToString(),
    $archivePath,
    ("{0}@{1}:{2}" -f $RemoteUser, $RemoteHost, $remoteArchive)
)
& scp.exe @scpArguments
if ($LASTEXITCODE -ne 0) {
    throw "部署包上传失败（退出码 $LASTEXITCODE）。"
}

# 部署后等待 Compose 健康检查，只有所有服务就绪后才报告成功。
$remoteHealthCheck = @'
# 最多等待 10 分钟，每 5 秒检查 PostgreSQL、ClickHouse、后端和 Web 容器。
health_timeout_seconds=600
health_poll_interval_seconds=5
health_elapsed_seconds=0
health_services='postgres clickhouse backend web'

while [ "$health_elapsed_seconds" -le "$health_timeout_seconds" ]; do
    all_services_healthy=true
    health_summary=''

    for service in $health_services; do
        if ! container_id="$(sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml ps -aq "$service")"; then
            service_status='compose-query-error'
        elif [ -z "$container_id" ]; then
            service_status='missing'
        elif ! service_status="$(sudo -n docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$container_id" 2>/dev/null)"; then
            service_status='inspect-error'
        fi

        health_summary="$health_summary $service=$service_status"
        if [ "$service_status" != 'running healthy' ]; then
            all_services_healthy=false
        fi
    done

    if [ "$all_services_healthy" = true ]; then
        break
    fi

    if [ "$((health_elapsed_seconds % 30))" -eq 0 ]; then
        printf '等待云端服务健康：已等待 %s 秒，当前状态：%s\n' "$health_elapsed_seconds" "$health_summary"
    fi

    if [ "$health_elapsed_seconds" -ge "$health_timeout_seconds" ]; then
        break
    fi

    sleep "$health_poll_interval_seconds"
    health_elapsed_seconds=$((health_elapsed_seconds + health_poll_interval_seconds))
done

if [ "$all_services_healthy" != true ]; then
    echo '云端服务在 600 秒内未全部达到 running/healthy，当前 Compose 状态：' >&2
    sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml ps --all
    exit 1
fi

echo 'PostgreSQL、ClickHouse、后端和 Web 容器均已 healthy。'
'@

# 远程只清理本次部署包和项目运行文件目录，保留 .env.cloud 与 Docker 数据卷。
$remoteCommand = @"
set -eu
rm -rf '$remoteStage'
mkdir -p '$remoteStage'
unzip -o -q '$remoteArchive' -d '$remoteStage'
mkdir -p '$RemoteDirectory'
rm -rf '$RemoteDirectory/backend/build/libs' '$RemoteDirectory/frontend/dist' '$RemoteDirectory/docker' '$RemoteDirectory/backend/src/main/resources/db/clickhouse'
mkdir -p '$RemoteDirectory/backend/build' '$RemoteDirectory/frontend' '$RemoteDirectory/backend/src/main/resources/db'
cp -a '$remoteStage/backend/build/libs' '$RemoteDirectory/backend/build/'
cp -a '$remoteStage/frontend/dist' '$RemoteDirectory/frontend/'
cp -a '$remoteStage/docker' '$RemoteDirectory/'
cp -a '$remoteStage/backend/src/main/resources/db/clickhouse' '$RemoteDirectory/backend/src/main/resources/db/'
cp -a '$remoteStage/compose.cloud.yaml' '$RemoteDirectory/'
cp -a '$remoteStage/.dockerignore' '$RemoteDirectory/'
rm -rf '$remoteStage' '$remoteArchive'
cd '$RemoteDirectory'
sudo -n docker compose --env-file .env.cloud -f compose.cloud.yaml up -d --build
$remoteHealthCheck
"@
Invoke-RemoteCommand -Command $remoteCommand

# 临时 staging 目录只用于打包，部署包本身保留在 build/cloud-deploy 便于回滚或追溯。
Remove-Item -LiteralPath $stagingRoot -Recurse -Force
Write-Host '云端部署命令与四个容器健康检查均已完成。脚本未执行测试或业务验收。'
Write-Host '如需业务验收，请单独检查业务接口与真实业务链路。'
