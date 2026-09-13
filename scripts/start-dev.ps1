[CmdletBinding()]
param(
    [ValidateSet('clickhouse', 'memory')]
    [string]$StorageMode = 'clickhouse',

    [ValidateSet('auto', 'local', 'remote')]
    [string]$DatabaseMode = 'remote',

    [switch]$NoFrontend,

    [string]$BootstrapAdminEmail,

    [string]$BootstrapAdminDisplayName,

    [switch]$PromptForBootstrapAdmin
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$frontendRoot = Join-Path $repoRoot 'frontend'
$runtimeRoot = Join-Path $repoRoot 'build\dev-services'
$statePath = Join-Path $runtimeRoot 'services.json'
$backendLog = Join-Path $runtimeRoot 'backend.log'
$frontendLog = Join-Path $runtimeRoot 'frontend.log'
$localEnvPath = Join-Path $repoRoot '.env.local'

function Import-LocalEnvironment {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            continue
        }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

Import-LocalEnvironment -Path $localEnvPath

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

    $javaInstallRoot = Join-Path ${env:ProgramFiles} 'Java'
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

    throw '未找到可用的 JDK 21。请安装 JDK 21，或设置被 .env.local 忽略的 APM_JAVA_HOME。'
}

$env:JAVA_HOME = Resolve-Java21Home
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
Write-Host "使用 JDK 21：$env:JAVA_HOME"

function Test-TcpPort {
    param([int]$Port)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $asyncResult = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $asyncResult.AsyncWaitHandle.WaitOne(300)) {
            return $false
        }
        $client.EndConnect($asyncResult)
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-TcpPort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -Port $Port) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-ProcessTree {
    param([int]$ProcessId)

    if ($ProcessId -le 0) {
        return
    }

    # Start-Process 使用 cmd -> Gradle -> Java 的多级进程链；Stop-Process
    # 只结束当前 PID，失败清理必须递归结束已确认属于本次启动的进程树。
    $taskKill = Get-Command taskkill.exe -ErrorAction SilentlyContinue
    if ($null -ne $taskKill) {
        & $taskKill.Source /PID $ProcessId /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return
        }
    }

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Get-ListeningProcessId {
    param([int]$Port)

    $lines = & netstat.exe -ano | Select-String -Pattern 'LISTENING'
    foreach ($line in $lines) {
        $columns = $line.Line.Trim() -split '\s+'
        if ($columns.Count -ge 5 -and $columns[1] -match ":$Port$" -and $columns[3] -eq 'LISTENING') {
            return [int]$columns[4]
        }
    }
    return $null
}

function Get-ProcessState {
    param([System.Diagnostics.Process]$Process)

    $current = Get-Process -Id $Process.Id -ErrorAction Stop
    return [ordered]@{
        pid       = $current.Id
        startTime = $current.StartTime.ToUniversalTime().ToString('o')
    }
}

function Convert-SecureStringToPlainText {
    param([System.Security.SecureString]$SecureString)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Stop-StartupProcess {
    param(
        [int]$LauncherId,
        [int]$ListenerId
    )

    if ($ListenerId -gt 0) {
        Stop-ProcessTree -ProcessId $ListenerId
    }
    if ($LauncherId -gt 0) {
        Stop-ProcessTree -ProcessId $LauncherId
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $backendRoot 'gradlew.bat'))) {
    throw "找不到 Gradle Wrapper：$backendRoot\gradlew.bat"
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'package.json'))) {
    throw "找不到前端项目：$frontendRoot"
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw '找不到 npm.cmd，请先安装 Node.js 并确认 npm 已加入 PATH'
}
if (Test-Path -LiteralPath $statePath) {
    $oldState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $oldEntries = @($oldState.backend, $oldState.frontend)
    $running = @($oldEntries | Where-Object {
        $_ -and $_.pid -gt 0 -and (Get-Process -Id ([int]$_.pid) -ErrorAction SilentlyContinue)
    })
    if ($running.Count -gt 0) {
        throw '已有由脚本启动的服务正在运行，请先执行 .\scripts\stop-dev.ps1'
    }
    Remove-Item -LiteralPath $statePath -Force
}

