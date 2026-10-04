-- 宿主报告成功仍可能停止未知；按凭据有界查找门禁，不扫描已确认历史。
CREATE INDEX crash_analysis_run_unconfirmed_worker_idx ON crash_analysis_run(credential_id)
    WHERE state='RUNNING' OR stop_confirmed=false;
