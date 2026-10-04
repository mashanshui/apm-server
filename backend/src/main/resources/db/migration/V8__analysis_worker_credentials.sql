-- Worker 独立执行凭据，仅存随机秘密摘要，禁止查询 Token 复用。
CREATE TABLE analysis_worker_credential (
    credential_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES apm_app(app_id),
    repository_id VARCHAR(64) NOT NULL REFERENCES analysis_repository(repository_id),
    analysis_type VARCHAR(16) NOT NULL CHECK (analysis_type='ANALYZE'),
    name VARCHAR(100) NOT NULL,
    token_sha256 VARCHAR(64) NOT NULL UNIQUE CHECK (token_sha256 ~ '^[a-f0-9]{64}$'),
    display_prefix VARCHAR(16) NOT NULL,
    created_by UUID NOT NULL REFERENCES apm_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()+INTERVAL '30 days',
    revoked_at TIMESTAMP WITH TIME ZONE,
    CHECK (expires_at>created_at)
);
CREATE INDEX analysis_worker_app_page_idx ON analysis_worker_credential(app_id,created_at DESC,credential_id DESC);
