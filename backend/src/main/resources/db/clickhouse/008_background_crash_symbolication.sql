-- 当前 Crash 数据集独立初始化；不读取、转换或删除旧表。
CREATE DATABASE IF NOT EXISTS apm;

-- Crash 和 app_start 共用不可变事实，分母不能混入旧数据集。
CREATE TABLE IF NOT EXISTS apm.apm_crash_fact
(
    app_id UUID,
    event_id String,
    event_type LowCardinality(String),
    package_name LowCardinality(String),
    build_id String,
    event_time DateTime64(3, 'UTC'),
    received_time DateTime64(3, 'UTC'),
    schema_version UInt16,
    session_id String,
    process_id String,
    anonymous_device_id String,
    app_version LowCardinality(String),
    version_code Int64,
    environment LowCardinality(String),
    channel LowCardinality(String),
    os_version LowCardinality(String),
    device_model LowCardinality(String),
    network_type LowCardinality(String),
    crash_kind LowCardinality(String),
    crash_fatal UInt8,
    INDEX crash_fact_event_id event_id TYPE bloom_filter(0.01) GRANULARITY 1,
    CONSTRAINT current_fact_type CHECK event_type IN ('crash','app_start')
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, build_id, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;

-- 状态与指纹、单份堆栈、实际来源原子发布为同一行。摘要查询按列裁剪堆栈。
CREATE TABLE IF NOT EXISTS apm.apm_crash_state
(
    app_id UUID,
    event_id String,
    build_id String,
    event_time DateTime64(3, 'UTC'),
    state_version UInt64,
    symbolication_status LowCardinality(String),
    symbolication_reason LowCardinality(String),
    stack_format LowCardinality(String),
    stack_payload String CODEC(ZSTD),
    exception_type String,
    fingerprint String,
    fingerprint_version LowCardinality(String),
    grouping_kind LowCardinality(String),
    symbol_id Nullable(UUID),
    symbol_revision Nullable(UInt32),
    symbol_sha256 String,
    processed_at Nullable(DateTime64(3, 'UTC')),
    retry_at Nullable(DateTime64(3, 'UTC')),
    INDEX crash_state_event_id event_id TYPE bloom_filter(0.01) GRANULARITY 1,
    CONSTRAINT current_state_status CHECK symbolication_status IN ('pending','failed','symbolicated'),
    CONSTRAINT current_state_format CHECK
        (symbolication_status = 'symbolicated' AND stack_format = 'retrace_text' AND grouping_kind = 'resolved')
        OR (symbolication_status IN ('pending','failed') AND stack_format = 'raw_chain' AND grouping_kind = 'temporary'),
    CONSTRAINT current_state_source CHECK symbolication_status != 'symbolicated'
        OR (symbol_id IS NOT NULL AND symbol_revision IS NOT NULL AND length(symbol_sha256) = 64 AND processed_at IS NOT NULL)
)
ENGINE = ReplacingMergeTree(state_version)
PARTITION BY toYYYYMM(event_time)
ORDER BY (app_id, build_id, event_time, event_id)
TTL event_time + INTERVAL 30 DAY;
