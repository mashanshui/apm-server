-- V15 的匿名检查约束由 PostgreSQL 生成名称，按表达式定位并保留其余约束。
DO $$
DECLARE
    payload_constraint TEXT;
BEGIN
    SELECT conname INTO payload_constraint
      FROM pg_constraint
     WHERE conrelid = 'crash_symbolication_publication'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%octet_length(pending_payload)%';
    IF payload_constraint IS NULL THEN
        RAISE EXCEPTION 'Crash publication payload constraint missing';
    END IF;
    EXECUTE format('ALTER TABLE crash_symbolication_publication DROP CONSTRAINT %I', payload_constraint);
END $$;

-- 辅助线程和主异常同一次发布，单份登记限额扩展为 4 MiB，总在途预算不变。
ALTER TABLE crash_symbolication_publication
    ADD CONSTRAINT crash_symbolication_publication_payload_limit_ck
    CHECK (pending_payload IS NULL OR octet_length(pending_payload) <= 4194304);
