-- 切换只允许排空后的执行器；历史结果及序列保持原字节与来源。
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM crash_symbolication_job WHERE lease_owner IS NOT NULL AND lease_expires_at>clock_timestamp())
 OR EXISTS (SELECT 1 FROM crash_symbolication_publication WHERE published_at IS NULL) THEN
 RAISE EXCEPTION '版本任务迁移前必须排空租约及未确认发布'; END IF;
END $$;

-- 原行、构建与累计计数全部保留，稳定身份避免清理重建后旧租约误命中新任务。
ALTER TABLE crash_symbolication_job ADD COLUMN job_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE crash_symbolication_job ADD COLUMN app_version VARCHAR(128);
ALTER TABLE crash_symbolication_job ADD COLUMN scan_event_time TIMESTAMPTZ;
ALTER TABLE crash_symbolication_job ADD COLUMN scan_event_id VARCHAR(128);
ALTER TABLE crash_symbolication_job DROP CONSTRAINT crash_symbolication_job_pkey;
ALTER TABLE crash_symbolication_job ALTER COLUMN build_id DROP NOT NULL;
ALTER TABLE crash_symbolication_job ADD PRIMARY KEY(job_id);
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_scope_ck CHECK (
 (app_version IS NULL AND build_id IS NOT NULL) OR (app_version IS NOT NULL AND build_id IS NULL));
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_version_ck CHECK (
 app_version IS NULL OR (length(app_version) BETWEEN 1 AND 128 AND app_version !~ '[[:cntrl:]]'
 AND app_version !~ '^[[:space:]]|[[:space:]]$'));
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_cursor_ck CHECK (
 (scan_event_time IS NULL)=(scan_event_id IS NULL));
CREATE UNIQUE INDEX crash_version_job_identity_uk ON crash_symbolication_job(app_id,app_version)
 WHERE app_version IS NOT NULL;
CREATE UNIQUE INDEX crash_legacy_job_identity_uk ON crash_symbolication_job(app_id,build_id)
 WHERE app_version IS NULL;
-- 不推导旧任务的发布版本。旧行明确退役，原计数及唤醒代次不重置。
UPDATE crash_symbolication_job SET state='waiting_mapping',error_category='legacy_unversioned',
 lease_owner=NULL,lease_expires_at=NULL WHERE app_version IS NULL;
CREATE INDEX crash_version_job_claim_idx ON crash_symbolication_job(next_run_at,job_id)
 WHERE app_version IS NOT NULL AND state IN ('queued','retry_wait','running');
ALTER TABLE crash_symbolication_publication ADD COLUMN app_version VARCHAR(128);
-- 旧已确认记录保持 NULL，不推导历史版本；新未确认结果必须带版本门禁。
ALTER TABLE crash_symbolication_publication ADD CONSTRAINT crash_publication_version_ck
 CHECK(published_at IS NOT NULL OR app_version IS NOT NULL);
CREATE INDEX crash_publication_version_pending_idx ON crash_symbolication_publication(app_id,app_version,created_at,event_id)
 WHERE published_at IS NULL;
UPDATE crash_symbolication_reconcile SET after_symbol_id=NULL;