if (Test-TcpPort -Port 8080) {
    throw '8080 端口已被占用。请停止已有后端，或先确认它是否就是目标服务。'
}
if (-not $NoFrontend -and (Test-TcpPort -Port 5173)) {
    throw '5173 端口已被占用。请停止已有前端，或先确认它是否就是目标服务。'
}

if (-not [string]::IsNullOrWhiteSpace($BootstrapAdminEmail)) {
    $env:APM_BOOTSTRAP_ADMIN_EMAIL = $BootstrapAdminEmail.Trim()
}
if (-not [string]::IsNullOrWhiteSpace($BootstrapAdminDisplayName)) {
    $env:APM_BOOTSTRAP_ADMIN_DISPLAY_NAME = $BootstrapAdminDisplayName.Trim()
}
if ($PromptForBootstrapAdmin) {
    if ([string]::IsNullOrWhiteSpace($env:APM_BOOTSTRAP_ADMIN_EMAIL)) {
        $env:APM_BOOTSTRAP_ADMIN_EMAIL = (Read-Host '引导管理员邮箱').Trim()
    }
    if ([string]::IsNullOrWhiteSpace($env:APM_BOOTSTRAP_ADMIN_PASSWORD)) {
        $securePassword = Read-Host '引导管理员密码（不会回显）' -AsSecureString
        $env:APM_BOOTSTRAP_ADMIN_PASSWORD = Convert-SecureStringToPlainText -SecureString $securePassword
    }
}
$adminEmailConfigured = -not [string]::IsNullOrWhiteSpace($env:APM_BOOTSTRAP_ADMIN_EMAIL)
$adminPasswordConfigured = -not [string]::IsNullOrWhiteSpace($env:APM_BOOTSTRAP_ADMIN_PASSWORD)
if ($adminEmailConfigured -xor $adminPasswordConfigured) {
    throw '引导管理员配置不完整：APM_BOOTSTRAP_ADMIN_EMAIL 与 APM_BOOTSTRAP_ADMIN_PASSWORD 必须同时设置。'
}

if ([string]::IsNullOrWhiteSpace($env:APM_APP_KEY_ENCRYPTION_KEY)) {
    throw '缺少 APM_APP_KEY_ENCRYPTION_KEY。请生成 Base64 编码的 32 字节随机值并持久写入被 Git 忽略的 .env.local；重启时必须使用同一值。'
}
try {
    $appKeyEncryptionBytes = [Convert]::FromBase64String($env:APM_APP_KEY_ENCRYPTION_KEY.Trim())
} catch {
    throw 'APM_APP_KEY_ENCRYPTION_KEY 不是有效的 Base64 值。'
}
if ($appKeyEncryptionBytes.Length -ne 32) {
    throw 'APM_APP_KEY_ENCRYPTION_KEY 解码后必须正好为 32 字节。'
}

if ([string]::IsNullOrWhiteSpace($env:APM_DATABASE_PASSWORD)) {
    $env:APM_DATABASE_PASSWORD = 'apm-local-only'
}
if ([string]::IsNullOrWhiteSpace($env:APM_DATABASE_USERNAME)) {
    $env:APM_DATABASE_USERNAME = 'apm'
}
if ([string]::IsNullOrWhiteSpace($env:APM_DATABASE_NAME)) {
    $env:APM_DATABASE_NAME = 'apm'
}
if ([string]::IsNullOrWhiteSpace($env:APM_DATABASE_URL)) {
    $env:APM_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/' + $env:APM_DATABASE_NAME
}

# 默认使用远程 PostgreSQL；显式使用 auto 时根据 JDBC 地址判断数据库位置，
# 本地地址使用 Docker PostgreSQL，其他地址视为远程数据库。
$databaseIsRemote = switch ($DatabaseMode) {
    'remote' { $true; break }
    'local' { $false; break }
    default {
        -not ($env:APM_DATABASE_URL -match '^jdbc:postgresql://(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?/')
    }
}
if ($databaseIsRemote) {
    Write-Host "使用远程 PostgreSQL：$env:APM_DATABASE_URL"
} else {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
        throw '找不到 docker.exe。当前管理域需要本地 PostgreSQL，请先安装并启动 Docker Desktop。'
    }
}

