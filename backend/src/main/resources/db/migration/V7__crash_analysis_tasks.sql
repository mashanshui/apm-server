-- 单事件任务保存固定输入，原始事件与 Issue 聚合保持独立。
CREATE TABLE crash_analysis_task (
    task_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES apm_app(app_id),
    event_id VARCHAR(128) NOT NULL,
    idempotency_key UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES apm_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    build_id VARCHAR(128),
    fingerprint VARCHAR(256) NOT NULL,
    mapping_revision BIGINT,
    preparation_generation BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(20) NOT NULL CHECK (state IN ('BLOCKED','READY','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED')),
    block_reason VARCHAR(64),
    model_config JSONB NOT NULL,
    evidence_id UUID,
    evidence JSONB,
    evidence_text TEXT,
    evidence_sha256 VARCHAR(64),
    CONSTRAINT crash_analysis_idempotency_uk UNIQUE (app_id,idempotency_key),
    CONSTRAINT crash_analysis_evidence_size_ck CHECK (evidence IS NULL OR octet_length(evidence::text) <= 1048576),
    CONSTRAINT crash_analysis_evidence_text_size_ck CHECK (evidence_text IS NULL OR octet_length(evidence_text) <= 1048576),
    CONSTRAINT crash_analysis_evidence_content_ck CHECK (
        (evidence IS NULL AND evidence_text IS NULL) OR evidence = evidence_text::jsonb),
    CONSTRAINT crash_analysis_evidence_digest_ck CHECK (evidence_sha256 IS NULL OR evidence_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT crash_analysis_mapping_fk FOREIGN KEY (app_id,build_id,mapping_revision)
        REFERENCES analysis_build_revision(app_id,build_id,revision)
);
CREATE INDEX crash_analysis_event_page_idx ON crash_analysis_task(app_id,event_id,created_at DESC,task_id DESC);
CREATE INDEX crash_analysis_active_idx ON crash_analysis_task(app_id,state)
    WHERE state IN ('BLOCKED','READY','RUNNING','CANCELLING');
