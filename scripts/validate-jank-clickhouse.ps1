param(
    [string]$AppId = '',
    [switch]$InitializeSchema
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($AppId)) {
    $AppId = [Guid]::NewGuid().ToString()
} else {
    try {
        $AppId = ([Guid]::Parse($AppId)).ToString()
    } catch {
        throw 'AppId 必须是标准 UUID。'
    }
}

function Read-LocalEnv {
    $values = @{}
    if (-not (Test-Path -LiteralPath '.env.local')) {
        throw '未找到 .env.local；请在本地配置 CLICKHOUSE_ADMIN_USER、CLICKHOUSE_ADMIN_PASSWORD 和 CLICKHOUSE_HTTP_URL。'
    }
    foreach ($line in Get-Content -LiteralPath '.env.local') {
        $trim = $line.Trim()
        if ($trim -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $value = $matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$matches[1]] = $value
        }
    }
    return $values
}

function Invoke-ClickHouse {
    param(
        [Parameter(Mandatory = $true)][string]$Request,
        [string]$Body = ''
    )

    $tempFile = [IO.Path]::GetTempFileName()
    try {
        $payload = if ([string]::IsNullOrEmpty($Body)) { $Request } else { $Request + [Environment]::NewLine + $Body }
        [IO.File]::WriteAllText($tempFile, $payload, [Text.UTF8Encoding]::new($false))
        $curlArguments = @(
            '--silent', '--show-error', '--user', $script:Credentials,
            '--data-binary', ('@' + $tempFile), '--max-time', '60',
            '--write-out', ' HTTP_STATUS=%{http_code}',
            ($script:ClickHouseUrl + '/?database=' + $script:Database)
        )
        $output = (& curl.exe @curlArguments 2>&1 | Out-String)
        $marker = 'HTTP_STATUS='
        $markerIndex = $output.LastIndexOf($marker)
        if ($markerIndex -lt 0) {
            throw 'ClickHouse 请求未返回 HTTP 状态码。'
        }
        $statusText = $output.Substring($markerIndex + $marker.Length).Trim()
        $status = 0
        if (-not [int]::TryParse($statusText, [ref]$status)) {
            throw '无法解析 ClickHouse HTTP 状态码。'
        }
        $responseBody = $output.Substring(0, $markerIndex).Trim()
        if ($status -lt 200 -or $status -ge 300) {
            throw "ClickHouse 请求失败，HTTP $status：$responseBody"
        }
        return $responseBody
    } finally {
        Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-ClickHouseRows {
    param([Parameter(Mandatory = $true)][string]$Query)

    $body = Invoke-ClickHouse -Request $Query
    if ([string]::IsNullOrWhiteSpace($body)) {
        return @()
    }
    return @($body -split '\r?\n' |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json })
}

function ConvertTo-CompactJson {
    param([Parameter(Mandatory = $true)][object]$Value)
    return $Value | ConvertTo-Json -Compress -Depth 100
}

function ConvertTo-ClickHouseTime {
    param([Parameter(Mandatory = $true)][Int64]$EpochMilliseconds)
    return [DateTimeOffset]::FromUnixTimeMilliseconds($EpochMilliseconds).UtcDateTime.ToString(
        'yyyy-MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
}

function Normalize-FingerprintPart {
    param([string]$Value)
    if ($null -eq $Value) {
        return ''
    }
    $normalized = [regex]::Replace($Value, '(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<id>')
    $normalized = [regex]::Replace($normalized, '(?i)0x[0-9a-f]+', '<address>')
    $normalized = [regex]::Replace($normalized, '\b\d+(?:\.\d+)?\b', '<number>')
    return ([regex]::Replace($normalized, '\s+', ' ')).Trim().ToLowerInvariant()
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).Replace('-', '').ToLowerInvariant())
    } finally {
        $sha.Dispose()
    }
}