if ([string]::IsNullOrWhiteSpace($env:APM_FLYWAY_ENABLED)) {
    $env:APM_FLYWAY_ENABLED = 'true'
}
if ([string]::IsNullOrWhiteSpace($env:APM_SESSION_COOKIE_SECURE)) {
    $env:APM_SESSION_COOKIE_SECURE = 'false'
}
if ([string]::IsNullOrWhiteSpace($env:APM_SESSION_SAME_SITE)) {
    $env:APM_SESSION_SAME_SITE = 'lax'
}

if (-not $databaseIsRemote) {
    & docker.exe compose -f (Join-Path $repoRoot 'compose.yaml') up -d postgres
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL 容器启动失败，请查看 Docker Desktop 状态。'
    }
    if (-not (Wait-TcpPort -Port 5432 -TimeoutSeconds 60)) {
        throw 'PostgreSQL 未在 60 秒内监听 5432 端口。'
    }
}

if ($StorageMode -eq 'clickhouse') {
    # 安装文档使用 CLICKHOUSE_ADMIN_*，应用连接属性使用 CLICKHOUSE_*；
    # 未显式设置应用变量时复用同一套本地管理员配置。
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_USERNAME) -and
        -not [string]::IsNullOrWhiteSpace($env:CLICKHOUSE_ADMIN_USER)) {
        $env:CLICKHOUSE_USERNAME = $env:CLICKHOUSE_ADMIN_USER
    }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_PASSWORD) -and
        -not [string]::IsNullOrWhiteSpace($env:CLICKHOUSE_ADMIN_PASSWORD)) {
        $env:CLICKHOUSE_PASSWORD = $env:CLICKHOUSE_ADMIN_PASSWORD
    }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_URL) -and
        -not [string]::IsNullOrWhiteSpace($env:CLICKHOUSE_HTTP_URL)) {
        $env:CLICKHOUSE_URL = $env:CLICKHOUSE_HTTP_URL
    }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_PASSWORD)) {
        $securePassword = Read-Host '请输入 ClickHouse 密码' -AsSecureString
        $env:CLICKHOUSE_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
    }
    $env:APM_CLICKHOUSE_ENABLED = 'true'
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_URL)) {
        $env:CLICKHOUSE_URL = 'http://127.0.0.1:8123'
    }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_DATABASE)) {
        $env:CLICKHOUSE_DATABASE = 'apm'
    }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_USERNAME)) {
        $env:CLICKHOUSE_USERNAME = 'apm_admin'
    }
} else {
    $env:APM_STORAGE_IN_MEMORY_AVAILABLE = 'true'
    $env:APM_CLICKHOUSE_ENABLED = 'false'
}
$env:APM_STORAGE_MODE = $StorageMode
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.gradle-local'

# 当前工程的 processor 制品位于被 Git 忽略的项目 Maven Local；如果外部环境已经
# 设置 GRADLE_OPTS（例如 Gradle 镜像地址），只追加仓库参数，不覆盖原有选项。
$projectMavenRepository = Join-Path $repoRoot '.m2\repository'
$mavenRepositoryOption = "-Dmaven.repo.local=$projectMavenRepository"
if ([string]::IsNullOrWhiteSpace($env:GRADLE_OPTS)) {
    $env:GRADLE_OPTS = $mavenRepositoryOption
} elseif ($env:GRADLE_OPTS -notmatch [regex]::Escape('-Dmaven.repo.local=')) {
    $env:GRADLE_OPTS = "$env:GRADLE_OPTS $mavenRepositoryOption"
}

