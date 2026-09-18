-- 为六类原始事件记录增加进程实例身份；空字符串表示迁移前历史记录缺失该字段。
ALTER TABLE apm.apm_event_raw
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';

ALTER TABLE apm.apm_jank_event
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';

ALTER TABLE apm.apm_frame_scene_summary
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';

ALTER TABLE apm.apm_device_suspension_segment
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';

ALTER TABLE apm.apm_memory_sample
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';

ALTER TABLE apm.apm_memory_report
    ADD COLUMN IF NOT EXISTS process_id String DEFAULT '';