function Get-JankFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$AppId,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][object]$Payload,
        [Parameter(Mandatory = $true)][object]$Analysis
    )

    $stackWeights = @{}
    foreach ($slice in @($Analysis.sampleSlices)) {
        if ($slice.covered -and [Int64]$slice.endOffsetNs -gt [Int64]$slice.startOffsetNs -and $null -ne $slice.stackId) {
            $part = [Int64]$slice.endOffsetNs - [Int64]$slice.startOffsetNs
            if ($stackWeights.ContainsKey($slice.stackId)) {
                $stackWeights[$slice.stackId] = [Int64]$stackWeights[$slice.stackId] + $part
            } else {
                $stackWeights[$slice.stackId] = $part
            }
        }
    }
    $selected = $stackWeights.GetEnumerator() | Sort-Object @{Expression = { [Int64]$_.Value }; Descending = $true }, Name | Select-Object -First 1
    $frames = @()
    if ($null -ne $selected) {
        $property = $Payload.stackDictionary.PSObject.Properties | Where-Object { $_.Name -eq $selected.Key } | Select-Object -First 1
        if ($null -ne $property) {
            $frames = @($property.Value)
        }
    }
    $applicationFrames = @($frames | Where-Object { $_ -ne $null -and $_.applicationFrame -eq $true } | Select-Object -First 8)
    if ($applicationFrames.Count -eq 0) {
        $applicationFrames = @($frames | Where-Object { $_ -ne $null } | Select-Object -First 8)
    }
    $path = ($applicationFrames | ForEach-Object {
            (Normalize-FingerprintPart $_.className) + '#' + (Normalize-FingerprintPart $_.methodName)
        }) -join ';'
    if ([string]::IsNullOrWhiteSpace($path)) {
        $path = 'missing'
    }
    return Get-Sha256 ($AppId + '|' + $PackageName + '|' + (Normalize-FingerprintPart $Payload.scene) + '|' + $path)
}

function New-JankAnalysis {
    param([Parameter(Mandatory = $true)][object]$Payload)

    $duration = [Int64]$Payload.messageDurationNs
    $interval = [Int64]$Payload.samplingIntervalNs
    $maxContinuousGap = $interval * 2
    $samples = @($Payload.samples | Sort-Object { [Int64]$_.offsetNs })
    $slices = [System.Collections.Generic.List[object]]::new()
    $weights = @{}
    $cursor = [Int64]0
    $covered = [Int64]0

    for ($i = 0; $i -lt $samples.Count; $i++) {
        $sample = $samples[$i]
        $start = [Math]::Max([Int64]0, [Int64]$sample.offsetNs)
        if ($start -gt $cursor) {
            $slices.Add([PSCustomObject]@{ startOffsetNs = $cursor; endOffsetNs = $start; stackId = $null; covered = $false })
        }
        $end = $duration
        if ($i + 1 -lt $samples.Count) {
            $next = [Math]::Max($start, [Int64]$samples[$i + 1].offsetNs)
            $end = [Math]::Min($duration, $next)
            if ($next - $start -gt $maxContinuousGap) {
                $end = [Math]::Min($duration, $start + $interval)
            }
        } else {
            $end = [Math]::Min($duration, $start + $interval)
        }
        if ($end -gt $start) {
            $slices.Add([PSCustomObject]@{ startOffsetNs = $start; endOffsetNs = $end; stackId = $sample.stackId; covered = $true })
            $part = [Int64]($end - $start)
            $covered += $part
            if ($weights.ContainsKey($sample.stackId)) {
                $weights[$sample.stackId] = [Int64]$weights[$sample.stackId] + $part
            } else {
                $weights[$sample.stackId] = $part
            }
            $cursor = [Math]::Max($cursor, $end)
        }
        if ($i + 1 -lt $samples.Count) {
            $next = [Math]::Min($duration, [Math]::Max($start, [Int64]$samples[$i + 1].offsetNs))
            if ($next -gt $cursor) {
                $slices.Add([PSCustomObject]@{ startOffsetNs = $cursor; endOffsetNs = $next; stackId = $null; covered = $false })
                $cursor = $next
            }
        }
    }
    if ($cursor -lt $duration) {
        $slices.Add([PSCustomObject]@{ startOffsetNs = $cursor; endOffsetNs = $duration; stackId = $null; covered = $false })
    }

    $tree = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $weights.GetEnumerator()) {
        $property = $Payload.stackDictionary.PSObject.Properties | Where-Object { $_.Name -eq $entry.Key } | Select-Object -First 1
        $frames = if ($null -eq $property) { @() } else { @($property.Value) }
        $nodes = @()
        for ($i = $frames.Count - 1; $i -ge 0; $i--) {
            $frame = $frames[$i]
            if ($null -eq $frame) {
                continue
            }
            $nodes = @([PSCustomObject]@{
                    className = $frame.className
                    methodName = $frame.methodName
                    estimatedDurationNs = [Int64]$entry.Value
                    estimatedUnattributedDurationNs = [Int64]0
                    children = @($nodes)
                })
        }
        if ($nodes.Count -gt 0) {
            $tree.Add($nodes[0])
        }
    }

    return [PSCustomObject]@{
        exactMessageDurationNs = $duration
        estimatedDurationNs = $covered
        estimatedUnattributedDurationNs = [Int64]0
        coveredDurationNs = $covered
        uncoveredDurationNs = [Math]::Max([Int64]0, $duration - $covered)
        sampleSlices = @($slices.ToArray())
        callTree = @($tree.ToArray())
        stackDictionary = $Payload.stackDictionary
        expectedSampleCount = [int]$Payload.expectedSampleCount
        parsedSampleCount = [int]$Payload.parsedSampleCount
        missingSampleCount = [int]$Payload.missingSampleCount
        algorithmVersion = $Payload.algorithmVersion
        warnings = @()
    }
}

