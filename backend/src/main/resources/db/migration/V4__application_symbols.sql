CREATE TABLE app_symbol_file (
    symbol_id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    build_id VARCHAR(128) NOT NULL,
    revision INTEGER NOT NULL,
    storage_key VARCHAR(160) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES apm_user (id),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT app_symbol_file_build_id_ck CHECK (build_id ~ '^[A-Za-z0-9._-]{1,128}$' AND build_id NOT IN ('.', '..')),
    CONSTRAINT app_symbol_file_revision_ck CHECK (revision > 0),
    CONSTRAINT app_symbol_file_size_ck CHECK (size_bytes > 0),
    CONSTRAINT app_symbol_file_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT app_symbol_file_name_ck CHECK (length(trim(original_filename)) > 0),
    CONSTRAINT app_symbol_file_updated_ck CHECK (updated_at >= uploaded_at),
    CONSTRAINT app_symbol_file_app_build_uk UNIQUE (app_id, build_id)
);

CREATE INDEX app_symbol_file_app_updated_idx ON app_symbol_file (app_id, updated_at DESC);

CREATE TABLE app_symbol_file_audit (
    audit_id UUID PRIMARY KEY,
    symbol_id UUID NOT NULL REFERENCES app_symbol_file (symbol_id) ON DELETE CASCADE,
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    build_id VARCHAR(128) NOT NULL,
    old_revision INTEGER,
    old_sha256 VARCHAR(64),
    new_revision INTEGER NOT NULL,
    new_sha256 VARCHAR(64) NOT NULL,
    changed_by UUID NOT NULL REFERENCES apm_user (id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT app_symbol_audit_revision_ck CHECK (new_revision > 0)
);

CREATE INDEX app_symbol_file_audit_symbol_idx ON app_symbol_file_audit (symbol_id, changed_at DESC);
