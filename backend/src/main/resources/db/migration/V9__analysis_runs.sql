-- 每次分配独立 Run；活动租约只用于接收当前执行身份的写回。
CREATE TABLE crash_analysis_run (
    run_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES crash_analysis_task(task_id),
    app_id UUID NOT NULL REFERENCES apm_app(app_id),
    credential_id UUID NOT NULL REFERENCES analysis_worker_credential(credential_id),
    request_id UUID NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt>0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    lease_generation BIGINT NOT NULL,
    lease_token VARCHAR(43),
    lease_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    stop_confirmed BOOLEAN NOT NULL DEFAULT false,
    error_code VARCHAR(64),
    result JSONB,
    result_text TEXT,
    result_sha256 VARCHAR(64),
    CONSTRAINT crash_analysis_run_request_uk UNIQUE (credential_id,request_id),
    CONSTRAINT crash_analysis_run_attempt_uk UNIQUE (task_id,attempt),
    CONSTRAINT crash_analysis_run_result_size_ck CHECK (result IS NULL OR octet_length(result::text)<=1048576),
    CONSTRAINT crash_analysis_run_result_text_size_ck CHECK (result_text IS NULL OR octet_length(result_text)<=1048576),
    CONSTRAINT crash_analysis_run_result_content_ck CHECK ((result IS NULL AND result_text IS NULL) OR (result IS NOT NULL AND result_text IS NOT NULL AND result=result_text::jsonb))
);
CREATE UNIQUE INDEX crash_analysis_run_active_task_idx ON crash_analysis_run(task_id) WHERE state='RUNNING';
CREATE UNIQUE INDEX crash_analysis_run_active_worker_idx ON crash_analysis_run(credential_id) WHERE state='RUNNING';
CREATE INDEX crash_analysis_run_expiry_idx ON crash_analysis_run(lease_expires_at) WHERE state='RUNNING';
ALTER TABLE crash_analysis_task ADD COLUMN current_run_id UUID REFERENCES crash_analysis_run(run_id);
