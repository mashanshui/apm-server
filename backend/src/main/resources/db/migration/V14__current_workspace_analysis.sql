-- 切换必须先关闭旧任务入口并完成真实停止审计；迁移不伪造停止事实。
LOCK TABLE crash_analysis_task, crash_analysis_run, analysis_worker_credential IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM crash_analysis_task WHERE state IN ('BLOCKED','READY','RUNNING','CANCELLING'))
        OR EXISTS (SELECT 1 FROM crash_analysis_run WHERE state='RUNNING' OR stop_confirmed=false) THEN
        RAISE EXCEPTION 'ANALYSIS_MIGRATION_STOP_REQUIRED: 先结束旧任务并完成实际停止核验';
    END IF;
END $$;

-- 原证据、报告 JSON 和停止审计原样保留，旧任务仅供历史查看。
ALTER TABLE crash_analysis_task DROP CONSTRAINT crash_analysis_mapping_fk;
ALTER TABLE crash_analysis_task DROP COLUMN build_id, DROP COLUMN mapping_revision, DROP COLUMN model_config;
ALTER TABLE analysis_worker_credential DROP COLUMN repository_id, DROP COLUMN analysis_type;
DROP TABLE analysis_build_current;
DROP TABLE analysis_build_revision;
DROP TABLE analysis_repository;

-- 旧 Run 无工作区快照；新分配必须由 API 校验并固定此 ID。
ALTER TABLE crash_analysis_run ADD COLUMN snapshot_id UUID;