function Insert-JsonRows {
    param(
        [Parameter(Mandatory = $true)][string]$Table,
        [Parameter(Mandatory = $true)][object[]]$Rows
    )
    $lines = @($Rows | ForEach-Object { ConvertTo-CompactJson $_ })
    $body = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
    [void](Invoke-ClickHouse -Request ('INSERT INTO apm.' + $Table + ' FORMAT JSONEachRow') -Body $body)
    Write-Host ('写入 {0}: {1} 行' -f $Table, $Rows.Count)
}

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw '验收失败：' + $Message
    }
}

function Assert-ApplicationIdentityTablesEmpty {
    $migrationTables = @(
        'apm_event_raw',
        'apm_crash_detail',
        'apm_event_hourly',
        'apm_crash_issue_hourly',
        'apm_jank_event',
        'apm_jank_detail',
        'apm_jank_issue_hourly',
        'apm_frame_scene_summary',
        'apm_device_suspension_segment',
        'apm_device_suspension_daily'
    )
    $quotedNames = ($migrationTables | ForEach-Object { "'$_'" }) -join ', '
    $existing = Invoke-ClickHouseRows -Query (
        "SELECT name FROM system.tables WHERE database = 'apm' AND name IN ($quotedNames) FORMAT JSONEachRow")
    $nonEmpty = [System.Collections.Generic.List[string]]::new()
    foreach ($table in @($existing | ForEach-Object { $_.name })) {
        $countRows = Invoke-ClickHouseRows -Query ("SELECT count() AS row_count FROM apm.$table FORMAT JSONEachRow")
        $rowCount = if ($countRows.Count -eq 0) { 0L } else { [Int64]$countRows[0].row_count }
        if ($rowCount -gt 0) {
            $nonEmpty.Add("$table=$rowCount")
        }
    }
    if ($nonEmpty.Count -gt 0) {
        throw ('拒绝执行 ClickHouse 004：受影响业务表非空（' + ($nonEmpty -join ', ') + '）。请先确认仅为测试数据并人工清理，脚本不会静默删除。')
    }
}

$envValues = Read-LocalEnv
$script:ClickHouseUrl = $envValues['CLICKHOUSE_HTTP_URL']
if ([string]::IsNullOrWhiteSpace($script:ClickHouseUrl)) {
    $script:ClickHouseUrl = 'http://localhost:8123'
}
$script:Database = 'apm'
$script:Credentials = $envValues['CLICKHOUSE_ADMIN_USER'] + ':' + $envValues['CLICKHOUSE_ADMIN_PASSWORD']
if ([string]::IsNullOrWhiteSpace($envValues['CLICKHOUSE_ADMIN_USER']) -or
        [string]::IsNullOrWhiteSpace($envValues['CLICKHOUSE_ADMIN_PASSWORD'])) {
    throw 'CLICKHOUSE_ADMIN_USER 或 CLICKHOUSE_ADMIN_PASSWORD 未配置。'
}

if ($InitializeSchema) {
    Assert-ApplicationIdentityTablesEmpty
    $currentSchema = Invoke-ClickHouseRows -Query (
        "SELECT count() AS identity_columns FROM system.columns WHERE database = 'apm' " +
        "AND table = 'apm_event_raw' AND name = 'app_id' AND type = 'UUID' FORMAT JSONEachRow") | Select-Object -First 1
    if ($null -ne $currentSchema -and [Int64]$currentSchema.identity_columns -eq 1) {
        $schemaFiles = @('src/main/resources/db/clickhouse/004_application_identity_schema.sql')
        $schemaLabel = '004_application_identity_schema.sql'
    } else {
        $schemaFiles = @(
            'src/main/resources/db/clickhouse/001_crash_schema.sql',
            'src/main/resources/db/clickhouse/002_jank_schema.sql',
            'src/main/resources/db/clickhouse/003_jank_sampling_quality.sql',
            'src/main/resources/db/clickhouse/004_application_identity_schema.sql')
        $schemaLabel = '001-004'
    }
    $statementCount = 0
    foreach ($schemaFile in $schemaFiles) {
        $schema = Get-Content -Raw -LiteralPath $schemaFile
        $statements = @($schema -split ';' | ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and ($_ -notmatch '(?is)^--') })
        foreach ($statement in $statements) {
            [void](Invoke-ClickHouse -Request ($statement + ';'))
            $statementCount++
        }
    }
    Write-Host ('schema 初始化完成: {0} 条语句，已执行 {1} 空表保护' -f $statementCount, $schemaLabel)
}

