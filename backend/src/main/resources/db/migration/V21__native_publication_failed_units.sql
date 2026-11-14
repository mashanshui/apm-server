-- 待确认快照固定新增失败次数，使重启后的确认与登记使用同一计数。
ALTER TABLE crash_symbolication_publication ADD COLUMN native_failed_unit_count BIGINT NOT NULL DEFAULT 0
 CHECK (native_failed_unit_count >= 0 AND (crash_kind = 'native' OR native_failed_unit_count = 0));
