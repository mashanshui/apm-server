DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project LIMIT 1) THEN
        RAISE EXCEPTION 'project_ingest_credential migration requires an empty project table; clear test projects before upgrading';
    END IF;
END
$$;

CREATE TABLE project_ingest_credential (
    project_id VARCHAR(40) PRIMARY KEY,
    app_package_name VARCHAR(255) NOT NULL,
    key_digest BYTEA NOT NULL,
    key_ciphertext BYTEA NOT NULL,
    key_nonce BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT project_ingest_credential_app_package_ck
        CHECK (app_package_name ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'),
    CONSTRAINT project_ingest_credential_key_digest_length_ck
        CHECK (octet_length(key_digest) = 32),
    CONSTRAINT project_ingest_credential_key_ciphertext_length_ck
        CHECK (octet_length(key_ciphertext) >= 16),
    CONSTRAINT project_ingest_credential_key_nonce_length_ck
        CHECK (octet_length(key_nonce) = 12),
    CONSTRAINT project_ingest_credential_key_digest_uk UNIQUE (key_digest),
    CONSTRAINT project_ingest_credential_project_fk
        FOREIGN KEY (project_id) REFERENCES project (project_id) ON DELETE CASCADE
);
