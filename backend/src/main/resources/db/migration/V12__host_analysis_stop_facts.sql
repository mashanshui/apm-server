-- 宿主分析将本地材料工具停止和宿主停止核验分别记录。
ALTER TABLE crash_analysis_run ADD COLUMN local_tools_stopped BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE crash_analysis_run ADD COLUMN host_stop_state VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE';
ALTER TABLE crash_analysis_run ADD CONSTRAINT crash_analysis_host_stop_ck CHECK (host_stop_state IN ('UNKNOWN','CONFIRMED','NOT_APPLICABLE'));
UPDATE crash_analysis_run SET local_tools_stopped=stop_confirmed;
