-- 派生分区独立于原始 ANR 事实，固定成功版本覆盖临时失败版本。
CREATE TABLE IF NOT EXISTS apm.apm_anr_symbolication
(
    app_id UUID,
    event_id String,
    section LowCardinality(String),
    event_time DateTime64(3, 'UTC'),
    state_version UInt64,
    view_json String
)
ENGINE = ReplacingMergeTree(state_version)
PARTITION BY cityHash64(app_id, event_id) % 32
ORDER BY (app_id, event_id, section)
TTL event_time + INTERVAL 30 DAY
SETTINGS non_replicated_deduplication_window = 1000000;
