-- 内存泄漏报告为独立异常证据；不进入 apm_memory_sample 统计。
CREATE TABLE IF NOT EXISTS apm.apm_memory_report
(
    app_id UUID,
    event_id UUID,
    event_time DateTime64(3, 'UTC'),
    received_time DateTime64(3, 'UTC'),
    package_name LowCardinality(String),
    app_version LowCardinality(String),
    version_code Int64,
    anonymous_device_id String,
    process_name String,
    session_id String,
    build_id String,
    environment LowCardinality(String),
    channel LowCardinality(String),
    device_model String,
    scene String,
    manufacturer String,
    sdk_int Nullable(Int32),
    dump_reason String,
    report_json String,
    gc_paths_json String,
    payload_hash FixedString(64),
    attachment_digest Nullable(FixedString(64)),
    attachment_path String,
    attachment_bytes UInt64
)
ENGINE = ReplacingMergeTree(received_time)
PARTITION BY sipHash64(app_id, event_id) % 32
ORDER BY (app_id, event_id)
TTL event_time + INTERVAL 90 DAY;
