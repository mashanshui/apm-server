DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project LIMIT 1)
        OR EXISTS (SELECT 1 FROM project_member LIMIT 1)
        OR EXISTS (SELECT 1 FROM project_ingest_credential LIMIT 1) THEN
        RAISE EXCEPTION 'application_identity migration requires empty legacy project tables; clear test business data before upgrading';
    END IF;
END
$$;

DROP TABLE project_ingest_credential;
DROP TABLE project_member;
DROP TABLE project;

CREATE TABLE apm_app (
    app_id UUID PRIMARY KEY,
    package_name VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_by UUID NOT NULL REFERENCES apm_user (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT apm_app_package_name_uk UNIQUE (package_name),
    CONSTRAINT apm_app_package_name_ck CHECK (package_name ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    CONSTRAINT apm_app_name_not_blank_ck CHECK (length(trim(name)) > 0),
    CONSTRAINT apm_app_created_updated_ck CHECK (updated_at >= created_at)
);

CREATE INDEX apm_app_updated_at_idx ON apm_app (updated_at DESC);

CREATE TABLE app_member (
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES apm_user (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (app_id, user_id),
    CONSTRAINT app_member_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE INDEX app_member_user_idx ON app_member (user_id, app_id);

CREATE TABLE app_ingest_credential (
    app_id UUID PRIMARY KEY REFERENCES apm_app (app_id) ON DELETE CASCADE,
    key_digest BYTEA NOT NULL,
    key_ciphertext BYTEA NOT NULL,
    key_nonce BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT app_ingest_credential_key_digest_uk UNIQUE (key_digest),
    CONSTRAINT app_ingest_credential_key_digest_length_ck
        CHECK (octet_length(key_digest) = 32),
    CONSTRAINT app_ingest_credential_key_ciphertext_length_ck
        CHECK (octet_length(key_ciphertext) >= 16),
    CONSTRAINT app_ingest_credential_key_nonce_length_ck
        CHECK (octet_length(key_nonce) = 12)
);
