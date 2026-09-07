-- 应用身份 ClickHouse schema v4。
-- 必须在 001-003 已执行且所有旧业务表为空时执行；任何非空旧表都会先失败。

CREATE DATABASE IF NOT EXISTS apm;

SELECT throwIf((SELECT count() FROM apm.apm_event_raw) > 0,
               '004 migration requires empty apm_event_raw');
SELECT throwIf((SELECT count() FROM apm.apm_crash_detail) > 0,
               '004 migration requires empty apm_crash_detail');
SELECT throwIf((SELECT count() FROM apm.apm_event_hourly) > 0,
               '004 migration requires empty apm_event_hourly');
SELECT throwIf((SELECT count() FROM apm.apm_crash_issue_hourly) > 0,
               '004 migration requires empty apm_crash_issue_hourly');
SELECT throwIf((SELECT count() FROM apm.apm_jank_event) > 0,
               '004 migration requires empty apm_jank_event');
SELECT throwIf((SELECT count() FROM apm.apm_jank_detail) > 0,
               '004 migration requires empty apm_jank_detail');
SELECT throwIf((SELECT count() FROM apm.apm_jank_issue_hourly) > 0,
               '004 migration requires empty apm_jank_issue_hourly');
SELECT throwIf((SELECT count() FROM apm.apm_frame_scene_summary) > 0,
               '004 migration requires empty apm_frame_scene_summary');
SELECT throwIf((SELECT count() FROM apm.apm_device_suspension_segment) > 0,
               '004 migration requires empty apm_device_suspension_segment');
SELECT throwIf((SELECT count() FROM apm.apm_device_suspension_daily) > 0,
               '004 migration requires empty apm_device_suspension_daily');

DROP VIEW IF EXISTS apm.apm_crash_issue_hourly_mv;
DROP VIEW IF EXISTS apm.apm_event_hourly_mv;
DROP VIEW IF EXISTS apm.apm_jank_issue_hourly_mv;

DROP TABLE apm.apm_crash_issue_hourly;
DROP TABLE apm.apm_event_hourly;
DROP TABLE apm.apm_crash_detail;
DROP TABLE apm.apm_event_raw;
DROP TABLE apm.apm_jank_issue_hourly;
DROP TABLE apm.apm_jank_detail;
DROP TABLE apm.apm_jank_event;
DROP TABLE apm.apm_frame_scene_summary;
DROP TABLE apm.apm_device_suspension_segment;
DROP TABLE apm.apm_device_suspension_daily;

