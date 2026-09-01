[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoRoot 'build\dev-services'
$statePath = Join-Path $runtimeRoot 'services.json'

function Stop-SafeProcess {
    param(
        [string]$Name,
        [object]$Entry
    )

    if ($null -eq $Entry -or [int]$Entry.pid -le 0) {
        return
    }

    $processId = [int]$Entry.pid
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Write-Host "$Name 已经停止（PID $processId）"
        return
    }

    try {
        $expectedStart = ([datetime]$Entry.startTime).ToUniversalTime()
        $actualStart = $process.StartTime.ToUniversalTime()
        if ([math]::Abs(($actualStart - $expectedStart).TotalSeconds) -gt 10) {
            Write-Warning "$Name 的 PID $processId 已被其他进程复用，跳过停止。"
            return
        }
    } catch {
        Write-Warning "无法确认 $Name 的 PID $processId，跳过停止。"
        return
    }

    Stop-Process -Id $processId -Force -ErrorAction Stop
    Write-Host "已停止 $Name（PID $processId）"
}

function Stop-TrackedProcess {
    param(
        [string]$Name,
        [object]$Entry
    )

    if ($null -eq $Entry) {
        return
    }

    if ($null -ne $Entry.listener) {
        Stop-SafeProcess -Name "$Name 监听进程" -Entry $Entry.listener
    }
    if ($null -ne $Entry.launcher) {
        Stop-SafeProcess -Name "$Name 启动进程" -Entry $Entry.launcher
    } else {
        # 兼容旧版状态文件格式。
        Stop-SafeProcess -Name $Name -Entry $Entry
    }
}

if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Host '没有找到脚本启动记录，未停止任何进程。'
    exit 0
}

$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
Stop-TrackedProcess -Name '后端' -Entry $state.backend
Stop-TrackedProcess -Name '前端' -Entry $state.frontend
if ($state.database -and $state.database.started -and (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    & docker.exe compose -f $state.database.compose stop postgres | Out-Host
}
Remove-Item -LiteralPath $statePath -Force
Write-Host '服务停止完成；日志仍保留在 build\dev-services。'
