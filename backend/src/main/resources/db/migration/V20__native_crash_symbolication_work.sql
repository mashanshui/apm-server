-- 只允许在旧执行器与未确认发布排空后切换；不重算或清除任何历史结果。
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM crash_symbolication_job WHERE lease_owner IS NOT NULL AND lease_expires_at > clock_timestamp())
 OR EXISTS (SELECT 1 FROM crash_symbolication_publication WHERE published_at IS NULL) THEN
  RAISE EXCEPTION 'Native 工作迁移前必须排空租约及未确认发布';
 END IF;
END $$;

-- 旧工作保留 JVM 类型与既有计数；Native 按应用、版本和类型独立登记。
ALTER TABLE crash_symbolication_job ADD COLUMN crash_kind VARCHAR(8) NOT NULL DEFAULT 'jvm';
ALTER TABLE crash_symbolication_job ADD COLUMN published_count BIGINT NOT NULL DEFAULT 0 CHECK (published_count >= 0);
ALTER TABLE crash_symbolication_job ADD COLUMN failed_unit_count BIGINT NOT NULL DEFAULT 0 CHECK (failed_unit_count >= 0);
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_kind_ck CHECK (crash_kind IN ('jvm','native'));
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_legacy_kind_ck CHECK (app_version IS NOT NULL OR crash_kind = 'jvm');
ALTER TABLE crash_symbolication_job DROP CONSTRAINT crash_symbolication_job_state_check;
ALTER TABLE crash_symbolication_job ADD CONSTRAINT crash_job_state_kind_ck CHECK (
 (crash_kind = 'jvm' AND state IN ('queued','running','waiting_mapping','retry_wait','idle')) OR
 (crash_kind = 'native' AND state IN ('queued','running','waiting_symbols','retry_wait','idle')));
DROP INDEX crash_version_job_identity_uk;
CREATE UNIQUE INDEX crash_version_job_identity_uk ON crash_symbolication_job(app_id,app_version,crash_kind)
 WHERE app_version IS NOT NULL;

-- 旧成功行固定为 JVM，保留来源；Native 以单元记录来源并绑定增量基础版本。
ALTER TABLE crash_symbolication_publication ADD COLUMN crash_kind VARCHAR(8) NOT NULL DEFAULT 'jvm';
ALTER TABLE crash_symbolication_publication ADD COLUMN base_state_version BIGINT;
ALTER TABLE crash_symbolication_publication ALTER COLUMN symbol_id DROP NOT NULL;
ALTER TABLE crash_symbolication_publication ALTER COLUMN symbol_revision DROP NOT NULL;
ALTER TABLE crash_symbolication_publication ALTER COLUMN symbol_sha256 DROP NOT NULL;
ALTER TABLE crash_symbolication_publication ADD CONSTRAINT crash_publication_kind_source_ck CHECK (
 (crash_kind = 'jvm' AND base_state_version IS NULL AND symbol_id IS NOT NULL
  AND symbol_revision IS NOT NULL AND symbol_sha256 IS NOT NULL) OR
 (crash_kind = 'native' AND base_state_version IS NOT NULL AND base_state_version >= 0
  AND base_state_version < state_version AND symbol_id IS NULL AND symbol_revision IS NULL AND symbol_sha256 IS NULL));