CREATE TABLE apm.apm_event_raw
(
    app_id                 UUID,
    package_name           LowCardinality(String),
    event_id               String,
    event_type             LowCardinality(String),
    event_time             DateTime64(3, 'UTC'),
    received_time          DateTime64(3, 'UTC'),
    schema_version         UInt16,
    app_version            LowCardinality(String),
    version_code           Int64,
    build_id               String,
    channel                LowCardinality(String),
    environment            LowCardinality(String),
    session_id             String,
    anonymous_device_id    String,
    os_version             LowCardinality(String),
    device_model           LowCardinality(String),
    network_type           LowCardinality(String),
    duration_ms             Nullable(Float64),
    status                  LowCardinality(String),
    measurements            Map(String, Float64),
    attributes              Map(String, String),
    crash_kind              LowCardinality(String) DEFAULT '',
    crash_fatal             UInt8 DEFAULT 0,
    crash_exception_type    String DEFAULT '',
    crash_fingerprint       String DEFAULT '',
    fingerprint_version     LowCardinality(String) DEFAULT '',
    symbolication_status    LowCardinality(String) DEFAULT ''
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, event_type, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE apm.apm_crash_detail
(
    app_id                       UUID,
    event_id                     String,
    event_time                   DateTime64(3, 'UTC'),
    received_time                DateTime64(3, 'UTC'),
    build_id                     String,
    crash_fingerprint            String,
    fingerprint_version          LowCardinality(String),
    symbolication_status         LowCardinality(String),
    throwable_chain_json         String CODEC(ZSTD),
    raw_stack_json               String CODEC(ZSTD),
    symbolicated_stack_json      String DEFAULT '' CODEC(ZSTD),
    symbolicated_fingerprint     String DEFAULT ''
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE apm.apm_event_hourly
(
    hour                 DateTime('UTC'),
    app_id               UUID,
    app_version          LowCardinality(String),
    channel              LowCardinality(String),
    environment          LowCardinality(String),
    os_version           LowCardinality(String),
    device_model         LowCardinality(String),
    event_type           LowCardinality(String),
    event_ids            AggregateFunction(uniqCombined64, String),
    session_ids          AggregateFunction(uniqCombined64, String),
    device_ids           AggregateFunction(uniqCombined64, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, event_type, hour, app_version, channel, environment, os_version, device_model)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW apm.apm_event_hourly_mv
TO apm.apm_event_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    app_id,
    app_version,
    channel,
    environment,
    os_version,
    device_model,
    event_type,
    uniqCombined64State(event_id) AS event_ids,
    uniqCombined64State(session_id) AS session_ids,
    uniqCombined64State(anonymous_device_id) AS device_ids
FROM apm.apm_event_raw
GROUP BY hour, app_id, app_version, channel, environment, os_version, device_model, event_type;

CREATE TABLE apm.apm_crash_issue_hourly
(
    hour                 DateTime('UTC'),
    app_id               UUID,
    app_version          LowCardinality(String),
    channel              LowCardinality(String),
    environment          LowCardinality(String),
    os_version           LowCardinality(String),
    device_model         LowCardinality(String),
    crash_fingerprint    String,
    fingerprint_version  LowCardinality(String),
    exception_type       String,
    event_ids            AggregateFunction(uniqCombined64, String),
    session_ids          AggregateFunction(uniqCombined64, String),
    device_ids           AggregateFunction(uniqCombined64, String),
    first_seen            SimpleAggregateFunction(min, DateTime64(3, 'UTC')),
    last_seen             SimpleAggregateFunction(max, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, crash_fingerprint, fingerprint_version, exception_type, hour, app_version, channel, environment, os_version, device_model)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW apm.apm_crash_issue_hourly_mv
TO apm.apm_crash_issue_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    app_id,
    app_version,
    channel,
    environment,
    os_version,
    device_model,
    crash_fingerprint,
    fingerprint_version,
    crash_exception_type AS exception_type,
    uniqCombined64State(event_id) AS event_ids,
    uniqCombined64State(session_id) AS session_ids,
    uniqCombined64State(anonymous_device_id) AS device_ids,
    min(event_time) AS first_seen,
    max(event_time) AS last_seen
FROM apm.apm_event_raw
WHERE event_type = 'crash'
GROUP BY hour, app_id, app_version, channel, environment, os_version, device_model,
         crash_fingerprint, fingerprint_version, exception_type;

CREATE TABLE apm.apm_jank_event
(
    app_id                            UUID,
    package_name                      LowCardinality(String),
    event_id                          String,
    event_time                        DateTime64(3, 'UTC'),
    received_time                     DateTime64(3, 'UTC'),
    schema_version                    UInt16,
    session_id                        String,
    anonymous_device_id               String,
    app_version                       LowCardinality(String),
    version_code                      Int64,
    build_id                          String,
    channel                           LowCardinality(String),
    environment                       LowCardinality(String),
    os_version                        LowCardinality(String),
    device_model                      LowCardinality(String),
    network_type                      LowCardinality(String),
    scene                             LowCardinality(String),
    algorithm_version                 LowCardinality(String),
    message_duration_ns               UInt64,
    threshold_ns                      UInt64,
    sampling_interval_ns              UInt64,
    estimated_duration_ns             UInt64,
    estimated_unattributed_duration_ns UInt64,
    covered_duration_ns               UInt64,
    uncovered_duration_ns             UInt64,
    expected_sample_count             UInt32,
    parsed_sample_count               UInt32 DEFAULT 0,
    missing_sample_count              UInt32 DEFAULT 0,
    fingerprint                       String,
    fingerprint_version               LowCardinality(String),
    jank_payload_json                 String CODEC(ZSTD),
    jank_analysis_json                String CODEC(ZSTD)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE apm.apm_jank_detail
(
    app_id              UUID,
    event_id            String,
    event_time          DateTime64(3, 'UTC'),
    received_time       DateTime64(3, 'UTC'),
    fingerprint         String,
    fingerprint_version LowCardinality(String),
    stack_dictionary_json String CODEC(ZSTD),
    samples_json        String CODEC(ZSTD),
    call_tree_json      String CODEC(ZSTD),
    evidence_json       String CODEC(ZSTD)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE apm.apm_jank_issue_hourly
(
    hour                 DateTime('UTC'),
    app_id               UUID,
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
ORDER BY (app_id, fingerprint, fingerprint_version, hour, app_version, channel, environment, os_version, device_model, scene, algorithm_version)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW apm.apm_jank_issue_hourly_mv
TO apm.apm_jank_issue_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    app_id, app_version, channel, environment, os_version, device_model, scene, algorithm_version,
    fingerprint, fingerprint_version,
    uniqCombined64State(event_id) AS event_ids,
    uniqCombined64State(session_id) AS session_ids,
    uniqCombined64State(anonymous_device_id) AS device_ids,
    quantilesTDigestState(0.5, 0.9, 0.99)(toFloat64(message_duration_ns) / 1000000.0) AS exact_duration_p,
    quantilesTDigestState(0.5, 0.9, 0.99)(toFloat64(estimated_duration_ns) / 1000000.0) AS estimated_duration_p,
    min(event_time) AS first_seen,
    max(event_time) AS last_seen
FROM apm.apm_jank_event
GROUP BY hour, app_id, app_version, channel, environment, os_version, device_model, scene,
         algorithm_version, fingerprint, fingerprint_version;

CREATE TABLE apm.apm_frame_scene_summary
(
    app_id                  UUID,
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
ORDER BY (app_id, event_time, event_id)
TTL event_time + INTERVAL 90 DAY;

CREATE TABLE apm.apm_device_suspension_segment
(
    app_id                  UUID,
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
ORDER BY (app_id, anonymous_device_id, event_time, event_id)
TTL event_time + INTERVAL 90 DAY;

CREATE TABLE apm.apm_device_suspension_daily
(
    app_id                       UUID,
    utc_date                     Date,
    anonymous_device_id          String,
    app_version                  LowCardinality(String),
    channel                      LowCardinality(String),
    environment                  LowCardinality(String),
    os_version                   LowCardinality(String),
    device_model                 LowCardinality(String),
    algorithm_version            LowCardinality(String),
    foreground_duration_ms       UInt64,
    suspension_duration_ms       UInt64,
    suspension_count             UInt64,
    suspension_seconds_per_hour  Float64
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(utc_date)
ORDER BY (app_id, utc_date, anonymous_device_id, app_version, channel, environment, os_version, device_model, algorithm_version)
TTL utc_date + INTERVAL 365 DAY;
