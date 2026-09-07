-- JVM Crash 首期 ClickHouse 初始化脚本。
-- 所有对象使用 IF NOT EXISTS；重复执行不会删除或改写已有表。

CREATE DATABASE IF NOT EXISTS apm;

CREATE TABLE IF NOT EXISTS apm.apm_event_raw
(
    project_id             String,
    app_id                 LowCardinality(String),
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
    duration_ms            Nullable(Float64),
    status                 LowCardinality(String),
    measurements           Map(String, Float64),
    attributes             Map(String, String),
    crash_kind             LowCardinality(String) DEFAULT '',
    crash_fatal            UInt8 DEFAULT 0,
    crash_exception_type   String DEFAULT '',
    crash_fingerprint      String DEFAULT '',
    fingerprint_version    LowCardinality(String) DEFAULT '',
    symbolication_status   LowCardinality(String) DEFAULT ''
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_type, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_crash_detail
(
    project_id                    String,
    event_id                      String,
    event_time                    DateTime64(3, 'UTC'),
    received_time                 DateTime64(3, 'UTC'),
    build_id                      String,
    crash_fingerprint             String,
    fingerprint_version           LowCardinality(String),
    symbolication_status          LowCardinality(String),
    throwable_chain_json          String CODEC(ZSTD),
    raw_stack_json                String CODEC(ZSTD),
    symbolicated_stack_json       String DEFAULT '' CODEC(ZSTD),
    symbolicated_fingerprint      String DEFAULT ''
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (project_id, event_id, event_time)
TTL event_time + INTERVAL 30 DAY;

CREATE TABLE IF NOT EXISTS apm.apm_event_hourly
(
    hour                 DateTime('UTC'),
    project_id           String,
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
ORDER BY (project_id, event_type, hour, app_version, channel, environment, os_version, device_model)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm.apm_event_hourly_mv
TO apm.apm_event_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    project_id,
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
GROUP BY hour, project_id, app_version, channel, environment, os_version, device_model, event_type;

CREATE TABLE IF NOT EXISTS apm.apm_crash_issue_hourly
(
    hour                 DateTime('UTC'),
    project_id           String,
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
    first_seen           SimpleAggregateFunction(min, DateTime64(3, 'UTC')),
    last_seen            SimpleAggregateFunction(max, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (project_id, crash_fingerprint, fingerprint_version, exception_type, hour, app_version, channel, environment, os_version, device_model)
TTL hour + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm.apm_crash_issue_hourly_mv
TO apm.apm_crash_issue_hourly
AS SELECT
    toStartOfHour(event_time) AS hour,
    project_id,
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
GROUP BY hour, project_id, app_version, channel, environment, os_version, device_model,
         crash_fingerprint, fingerprint_version, exception_type;
