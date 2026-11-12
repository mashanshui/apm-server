-- 版本身份切换前必须排空仍有效的租约及未确认发布，不能丢失在途结果。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM crash_symbolication_job
               WHERE lease_owner IS NOT NULL AND lease_expires_at > clock_timestamp())
       OR EXISTS (SELECT 1 FROM crash_symbolication_publication WHERE published_at IS NULL) THEN
        RAISE EXCEPTION '版本化符号表迁移前必须排空在途租约和未确认发布';
    END IF;
END $$;

-- 历史 mapping 保留缺失版本，不猜测其版本归属；新登记由服务层要求版本。
ALTER TABLE app_symbol_file ALTER COLUMN build_id DROP NOT NULL;
ALTER TABLE app_symbol_file ALTER COLUMN build_id TYPE VARCHAR(256);
ALTER TABLE app_symbol_file DROP CONSTRAINT app_symbol_file_build_id_ck;
ALTER TABLE app_symbol_file ADD COLUMN symbol_type VARCHAR(16) NOT NULL DEFAULT 'MAPPING';
ALTER TABLE app_symbol_file ADD COLUMN app_version VARCHAR(128);
ALTER TABLE app_symbol_file ADD COLUMN native_abi VARCHAR(16);
ALTER TABLE app_symbol_file ADD COLUMN elf_build_id VARCHAR(128);
ALTER TABLE app_symbol_file ADD COLUMN has_function_symbols BOOLEAN;
ALTER TABLE app_symbol_file ADD COLUMN has_debug_info BOOLEAN;
ALTER TABLE app_symbol_file ADD COLUMN has_debug_line BOOLEAN;

-- 原值用于精确匹配，不能接受控制字符、边缘空白或空字符串。
ALTER TABLE app_symbol_file ADD CONSTRAINT app_symbol_version_ck CHECK (
    app_version IS NULL OR (length(app_version) BETWEEN 1 AND 128
        AND app_version !~ '[[:cntrl:]]' AND app_version !~ '^[[:space:]]|[[:space:]]$'));
ALTER TABLE app_symbol_file ADD CONSTRAINT app_symbol_build_ck CHECK (
    build_id IS NULL OR (length(build_id) BETWEEN 1 AND 256
        AND build_id !~ '[[:cntrl:]]' AND build_id !~ '^[[:space:]]|[[:space:]]$'));
ALTER TABLE app_symbol_file ADD CONSTRAINT app_symbol_type_fields_ck CHECK (
    (symbol_type = 'MAPPING' AND (app_version IS NOT NULL OR build_id IS NOT NULL)
        AND native_abi IS NULL AND elf_build_id IS NULL AND has_function_symbols IS NULL
        AND has_debug_info IS NULL AND has_debug_line IS NULL)
    OR (symbol_type = 'NATIVE_SO' AND app_version IS NOT NULL AND build_id IS NULL
        AND native_abi IS NOT NULL AND native_abi IN ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
        AND elf_build_id IS NOT NULL AND elf_build_id ~ '^([0-9a-f]{2}){1,64}$' AND has_function_symbols IS NOT NULL
        AND has_debug_info IS NOT NULL AND has_debug_line IS NOT NULL
        AND (has_function_symbols OR has_debug_info OR has_debug_line)));

-- 三种活动身份分开约束；旧无版本记录独立保留原构建的活动唯一性。
DROP INDEX app_symbol_file_active_build_uk;
CREATE UNIQUE INDEX app_symbol_file_active_legacy_uk ON app_symbol_file (app_id, build_id)
    WHERE deleted_at IS NULL AND symbol_type = 'MAPPING' AND app_version IS NULL;
CREATE UNIQUE INDEX app_symbol_file_active_default_uk ON app_symbol_file (app_id, app_version)
    WHERE deleted_at IS NULL AND symbol_type = 'MAPPING' AND app_version IS NOT NULL AND build_id IS NULL;
CREATE UNIQUE INDEX app_symbol_file_active_version_build_uk ON app_symbol_file (app_id, app_version, build_id)
    WHERE deleted_at IS NULL AND symbol_type = 'MAPPING' AND app_version IS NOT NULL AND build_id IS NOT NULL;
CREATE UNIQUE INDEX app_symbol_file_active_native_uk ON app_symbol_file (app_id, app_version, native_abi, elf_build_id)
    WHERE deleted_at IS NULL AND symbol_type = 'NATIVE_SO';

-- 审计快照独立保存删除或替换时的身份，历史行保持无版本。
ALTER TABLE app_symbol_file_audit ALTER COLUMN build_id DROP NOT NULL;
ALTER TABLE app_symbol_file_audit ALTER COLUMN build_id TYPE VARCHAR(256);
ALTER TABLE app_symbol_file_audit ADD COLUMN symbol_type VARCHAR(16) NOT NULL DEFAULT 'MAPPING';
ALTER TABLE app_symbol_file_audit ADD COLUMN app_version VARCHAR(128);
ALTER TABLE app_symbol_file_audit ADD COLUMN native_abi VARCHAR(16);
ALTER TABLE app_symbol_file_audit ADD COLUMN elf_build_id VARCHAR(128);
ALTER TABLE app_symbol_file_audit ADD COLUMN has_function_symbols BOOLEAN;
ALTER TABLE app_symbol_file_audit ADD COLUMN has_debug_info BOOLEAN;
ALTER TABLE app_symbol_file_audit ADD COLUMN has_debug_line BOOLEAN;

-- 审计的类型和身份快照遵守与当前记录相同的互斥规则。
ALTER TABLE app_symbol_file_audit ADD CONSTRAINT app_symbol_audit_version_ck CHECK (
    app_version IS NULL OR (length(app_version) BETWEEN 1 AND 128
        AND app_version !~ '[[:cntrl:]]' AND app_version !~ '^[[:space:]]|[[:space:]]$'));
ALTER TABLE app_symbol_file_audit ADD CONSTRAINT app_symbol_audit_build_ck CHECK (
    build_id IS NULL OR (length(build_id) BETWEEN 1 AND 256
        AND build_id !~ '[[:cntrl:]]' AND build_id !~ '^[[:space:]]|[[:space:]]$'));
ALTER TABLE app_symbol_file_audit ADD CONSTRAINT app_symbol_audit_scope_ck CHECK (
    (symbol_type = 'MAPPING' AND (app_version IS NOT NULL OR build_id IS NOT NULL)
        AND native_abi IS NULL AND elf_build_id IS NULL AND has_function_symbols IS NULL
        AND has_debug_info IS NULL AND has_debug_line IS NULL)
    OR (symbol_type = 'NATIVE_SO' AND app_version IS NOT NULL AND build_id IS NULL
        AND native_abi IS NOT NULL AND native_abi IN ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
        AND elf_build_id IS NOT NULL AND elf_build_id ~ '^([0-9a-f]{2}){1,64}$'
        AND has_function_symbols IS NOT NULL AND has_debug_info IS NOT NULL AND has_debug_line IS NOT NULL
        AND (has_function_symbols OR has_debug_info OR has_debug_line)));
