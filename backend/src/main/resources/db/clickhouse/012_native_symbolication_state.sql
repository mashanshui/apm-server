-- 历史 raw_native 必须先成为候选；状态读取后按实际单元计算剩余工作。
ALTER TABLE apm.apm_crash_state ADD COLUMN IF NOT EXISTS native_work_remaining UInt8
    DEFAULT toUInt8(stack_format IN ('raw_native', 'native_stack'));

-- 保留 JVM 原有格式组合和原始 Native 兼容入口，增加 Native 三态与 temporary 组合。
ALTER TABLE apm.apm_crash_state DROP CONSTRAINT IF EXISTS current_state_format;
ALTER TABLE apm.apm_crash_state ADD CONSTRAINT IF NOT EXISTS current_state_format CHECK
    if(stack_format = 'native_stack',
       grouping_kind = 'temporary' AND fingerprint_version = 'native-tmp-v1'
        AND symbol_id IS NULL AND symbol_revision IS NULL AND symbol_sha256 = ''
        AND (symbolication_status != 'symbolicated' OR processed_at IS NOT NULL),
    (symbolication_status = 'symbolicated' AND stack_format = 'retrace_text' AND grouping_kind = 'resolved')
    OR (symbolication_status IN ('pending','failed') AND stack_format = 'raw_chain' AND grouping_kind = 'temporary')
    OR (symbolication_status = 'pending' AND symbolication_reason = 'native_symbolication_deferred'
        AND stack_format = 'raw_native' AND grouping_kind = 'temporary'
        AND symbol_id IS NULL AND symbol_revision IS NULL AND symbol_sha256 = ''
        AND processed_at IS NULL AND retry_at IS NULL));

-- JVM 成功仍必须有单份来源；Native 成功的来源存在逐单元载荷中。
ALTER TABLE apm.apm_crash_state DROP CONSTRAINT IF EXISTS current_state_source;
ALTER TABLE apm.apm_crash_state ADD CONSTRAINT IF NOT EXISTS current_state_source CHECK
    symbolication_status != 'symbolicated'
    OR (stack_format = 'retrace_text' AND symbol_id IS NOT NULL AND symbol_revision IS NOT NULL
        AND length(symbol_sha256) = 64 AND processed_at IS NOT NULL)
    OR (stack_format = 'native_stack' AND symbol_id IS NULL AND symbol_revision IS NULL
        AND symbol_sha256 = '' AND processed_at IS NOT NULL);

-- JVM 状态不携带 Native 剩余工作标记，避免错误进入 Native 候选。
ALTER TABLE apm.apm_crash_state ADD CONSTRAINT IF NOT EXISTS native_work_remaining_valid CHECK
    native_work_remaining IN (0, 1) AND (stack_format IN ('raw_native', 'native_stack') OR native_work_remaining = 0);
