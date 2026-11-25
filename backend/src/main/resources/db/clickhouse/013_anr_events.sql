-- 同一应用的事件身份固定分区，重试不能因发生时间变更进入另一分区。
CREATE TABLE IF NOT EXISTS apm.apm_anr_events
(
    app_id UUID,
    event_id String,
    event_time DateTime64(3, 'UTC'),
    received_time DateTime64(3, 'UTC'),
    first_seen_version UInt64,
    app_version LowCardinality(String),
    version_code Int64,
    device_model LowCardinality(String),
    source LowCardinality(String),
    anonymous_device_id String,
    metadata_json String,
    payload_json String,
    INDEX event_time_bounds event_time TYPE minmax GRANULARITY 1
)
ENGINE = ReplacingMergeTree(first_seen_version)
PARTITION BY cityHash64(app_id, event_id) % 32
ORDER BY (app_id, event_id)
TTL event_time + INTERVAL 30 DAY
SETTINGS non_replicated_deduplication_window = 1000000;