$requiredTables = @(
    'apm_event_raw',
    'apm_crash_detail',
    'apm_event_hourly',
    'apm_crash_issue_hourly',
    'apm_event_hourly_mv',
    'apm_crash_issue_hourly_mv',
    'apm_device_suspension_daily',
    'apm_device_suspension_segment',
    'apm_frame_scene_summary',
    'apm_jank_detail',
    'apm_jank_event',
    'apm_jank_issue_hourly',
    'apm_jank_issue_hourly_mv'
)
$tableRows = Invoke-ClickHouseRows -Query "SELECT name FROM system.tables WHERE database = 'apm' AND (name LIKE 'apm_event%' OR name LIKE 'apm_crash%' OR name LIKE 'apm_jank%' OR name LIKE 'apm_frame%' OR name LIKE 'apm_device_suspension%') ORDER BY name FORMAT JSONEachRow"
$actualTables = @($tableRows | ForEach-Object { $_.name })
foreach ($table in $requiredTables) {
    Assert-Condition ($actualTables -contains $table) ('缺少 ClickHouse 对象: ' + $table)
}

$fixture = Get-Content -Raw -LiteralPath 'src/test/resources/fixtures/jank-dataset.json' | ConvertFrom-Json
$events = @($fixture.events)
$from = '2026-08-15 00:00:00.000'
$to = '2026-08-17 00:00:00.000'
$hourFrom = '2026-08-15 00:00:00'
$hourTo = '2026-08-17 00:00:00'
$rawRows = [System.Collections.Generic.List[object]]::new()
$jankRows = [System.Collections.Generic.List[object]]::new()
$detailRows = [System.Collections.Generic.List[object]]::new()
$frameRows = [System.Collections.Generic.List[object]]::new()
$suspensionRows = [System.Collections.Generic.List[object]]::new()
$receivedBase = [DateTimeOffset]::Parse('2026-08-27T12:00:00Z')

