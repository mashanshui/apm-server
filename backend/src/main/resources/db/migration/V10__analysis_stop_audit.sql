-- 停止事实与业务结果独立；失联或租约到期不能伪造停止。
ALTER TABLE crash_analysis_run ADD COLUMN stopped_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crash_analysis_run ADD COLUMN stopped_by UUID REFERENCES apm_user(id);
ALTER TABLE crash_analysis_run ADD COLUMN stop_basis VARCHAR(2000);
