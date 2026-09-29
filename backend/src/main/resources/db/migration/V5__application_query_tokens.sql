CREATE TABLE app_query_token (
    id UUID PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    token_digest BYTEA NOT NULL,
    display_prefix VARCHAR(24) NOT NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'apm:read',
    created_by UUID NOT NULL REFERENCES apm_user (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT app_query_token_digest_uk UNIQUE (token_digest),
    CONSTRAINT app_query_token_digest_length_ck CHECK (octet_length(token_digest) = 32),
    CONSTRAINT app_query_token_name_ck CHECK (length(trim(name)) BETWEEN 1 AND 100),
    CONSTRAINT app_query_token_scope_ck CHECK (scope = 'apm:read'),
    CONSTRAINT app_query_token_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT app_query_token_revoked_ck CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX app_query_token_app_created_idx ON app_query_token (app_id, created_at DESC, id DESC);
