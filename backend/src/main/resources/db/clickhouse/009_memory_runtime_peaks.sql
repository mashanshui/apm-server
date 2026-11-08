-- 运行峰值按最后持久化采样月份分区，与旧样本表隔离。
CREATE TABLE IF NOT EXISTS apm.apm_memory_peak_summary
(
    app_id UUID,
    package_name LowCardinality(String),
    event_id String,
    event_time DateTime64(3, 'UTC'),
    received_time DateTime64(3, 'UTC'),
    schema_version UInt16,
    session_id String,
    process_id String,
    anonymous_device_id String,
    app_version LowCardinality(String),
    version_code Int64,
    build_id String,
    channel LowCardinality(String),
    environment LowCardinality(String),
    os_version LowCardinality(String),
    device_model LowCardinality(String),
    network_type LowCardinality(String),
    pss_bytes Nullable(UInt64),
    vss_bytes Nullable(UInt64),
    java_heap_used_bytes Nullable(UInt64),
    process_name String,
    collection_started_at UInt64,
    pss_occurred_at Nullable(UInt64),
    pss_foreground Nullable(UInt8),
    pss_scene Nullable(String),
    vss_occurred_at Nullable(UInt64),
    vss_foreground Nullable(UInt8),
    vss_scene Nullable(String),
    java_heap_occurred_at Nullable(UInt64),
    java_heap_foreground Nullable(UInt8),
    java_heap_scene Nullable(String)
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, event_id)
TTL event_time + INTERVAL 90 DAY;
