-- 新契约只由创建任务的代码显式写入；既有证据和报告不自动升级。
ALTER TABLE crash_analysis_task ADD COLUMN contract_version INTEGER;
ALTER TABLE crash_analysis_task ADD CONSTRAINT crash_analysis_contract_ck
    CHECK (contract_version IS NULL OR contract_version = 3);
CREATE INDEX crash_analysis_current_event_idx
    ON crash_analysis_task (app_id, event_id, created_at DESC, task_id DESC)
    WHERE contract_version = 3;
CREATE INDEX crash_analysis_current_claim_idx
    ON crash_analysis_task (app_id, created_at, task_id)
    WHERE contract_version = 3 AND state = 'READY';

-- 主记录保留来源身份；删除只退出活动集合，重新上传生成另一 symbol_id。
ALTER TABLE app_symbol_file ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_symbol_file DROP CONSTRAINT app_symbol_file_app_build_uk;
CREATE UNIQUE INDEX app_symbol_file_active_build_uk
    ON app_symbol_file (app_id, build_id) WHERE deleted_at IS NULL;
CREATE INDEX app_symbol_file_active_page_idx
    ON app_symbol_file (app_id, updated_at DESC, symbol_id) WHERE deleted_at IS NULL;
-- 历史上传/替换不改写；DELETE 审计沿用删除时的版本与摘要。
ALTER TABLE app_symbol_file_audit ADD COLUMN action VARCHAR(16) NOT NULL DEFAULT 'UPSERT';
ALTER TABLE app_symbol_file_audit ADD CONSTRAINT app_symbol_audit_action_ck
    CHECK (action IN ('UPSERT', 'DELETE'));

-- 一构建一任务，唤醒代次与执行租约分离，防止执行期间到达的通知丢失。
CREATE TABLE crash_symbolication_job (
    app_id UUID NOT NULL REFERENCES apm_app(app_id) ON DELETE CASCADE,
    build_id VARCHAR(256) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'queued'
        CHECK (state IN ('queued','running','waiting_mapping','retry_wait','idle')),
    wake_generation BIGINT NOT NULL DEFAULT 1 CHECK (wake_generation > 0),
    claimed_wake_generation BIGINT NOT NULL DEFAULT 0,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_owner UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    succeeded_count BIGINT NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
    failed_count BIGINT NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    error_category VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (app_id, build_id),
    CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CHECK (claimed_wake_generation >= 0 AND claimed_wake_generation <= wake_generation),
    CHECK (lease_generation >= 0)
);
CREATE INDEX crash_symbolication_job_claim_idx
    ON crash_symbolication_job (next_run_at, app_id, build_id)
    WHERE state IN ('queued','retry_wait','running');
CREATE INDEX crash_symbolication_job_cleanup_idx ON crash_symbolication_job(updated_at);

-- 单实例轮转检查点持久化，重启不能让前页构建永久挤占后页。
CREATE TABLE crash_symbolication_reconcile (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    after_symbol_id UUID
);
INSERT INTO crash_symbolication_reconcile(singleton) VALUES (true);

-- 状态版本独立于事件接收时间；0 专用于首次接收，失败与成功由短事务分配。
CREATE SEQUENCE crash_symbolication_state_version AS BIGINT MINVALUE 1;
-- 只登记首次成功。未确认载荷可重放；确认后仅保留轻量幂等记录直至事件过期。
CREATE TABLE crash_symbolication_publication (
    app_id UUID NOT NULL REFERENCES apm_app(app_id) ON DELETE CASCADE,
    event_id VARCHAR(128) NOT NULL,
    build_id VARCHAR(256) NOT NULL,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version > 0),
    symbol_id UUID NOT NULL REFERENCES app_symbol_file(symbol_id),
    symbol_revision INTEGER NOT NULL CHECK (symbol_revision > 0),
    symbol_sha256 VARCHAR(64) NOT NULL CHECK (symbol_sha256 ~ '^[a-f0-9]{64}$'),
    result_sha256 VARCHAR(64) NOT NULL CHECK (result_sha256 ~ '^[a-f0-9]{64}$'),
    pending_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (app_id, event_id),
    CHECK (expires_at > event_time),
    CHECK (pending_payload IS NULL OR octet_length(pending_payload) <= 2097152),
    CHECK ((published_at IS NULL) = (pending_payload IS NOT NULL))
);
CREATE INDEX crash_symbolication_publication_pending_idx
    ON crash_symbolication_publication (created_at, app_id, event_id) WHERE published_at IS NULL;
CREATE INDEX crash_symbolication_publication_expiry_idx ON crash_symbolication_publication(expires_at);
