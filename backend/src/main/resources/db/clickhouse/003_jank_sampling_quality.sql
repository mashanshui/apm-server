-- 卡顿采样质量字段迁移：新增服务端派生列，旧列保留用于回滚。

ALTER TABLE apm.apm_jank_event
    ADD COLUMN IF NOT EXISTS parsed_sample_count UInt32 AFTER expected_sample_count;

ALTER TABLE apm.apm_jank_event
    ADD COLUMN IF NOT EXISTS missing_sample_count UInt32 AFTER parsed_sample_count;
