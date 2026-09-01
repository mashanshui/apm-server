-- Android 卡顿监控 ClickHouse schema v1。
-- 本脚本只新增对象，全部使用 IF NOT EXISTS；重复执行不会删除 Crash 数据。

CREATE DATABASE IF NOT EXISTS apm;

CREATE TABLE IF NOT EXISTS apm.apm_jank_event
(
    project_id                         String,
    app_id                             LowCardinality(String),
    event_id                           String,
    event_time                         DateTime64(3, 'UTC'),
    received_time                      DateTime64(3, 'UTC'),
    schema_version                     UInt16,
    session_id                         String,
    anonymous_device_id                String,
    app_version                        LowCardinality(String),
    version_code                       Int64,
    build_id                           String,
    channel                            LowCardinality(String),
    environment                        LowCardinality(String),
    os_version                         LowCardinality(String),
    device_model                       LowCardinality(String),
    network_type                       LowCardinality(String),
    scene                              LowCardinality(String),
    algorithm_version                  LowCardinality(String),
    message_duration_ns                UInt64,
    threshold_ns                       UInt64,
    sampling_interval_ns               UInt64,
    estimated_duration_ns              UInt64,
    estimated_unattributed_duration_ns UInt64,
    covered_duration_ns                UInt64,
    uncovered_duration_ns              UInt64,
    expected_sample_count              UInt32,
    attempted_sample_count             UInt32,
    successful_sample_count            UInt32,
    dropped_sample_count               UInt32,
    fingerprint                        String,
    fingerprint_version                LowCardinality(String),
    jank_payload_json                  String CODEC(ZSTD),
    jank_analysis_json                 String CODEC(ZSTD)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_jank_detail
(
    project_id          String,
    event_id            String,
    event_time          DateTime64(3, 'UTC'),
    received_time       DateTime64(3, 'UTC'),
    fingerprint         String,
    fingerprint_version LowCardinality(String),
    stack_dictionary_json String CODEC(ZSTD),
    samples_json          String CODEC(ZSTD),
    call_tree_json        String CODEC(ZSTD),
    evidence_json         String CODEC(ZSTD)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_jank_issue_hourly
(
    hour                 DateTime('UTC'),
    project_id           String,
    app_version          LowCardinality(String),
    channel              LowCardinality(String),
    environment          LowCardinality(String),
    os_version           LowCardinality(String),
    device_model         LowCardinality(String),
    scene                LowCardinality(String),
    algorithm_version    LowCardinality(String),
    fingerprint          String,
    fingerprint_version  LowCardinality(String),
    event_ids            AggregateFunction(uniqCombined64, String),
    session_ids          AggregateFunction(uniqCombined64, String),
    device_ids           AggregateFunction(uniqCombined64, String),
    exact_duration_p     AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), Float64),
    estimated_duration_p AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), Float64),
    first_seen           SimpleAggregateFunction(min, DateTime64(3, 'UTC')),
    last_seen            SimpleAggregateFunction(max, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (project_id, fingerprint, fingerprint_version, hour, app_version, channel, environment, os_version, device_model, scene, algorithm_version)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm.apm_jank_issue_hourly_mv
TO apm.apm_jank_issue_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    project_id, app_version, channel, environment, os_version, device_model, scene, algorithm_version,
    fingerprint, fingerprint_version,
    uniqCombined64State(event_id) AS event_ids,
    uniqCombined64State(session_id) AS session_ids,
    uniqCombined64State(anonymous_device_id) AS device_ids,
    quantilesTDigestState(0.5, 0.9, 0.99)(toFloat64(message_duration_ns) / 1000000.0) AS exact_duration_p,
    quantilesTDigestState(0.5, 0.9, 0.99)(toFloat64(estimated_duration_ns) / 1000000.0) AS estimated_duration_p,
    min(event_time) AS first_seen,
    max(event_time) AS last_seen
FROM apm.apm_jank_event
GROUP BY hour, project_id, app_version, channel, environment, os_version, device_model, scene,
         algorithm_version, fingerprint, fingerprint_version;

CREATE TABLE IF NOT EXISTS apm.apm_frame_scene_summary
(
    project_id              String,
    event_id                String,
    event_time              DateTime64(3, 'UTC'),
    received_time           DateTime64(3, 'UTC'),
    session_id              String,
    anonymous_device_id     String,
    app_version             LowCardinality(String),
    channel                 LowCardinality(String),
    environment             LowCardinality(String),
    os_version              LowCardinality(String),
    device_model            LowCardinality(String),
    scene                   LowCardinality(String),
    algorithm_version       LowCardinality(String),
    active_duration_ms      UInt64,
    ui_refresh_frame_count  UInt32,
    refresh_rate_hz         Float64,
    normalized_fps60        Float64,
    frame_duration_histogram_json String CODEC(ZSTD)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_time, event_id)
TTL event_time + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_device_suspension_segment
(
    project_id              String,
    event_id                String,
    event_time              DateTime64(3, 'UTC'),
    received_time           DateTime64(3, 'UTC'),
    session_id              String,
    anonymous_device_id     String,
    app_version             LowCardinality(String),
    channel                 LowCardinality(String),
    environment             LowCardinality(String),
    os_version              LowCardinality(String),
    device_model            LowCardinality(String),
    algorithm_version       LowCardinality(String),
    foreground_duration_ms  UInt64,
    suspension_duration_ms  UInt64,
    suspension_count        UInt32,
    threshold_ms             UInt32
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, anonymous_device_id, event_time, event_id)
TTL event_time + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_device_suspension_daily
(
    project_id              String,
    utc_date                Date,
    anonymous_device_id     String,
    app_version             LowCardinality(String),
    channel                 LowCardinality(String),
    environment             LowCardinality(String),
    os_version              LowCardinality(String),
    device_model            LowCardinality(String),
    algorithm_version       LowCardinality(String),
    foreground_duration_ms  UInt64,
    suspension_duration_ms  UInt64,
    suspension_count        UInt64,
    suspension_seconds_per_hour Float64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(utc_date)
ORDER BY (project_id, utc_date, anonymous_device_id, app_version, channel, environment, os_version, device_model, algorithm_version)
TTL utc_date + INTERVAL 365 DAY;
