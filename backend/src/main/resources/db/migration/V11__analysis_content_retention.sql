-- 终态时间用于有界内容到期扫描；保留任务及 Run 摘要，不删除未知活动环境。
ALTER TABLE crash_analysis_task ADD COLUMN terminal_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crash_analysis_task ADD COLUMN content_expired BOOLEAN NOT NULL DEFAULT false;
UPDATE crash_analysis_task SET terminal_at=created_at WHERE state IN ('SUCCEEDED','FAILED','CANCELLED');
CREATE INDEX crash_analysis_retention_idx ON crash_analysis_task(terminal_at,task_id)
    WHERE content_expired=false AND state IN ('SUCCEEDED','FAILED','CANCELLED');
