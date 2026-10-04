-- 首期固定仓库标识；真实目录与远端身份由受信本地配置核验。
CREATE TABLE analysis_repository (
    repository_id VARCHAR(64) PRIMARY KEY
);
INSERT INTO analysis_repository (repository_id) VALUES ('performance');

-- 历史版本不可被更正接口覆盖；任务将引用此完整版本键。
CREATE TABLE analysis_build_revision (
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    build_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    repository_id VARCHAR(64) NOT NULL REFERENCES analysis_repository (repository_id),
    commit_sha VARCHAR(64) NOT NULL CHECK (commit_sha ~ '^([a-f0-9]{40}|[a-f0-9]{64})$'),
    obfuscated BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    verification_basis VARCHAR(2000) NOT NULL CHECK (length(trim(verification_basis)) > 0),
    verified_by UUID NOT NULL REFERENCES apm_user (id),
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (app_id, build_id, revision),
    CONSTRAINT analysis_build_id_ck CHECK (build_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
);

-- 当前版本仅保存指针，避免当前值与审计记录出现不一致。
CREATE TABLE analysis_build_current (
    app_id UUID NOT NULL REFERENCES apm_app (app_id) ON DELETE CASCADE,
    build_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (app_id, build_id),
    FOREIGN KEY (app_id, build_id, revision)
        REFERENCES analysis_build_revision (app_id, build_id, revision)
);
CREATE INDEX analysis_build_current_page_idx
    ON analysis_build_current (app_id, updated_at DESC, build_id);