# 首次启动可能需要下载 Gradle Distribution；先完成 Wrapper 预热，避免
# 下载时间被误计入后端 90 秒端口就绪超时。
$gradleWrapper = Join-Path $backendRoot 'gradlew.bat'
# 显式指定后端工程，允许从仓库根目录或其他目录调用统一入口。
& $gradleWrapper -p $backendRoot --no-daemon --version
$gradleExitCode = $LASTEXITCODE
if ($gradleExitCode -ne 0) {
    throw "Gradle Wrapper 初始化失败（退出码 $gradleExitCode），请查看网络或 Gradle 日志。"
}

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

$backendCommand = "call `"$gradleWrapper`" --no-daemon bootRun > `"$backendLog`" 2>&1"
$backendProcess = Start-Process `
    -FilePath 'cmd.exe' `
    -ArgumentList @('/d', '/c', $backendCommand) `
    -WorkingDirectory $backendRoot `
    -WindowStyle Hidden `
    -PassThru

$frontendProcess = $null
try {
    if (-not $NoFrontend) {
        $frontendCommand = "call npm.cmd run dev -- --host 127.0.0.1 > `"$frontendLog`" 2>&1"
        $frontendProcess = Start-Process `
            -FilePath 'cmd.exe' `
            -ArgumentList @('/d', '/c', $frontendCommand) `
            -WorkingDirectory $frontendRoot `
            -WindowStyle Hidden `
            -PassThru
    }

} catch {
    Stop-ProcessTree -ProcessId $backendProcess.Id
    if ($null -ne $frontendProcess) {
        Stop-ProcessTree -ProcessId $frontendProcess.Id
    }
    throw
}

$backendReady = Wait-TcpPort -Port 8080 -TimeoutSeconds 90
$frontendReady = $NoFrontend -or (Wait-TcpPort -Port 5173 -TimeoutSeconds 20)
if (-not $backendReady -or -not $frontendReady) {
    Write-Warning '服务未在预期时间内监听端口，正在清理本次启动的进程；请查看 build\dev-services 下的日志。'
    $failedBackendListenerId = Get-ListeningProcessId -Port 8080
    $failedFrontendListenerId = if ($NoFrontend) { $null } else { Get-ListeningProcessId -Port 5173 }
    Stop-StartupProcess -LauncherId $backendProcess.Id -ListenerId $failedBackendListenerId
    if ($null -ne $frontendProcess) {
        Stop-StartupProcess -LauncherId $frontendProcess.Id -ListenerId $failedFrontendListenerId
    }
    throw '本地服务启动失败，请查看 build\dev-services 下的日志。'
}

$backendListenerId = Get-ListeningProcessId -Port 8080
$frontendListenerId = if ($NoFrontend) { $null } else { Get-ListeningProcessId -Port 5173 }
$backendListenerState = if ($null -eq $backendListenerId) {
    $null
} else {
    Get-ProcessState -Process (Get-Process -Id $backendListenerId -ErrorAction Stop)
}
$frontendListenerState = if ($null -eq $frontendListenerId) {
    $null
} else {
    Get-ProcessState -Process (Get-Process -Id $frontendListenerId -ErrorAction Stop)
}
$state = [ordered]@{
    storageMode = $StorageMode
    database = [ordered]@{
        mode    = if ($databaseIsRemote) { 'remote' } else { 'local' }
        compose = if ($databaseIsRemote) { $null } else { Join-Path $repoRoot 'compose.yaml' }
        started = -not $databaseIsRemote
    }
    backend = [ordered]@{
        launcher = Get-ProcessState -Process $backendProcess
        listener = $backendListenerState
    }
    frontend = if ($null -eq $frontendProcess) {
        $null
    } else {
        [ordered]@{
            launcher = Get-ProcessState -Process $frontendProcess
            listener = $frontendListenerState
        }
    }
}
$state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8

Write-Host "已启动后端（$StorageMode 模式）：http://127.0.0.1:8080"
if (-not $NoFrontend) {
    Write-Host '已启动前端：http://127.0.0.1:5173/login'
}
Write-Host "后端日志：$backendLog"
if (-not $NoFrontend) {
    Write-Host "前端日志：$frontendLog"
}
Write-Host '停止服务：.\scripts\stop-dev.ps1'
