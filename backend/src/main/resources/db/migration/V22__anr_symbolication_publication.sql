-- 首次可用结果固定原字节，ClickHouse 确认前可恢复重放。
CREATE TABLE anr_symbolication_publication (
    app_id UUID NOT NULL REFERENCES apm_app(app_id) ON DELETE CASCADE,
    event_id VARCHAR(128) NOT NULL,
    section VARCHAR(8) NOT NULL CHECK (section IN ('main', 'trace')),
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    pending_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (app_id, event_id, section),
    CHECK (expires_at > event_time),
    CHECK (pending_payload IS NULL OR octet_length(pending_payload) <= 262144),
    CHECK ((published_at IS NULL) = (pending_payload IS NOT NULL))
);

CREATE INDEX anr_symbolication_pending_idx ON anr_symbolication_publication(created_at, app_id, event_id)
    WHERE published_at IS NULL;
CREATE INDEX anr_symbolication_expiry_idx ON anr_symbolication_publication(expires_at);

-- 每个应用版本轮转扫描事件，重启后继续且迟到事件可在下一轮发现。
CREATE TABLE anr_symbolication_cursor (
    app_id UUID NOT NULL REFERENCES apm_app(app_id) ON DELETE CASCADE,
    app_version VARCHAR(128) NOT NULL,
    after_event_id VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (app_id, app_version)
);
