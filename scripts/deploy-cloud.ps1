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
"@
Invoke-RemoteCommand -Command $remoteCommand

# 临时 staging 目录只用于打包，部署包本身保留在 build/cloud-deploy 便于回滚或追溯。
Remove-Item -LiteralPath $stagingRoot -Recurse -Force
Write-Host '云端部署命令已完成。脚本未执行测试、容器状态检查、健康检查或业务验收。'
Write-Host '如需验收，请单独检查云端 Compose、/healthz 和业务接口。'