for ($index = 0; $index -lt $events.Count; $index++) {
    $event = $events[$index]
    $receivedAt = $receivedBase.AddMilliseconds($index).UtcDateTime.ToString(
        'yyyy-MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
    $eventTime = ConvertTo-ClickHouseTime ([Int64]$event.occurredAt)
    $algorithmVersion = ''
    $fingerprint = ''
    $analysis = $null
    if ($event.eventType -eq 'jank') {
        $algorithmVersion = $event.jank.algorithmVersion
        $analysis = New-JankAnalysis $event.jank
        $fingerprint = Get-JankFingerprint $AppId $event.packageName $event.jank $analysis
    } elseif ($event.eventType -eq 'frame_scene_summary') {
        $algorithmVersion = $event.frameSceneSummary.algorithmVersion
    } elseif ($event.eventType -eq 'foreground_suspension_summary') {
        $algorithmVersion = $event.foregroundSuspensionSummary.algorithmVersion
    }

    $rawRows.Add([ordered]@{
            app_id = $AppId
            package_name = $event.packageName
            event_id = $event.eventId
            event_type = $event.eventType
            event_time = $eventTime
            received_time = $receivedAt
            schema_version = [int]$event.schemaVersion
            app_version = $event.appVersion
            version_code = [Int64]$event.versionCode
            build_id = $event.buildId
            channel = $event.channel
            environment = $event.environment
            session_id = $event.sessionId
            anonymous_device_id = $event.anonymousDeviceId
            os_version = $event.osVersion
            device_model = $event.deviceModel
            network_type = ''
            duration_ms = $null
            status = ''
            measurements = @{}
            attributes = @{}
            crash_kind = ''
            crash_fatal = 0
            crash_exception_type = ''
            crash_fingerprint = $fingerprint
            fingerprint_version = if ($event.eventType -eq 'jank') { 'jank-v1' } else { '' }
            symbolication_status = ''
        })

    if ($event.eventType -eq 'jank') {
        $payloadJson = ConvertTo-CompactJson $event.jank
        $analysisJson = ConvertTo-CompactJson $analysis
        $jankRows.Add([ordered]@{
                app_id = $AppId
                package_name = $event.packageName
                event_id = $event.eventId
                event_time = $eventTime
                received_time = $receivedAt
                schema_version = [int]$event.schemaVersion
                session_id = $event.sessionId
                anonymous_device_id = $event.anonymousDeviceId
                app_version = $event.appVersion
                version_code = [Int64]$event.versionCode
                build_id = $event.buildId
                channel = $event.channel
                environment = $event.environment
                os_version = $event.osVersion
                device_model = $event.deviceModel
                network_type = ''
                scene = $event.jank.scene
                algorithm_version = $event.jank.algorithmVersion
                message_duration_ns = [Int64]$event.jank.messageDurationNs
                threshold_ns = [Int64]$event.jank.thresholdNs
                sampling_interval_ns = [Int64]$event.jank.samplingIntervalNs
                estimated_duration_ns = [Int64]$analysis.estimatedDurationNs
                estimated_unattributed_duration_ns = [Int64]$analysis.estimatedUnattributedDurationNs
                covered_duration_ns = [Int64]$analysis.coveredDurationNs
                uncovered_duration_ns = [Int64]$analysis.uncoveredDurationNs
                expected_sample_count = [int]$event.jank.expectedSampleCount
                parsed_sample_count = [int]$event.jank.parsedSampleCount
                missing_sample_count = [int]$event.jank.missingSampleCount
                fingerprint = $fingerprint
                fingerprint_version = 'jank-v1'
                jank_payload_json = $payloadJson
                jank_analysis_json = $analysisJson
            })
        $detailRows.Add([ordered]@{
                app_id = $AppId
                event_id = $event.eventId
                event_time = $eventTime
                received_time = $receivedAt
                fingerprint = $fingerprint
                fingerprint_version = 'jank-v1'
                stack_dictionary_json = ConvertTo-CompactJson $event.jank.stackDictionary
                samples_json = ConvertTo-CompactJson $analysis.sampleSlices
                call_tree_json = ConvertTo-CompactJson $analysis.callTree
                evidence_json = ConvertTo-CompactJson ([ordered]@{
                        exactMessageDurationNs = [Int64]$analysis.exactMessageDurationNs
                        estimatedDurationNs = [Int64]$analysis.estimatedDurationNs
                        estimatedUnattributedDurationNs = [Int64]$analysis.estimatedUnattributedDurationNs
                        coveredDurationNs = [Int64]$analysis.coveredDurationNs
                        uncoveredDurationNs = [Int64]$analysis.uncoveredDurationNs
                        algorithmVersion = $analysis.algorithmVersion
                    })
            })
    } elseif ($event.eventType -eq 'frame_scene_summary') {
        $histogram = if ($null -eq $event.frameSceneSummary.frameDurationHistogram) { @{} } else { $event.frameSceneSummary.frameDurationHistogram }
        $frameRows.Add([ordered]@{
                app_id = $AppId
                event_id = $event.eventId
                event_time = $eventTime
                received_time = $receivedAt
                session_id = $event.sessionId
                anonymous_device_id = $event.anonymousDeviceId
                app_version = $event.appVersion
                channel = $event.channel
                environment = $event.environment
                os_version = $event.osVersion
                device_model = $event.deviceModel
                scene = $event.frameSceneSummary.scene
                algorithm_version = $event.frameSceneSummary.algorithmVersion
                active_duration_ms = [Int64]$event.frameSceneSummary.activeDurationMs
                ui_refresh_frame_count = [int]$event.frameSceneSummary.uiRefreshFrameCount
                refresh_rate_hz = [double]$event.frameSceneSummary.refreshRateHz
                normalized_fps60 = [double]$event.frameSceneSummary.normalizedFps60
                frame_duration_histogram_json = ConvertTo-CompactJson $histogram
            })
    } elseif ($event.eventType -eq 'foreground_suspension_summary') {
        $suspensionRows.Add([ordered]@{
                app_id = $AppId
                event_id = $event.eventId
                event_time = $eventTime
                received_time = $receivedAt
                session_id = $event.sessionId
                anonymous_device_id = $event.anonymousDeviceId
                app_version = $event.appVersion
                channel = $event.channel
                environment = $event.environment
                os_version = $event.osVersion
                device_model = $event.deviceModel
                algorithm_version = $event.foregroundSuspensionSummary.algorithmVersion
                foreground_duration_ms = [Int64]$event.foregroundSuspensionSummary.foregroundDurationMs
                suspension_duration_ms = [Int64]$event.foregroundSuspensionSummary.suspensionDurationMs
                suspension_count = [int]$event.foregroundSuspensionSummary.suspensionCount
                threshold_ms = [int]$event.foregroundSuspensionSummary.thresholdMs
            })
    }
}

Insert-JsonRows 'apm_event_raw' $rawRows.ToArray()
Insert-JsonRows 'apm_jank_event' $jankRows.ToArray()
Insert-JsonRows 'apm_jank_detail' $detailRows.ToArray()
Insert-JsonRows 'apm_frame_scene_summary' $frameRows.ToArray()
Insert-JsonRows 'apm_device_suspension_segment' $suspensionRows.ToArray()

$rawCounts = Invoke-ClickHouseRows -Query ("SELECT " +
    "(SELECT count() FROM apm_event_raw WHERE app_id = '$AppId') AS physical_rows, " +
    "(SELECT uniqExact(event_id) FROM apm_event_raw WHERE app_id = '$AppId') AS distinct_event_ids, " +
    "(SELECT count() FROM apm_event_raw FINAL WHERE app_id = '$AppId') AS final_rows FORMAT JSONEachRow") | Select-Object -First 1
Assert-Condition ([int64]$rawCounts.distinct_event_ids -eq 9) ('原始表去重事件数应为 9，实际为 ' + $rawCounts.distinct_event_ids)
Assert-Condition ([int64]$rawCounts.final_rows -eq 9) ('原始表 FINAL 行数应为 9，实际为 ' + $rawCounts.final_rows)

$factCounts = Invoke-ClickHouseRows -Query ("SELECT " +
    "(SELECT count() FROM apm_jank_event WHERE app_id = '$AppId') AS physical_fact_rows, " +
    "(SELECT count() FROM apm_jank_event FINAL WHERE app_id = '$AppId') AS final_fact_rows, " +
    "(SELECT count() FROM apm_jank_detail WHERE app_id = '$AppId') AS physical_detail_rows, " +
    "(SELECT count() FROM apm_jank_detail FINAL WHERE app_id = '$AppId') AS final_detail_rows FORMAT JSONEachRow") | Select-Object -First 1
Assert-Condition ([int64]$factCounts.final_fact_rows -eq 3) ('卡顿事实 FINAL 行数应为 3，实际为 ' + $factCounts.final_fact_rows)
Assert-Condition ([int64]$factCounts.final_detail_rows -eq 3) ('卡顿详情 FINAL 行数应为 3，实际为 ' + $factCounts.final_detail_rows)

$issueRows = Invoke-ClickHouseRows -Query ("SELECT fingerprint, uniqCombined64Merge(event_ids) AS event_count, " +
    "uniqCombined64Merge(session_ids) AS session_count, uniqCombined64Merge(device_ids) AS device_count, " +
    "quantilesTDigestMerge(0.5, 0.9, 0.99)(exact_duration_p) AS duration_quantiles " +
    "FROM apm_jank_issue_hourly WHERE app_id = '$AppId' AND hour >= '$hourFrom' AND hour < '$hourTo' " +
    "GROUP BY fingerprint ORDER BY fingerprint FORMAT JSONEachRow")
Assert-Condition ($issueRows.Count -eq 2) ('Issue 聚合应有 2 组，实际为 ' + $issueRows.Count)
$issueCounts = @($issueRows | ForEach-Object { [int64]$_.event_count } | Sort-Object)
Assert-Condition ($issueCounts.Count -eq 2 -and $issueCounts[0] -eq 1 -and $issueCounts[1] -eq 2) ('Issue 去重事件数应为 1、2，实际为 ' + ($issueCounts -join '、'))
$allQuantiles = @($issueRows | ForEach-Object { $_.duration_quantiles } | ForEach-Object { $_ })
Assert-Condition ($allQuantiles.Count -eq 6) ('Issue 分位数结果应有 6 个值，实际为 ' + $allQuantiles.Count)

$fpsRows = Invoke-ClickHouseRows -Query ("SELECT algorithm_version, count() AS total_records, countIf(active_duration_ms > 0 AND ui_refresh_frame_count > 0 " +
    "AND isFinite(refresh_rate_hz) AND refresh_rate_hz > 0 AND isFinite(normalized_fps60) AND normalized_fps60 >= 0) AS valid_records, " +
    "avgIf(toFloat64(normalized_fps60), active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS average_fps, " +
    "quantileExactIf(0.50)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p50_fps, " +
    "quantileExactIf(0.10)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p90_fps, " +
    "quantileExactIf(0.01)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p99_fps " +
    "FROM apm_frame_scene_summary FINAL WHERE app_id = '$AppId' AND event_time >= '$from' AND event_time < '$to' GROUP BY algorithm_version ORDER BY algorithm_version FORMAT JSONEachRow")
Assert-Condition ($fpsRows.Count -eq 2) ('FPS 算法版本应有 2 组，实际为 ' + $fpsRows.Count)
$fpsByAlgorithm = @{}
foreach ($row in $fpsRows) { $fpsByAlgorithm[$row.algorithm_version] = $row }
Assert-Condition ([int64]$fpsByAlgorithm['fps-v1'].total_records -eq 2 -and [double]$fpsByAlgorithm['fps-v1'].average_fps -eq 40 -and
    [double]$fpsByAlgorithm['fps-v1'].p50_fps -eq 50 -and [double]$fpsByAlgorithm['fps-v1'].p90_fps -eq 30 -and
    [double]$fpsByAlgorithm['fps-v1'].p99_fps -eq 30) 'fps-v1 应为 2 条、平均 40、P50/P90/P99 为 50/30/30。'
Assert-Condition ([int64]$fpsByAlgorithm['fps-v2'].total_records -eq 1 -and [double]$fpsByAlgorithm['fps-v2'].average_fps -eq 45) 'fps-v2 应为 1 条、平均 45。'

$suspensionRowsResult = Invoke-ClickHouseRows -Query ("SELECT algorithm_version, sum(segment_records) AS total_records, " +
    "countIf(foreground_duration_ms > 0) AS valid_device_day_records, avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_seconds_per_hour, " +
    "quantileExactIf(0.50)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p50_seconds_per_hour, " +
    "quantileExactIf(0.90)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p90_seconds_per_hour, " +
    "quantileExactIf(0.99)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p99_seconds_per_hour FROM (" +
    "SELECT algorithm_version, anonymous_device_id, toDate(event_time) AS utc_date, app_version, channel, environment, os_version, device_model, " +
    "count() AS segment_records, sum(foreground_duration_ms) AS foreground_duration_ms, sum(suspension_duration_ms) AS suspension_duration_ms, " +
    "if(sum(foreground_duration_ms) > 0, sum(suspension_duration_ms) / 1000.0 / (sum(foreground_duration_ms) / 3600000.0), 0.0) AS suspension_seconds_per_hour " +
    "FROM apm_device_suspension_segment FINAL WHERE app_id = '$AppId' AND event_time >= '$from' AND event_time < '$to' " +
    "GROUP BY algorithm_version, anonymous_device_id, utc_date, app_version, channel, environment, os_version, device_model" +
    ") AS device_days GROUP BY algorithm_version ORDER BY algorithm_version SETTINGS prefer_column_name_to_alias = 1 FORMAT JSONEachRow")
Assert-Condition ($suspensionRowsResult.Count -eq 2) ('挂起算法版本应有 2 组，实际为 ' + $suspensionRowsResult.Count)
foreach ($row in $suspensionRowsResult) {
    $expectedTotal = if ($row.algorithm_version -eq 'suspension-v1') { 2 } else { 1 }
    $expectedRate = if ($row.algorithm_version -eq 'suspension-v1') { 3.0 } else { 2.0 }
    Assert-Condition ([int64]$row.total_records -eq $expectedTotal -and [int64]$row.valid_device_day_records -eq 1 -and
        [Math]::Abs([double]$row.average_seconds_per_hour - $expectedRate) -lt 0.0001) ('挂起率分组结果错误: ' + $row.algorithm_version)
}

$fpsTrendRows = Invoke-ClickHouseRows -Query ("SELECT toStartOfHour(event_time, 'UTC') AS bucket_start, addHours(bucket_start, 1) AS bucket_end, " +
    "algorithm_version, count() AS total_records, countIf(active_duration_ms > 0 AND ui_refresh_frame_count > 0 " +
    "AND isFinite(refresh_rate_hz) AND refresh_rate_hz > 0 AND isFinite(normalized_fps60) AND normalized_fps60 >= 0) AS valid_records, " +
    "avgIf(toFloat64(normalized_fps60), active_duration_ms > 0 AND ui_refresh_frame_count > 0 AND isFinite(refresh_rate_hz) " +
    "AND refresh_rate_hz > 0 AND isFinite(normalized_fps60) AND normalized_fps60 >= 0) AS average_value, " +
    "quantileExactIf(0.50)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p50_value, " +
    "quantileExactIf(0.10)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p90_value, " +
    "quantileExactIf(0.01)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p99_value " +
    "FROM apm_frame_scene_summary FINAL WHERE app_id = '$AppId' AND event_time >= '$from' AND event_time < '$to' " +
    "GROUP BY bucket_start, algorithm_version ORDER BY bucket_start, algorithm_version LIMIT 50 SETTINGS max_execution_time = 5 FORMAT JSONEachRow")
Assert-Condition ($fpsTrendRows.Count -eq 2) ('FPS 小时趋势应有 2 个时间桶/算法点，实际为 ' + $fpsTrendRows.Count)
Assert-Condition ([double]$fpsTrendRows[0].average_value -eq 40 -and [double]$fpsTrendRows[0].p50_value -eq 50 -and
    [double]$fpsTrendRows[0].p90_value -eq 30 -and [double]$fpsTrendRows[1].average_value -eq 45) 'FPS 小时趋势首点应为平均 40、P50/P90 为 50/30，第二点应为 45。'

$suspensionTrendRows = Invoke-ClickHouseRows -Query ("SELECT toDateTime(utc_date, 'UTC') AS bucket_start, addDays(bucket_start, 1) AS bucket_end, " +
    "algorithm_version, sum(segment_records) AS total_records, countIf(foreground_duration_ms > 0) AS valid_records, " +
    "avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_value, " +
    "quantileExactIf(0.50)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p50_value, " +
    "quantileExactIf(0.90)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p90_value, " +
    "quantileExactIf(0.99)(suspension_seconds_per_hour, foreground_duration_ms > 0) AS p99_value FROM (" +
    "SELECT algorithm_version, anonymous_device_id, toDate(event_time) AS utc_date, app_version, channel, environment, os_version, device_model, " +
    "count() AS segment_records, sum(foreground_duration_ms) AS foreground_duration_ms, sum(suspension_duration_ms) AS suspension_duration_ms, " +
    "if(sum(foreground_duration_ms) > 0, sum(suspension_duration_ms) / 1000.0 / (sum(foreground_duration_ms) / 3600000.0), 0.0) AS suspension_seconds_per_hour " +
    "FROM apm_device_suspension_segment FINAL WHERE app_id = '$AppId' AND event_time >= '$from' AND event_time < '$to' " +
    "GROUP BY algorithm_version, anonymous_device_id, utc_date, app_version, channel, environment, os_version, device_model" +
    ") AS device_days GROUP BY utc_date, algorithm_version ORDER BY bucket_start, algorithm_version LIMIT 50 " +
    "SETTINGS max_execution_time = 5, prefer_column_name_to_alias = 1 FORMAT JSONEachRow")
Assert-Condition ($suspensionTrendRows.Count -eq 2) ('挂起率 UTC 天趋势应有 2 个时间桶/算法点，实际为 ' + $suspensionTrendRows.Count)
foreach ($row in $suspensionTrendRows) {
    $expectedTotal = if ($row.algorithm_version -eq 'suspension-v1') { 2 } else { 1 }
    $expectedRate = if ($row.algorithm_version -eq 'suspension-v1') { 3.0 } else { 2.0 }
    Assert-Condition ([int64]$row.total_records -eq $expectedTotal -and [int64]$row.valid_records -eq 1 -and
        [Math]::Abs([double]$row.average_value - $expectedRate) -lt 0.0001) ('挂起率趋势结果错误: ' + $row.algorithm_version)
}

$factIds = @((Invoke-ClickHouseRows -Query "SELECT event_id FROM apm_jank_event FINAL WHERE app_id = '$AppId' ORDER BY event_id FORMAT JSONEachRow") | ForEach-Object { $_.event_id })
$detailIds = @((Invoke-ClickHouseRows -Query "SELECT event_id FROM apm_jank_detail FINAL WHERE app_id = '$AppId' ORDER BY event_id FORMAT JSONEachRow") | ForEach-Object { $_.event_id })
Assert-Condition ($factIds.Count -eq 3 -and $detailIds.Count -eq 3) '事实和详情 FINAL 数量应均为 3。'
Assert-Condition ((Compare-Object ($factIds | Sort-Object) ($detailIds | Sort-Object)).Count -eq 0) '事实与详情 event_id 集合不一致。'

Write-Host ('ClickHouse 004 验收通过，app_id=' + $AppId)
Write-Host ('fixture_input_rows=' + $events.Count + ', fixture_unique_event_ids=' + (($events | Select-Object -ExpandProperty eventId | Sort-Object -Unique).Count))
Write-Host ('raw=' + (ConvertTo-CompactJson $rawCounts))
Write-Host ('facts_details=' + (ConvertTo-CompactJson $factCounts))
Write-Host ('issue=' + (ConvertTo-CompactJson $issueRows))
Write-Host ('fps=' + (ConvertTo-CompactJson $fpsRows))
Write-Host ('suspension=' + (ConvertTo-CompactJson $suspensionRowsResult))
Write-Host ('fps_trend=' + (ConvertTo-CompactJson $fpsTrendRows))
Write-Host ('suspension_trend=' + (ConvertTo-CompactJson $suspensionTrendRows))
