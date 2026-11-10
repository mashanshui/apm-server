-- 扩展当前状态约束；保留既有数据和旧迁移，仅允许未处理的 Native 原始材料。
ALTER TABLE apm.apm_crash_state DROP CONSTRAINT IF EXISTS current_state_format;

ALTER TABLE apm.apm_crash_state ADD CONSTRAINT IF NOT EXISTS current_state_format CHECK
    (symbolication_status = 'symbolicated' AND stack_format = 'retrace_text' AND grouping_kind = 'resolved')
    OR (symbolication_status IN ('pending','failed') AND stack_format = 'raw_chain' AND grouping_kind = 'temporary')
    OR (symbolication_status = 'pending' AND symbolication_reason = 'native_symbolication_deferred'
        AND stack_format = 'raw_native' AND grouping_kind = 'temporary'
        AND symbol_id IS NULL AND symbol_revision IS NULL AND symbol_sha256 = ''
        AND processed_at IS NULL AND retry_at IS NULL);
