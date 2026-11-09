-- 仅扩展当前 Crash 数据集；历史行保留空诊断与空摘要，不重建旧现场。
ALTER TABLE apm.apm_crash_fact
    ADD COLUMN IF NOT EXISTS diagnostics_payload String DEFAULT '' CODEC(ZSTD),
    ADD COLUMN IF NOT EXISTS capture_sha256 String DEFAULT '';

-- 原始或还原后的辅助线程栈属于同一状态版本，TTL 继续沿用原发生时间。
ALTER TABLE apm.apm_crash_state
    ADD COLUMN IF NOT EXISTS thread_stacks_payload String DEFAULT '' CODEC(ZSTD),
    ADD COLUMN IF NOT EXISTS capture_sha256 String DEFAULT '';
