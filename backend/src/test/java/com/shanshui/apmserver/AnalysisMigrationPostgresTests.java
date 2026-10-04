package com.shanshui.apmserver;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 在隔离真实 PostgreSQL 中验证旧数据切换，禁止迁移伪造停止。 */
@Testcontainers(disabledWithoutDocker = true)
class AnalysisMigrationPostgresTests {
    /** 每组测试独立容器，绝不操作开发数据。 */
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 每项测试独立 schema，故失败可安全检查迁移回滚。 */
    private String schema;
    /** 当前隔离 schema 的数据库操作。 */
    private JdbcTemplate jdbc;
    /** 无敏感合成用户。 */
    private UUID userId;
    /** 合成应用。 */
    private UUID appId;
    /** 唯一旧任务。 */
    private UUID taskId;
    /** 唯一旧执行身份。 */
    private UUID credentialId;
    /** 旧 JSON 原始字节，必须原样保留。 */
    private final String evidence = "{\"schemaVersion\":1,\"source\":{\"commitSha\":\"" + "a".repeat(40) + "\"}}";

    /** 建立 V13 的真实空库基线。 */
    @BeforeEach
    void setup() {
        schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate("13");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl()+(POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")+"currentSchema="+schema, POSTGRES.getUsername(), POSTGRES.getPassword()));
        userId = UUID.randomUUID(); appId = UUID.randomUUID(); taskId = UUID.randomUUID(); credentialId = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'migration@example.invalid','迁移测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", userId);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.migration','迁移测试',NULL,?,clock_timestamp(),clock_timestamp())", appId, userId);
        jdbc.update("INSERT INTO analysis_build_revision(app_id,build_id,revision,repository_id,commit_sha,obfuscated,enabled,verification_basis,verified_by) VALUES (?,'probe',1,'performance',?,false,true,'受控测试',?)", appId, "a".repeat(40), userId);
        jdbc.update("INSERT INTO analysis_build_current(app_id,build_id,revision) VALUES (?,'probe',1)", appId);
        jdbc.update("INSERT INTO analysis_worker_credential(credential_id,app_id,repository_id,analysis_type,name,token_sha256,display_prefix,created_by) VALUES (?,?,'performance','ANALYZE','迁移测试',?,'test-only',?)", credentialId, appId, "c".repeat(64), userId);
    }

    /** 固定版本目标；无目标时执行新增迁移。 */
    private void migrate(String target) {
        // Flyway 在专用 schema 内自动创建管理表，不修改已发布脚本。
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).schemas(schema).defaultSchema(schema);
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    /** 插入真实外键约束下的旧任务，状态作为切换门禁。 */
    private void task(String state) {
        jdbc.update("INSERT INTO crash_analysis_task(task_id,app_id,event_id,idempotency_key,created_by,build_id,fingerprint,mapping_revision,state,model_config,evidence_id,evidence,evidence_text,evidence_sha256) VALUES (?,?,'event',?,?,'probe','fingerprint',1,?,'{}'::jsonb,?,?::jsonb,?,?)",
                taskId, appId, UUID.randomUUID(), userId, state, UUID.randomUUID(), evidence, evidence,
                com.shanshui.apmserver.agent.internal.AnalysisEvidence.digest(evidence));
    }

    /** 保存真实旧 Run，结果与停止审计不允许被切换改写。 */
    private UUID run(boolean stopped) {
        UUID id = UUID.randomUUID(); // 隔离分配标识。
        jdbc.update("INSERT INTO crash_analysis_run(run_id,task_id,app_id,credential_id,request_id,attempt,state,lease_generation,lease_sha256,lease_expires_at,deadline_at,stop_confirmed,local_tools_stopped,host_stop_state,stopped_by,stop_basis,result,result_text,result_sha256) VALUES (?,?,?,?,?,1,'SUCCEEDED',1,?,clock_timestamp(),clock_timestamp(),?,true,?,?,?,?::jsonb,?,?)",
                id, taskId, appId, credentialId, UUID.randomUUID(), "d".repeat(64), stopped, stopped ? "CONFIRMED" : "UNKNOWN", stopped ? userId : null,
                stopped ? "真实受控停止审计" : null, evidence, evidence, com.shanshui.apmserver.agent.internal.AnalysisEvidence.digest(evidence));
        return id;
    }

    /** 已停止旧数据升级，历史正文和审计保持原事实。 */
    @Test
    void preservesHistoricalBytesAndAuditAndRemovesRegistration() {
        task("SUCCEEDED");
        UUID runId = run(true); // 已确认的旧分配。
        migrate(null);
        assertEquals(evidence, jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?",String.class,taskId));
        assertEquals(evidence, jdbc.queryForObject("SELECT result_text FROM crash_analysis_run WHERE run_id=?",String.class,runId));
        assertEquals("真实受控停止审计",jdbc.queryForObject("SELECT stop_basis FROM crash_analysis_run WHERE run_id=?",String.class,runId));
        assertNull(jdbc.queryForObject("SELECT snapshot_id FROM crash_analysis_run WHERE run_id=?",UUID.class,runId));
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)",String.class,schema+".analysis_build_revision"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name IN ('crash_analysis_task','analysis_worker_credential') AND column_name IN ('build_id','mapping_revision','model_config','repository_id','analysis_type')",Integer.class,schema));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)",String.class,schema+".app_symbol_file"));
    }

    /** 活动旧任务必须先明确结束，失败迁移不能删除结构。 */
    @Test
    void refusesActiveTaskAndCanMigrateAfterCancellation() {
        task("READY");
        assertThrows(RuntimeException.class, () -> migrate(null));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)",String.class,schema+".analysis_build_revision"));
        jdbc.update("UPDATE crash_analysis_task SET state='CANCELLED' WHERE task_id=?",taskId);
        migrate(null);
    }

    /** 成功报告与材料关闭不能替代实际宿主停止。 */
    @Test
    void refusesUnknownHostStopAndPreservesItsAudit() {
        task("SUCCEEDED");
        UUID runId = run(false); // 没有确认停止的历史分配。
        assertThrows(RuntimeException.class, () -> migrate(null));
        assertFalse(jdbc.queryForObject("SELECT stop_confirmed FROM crash_analysis_run WHERE run_id=?",Boolean.class,runId));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT host_stop_state FROM crash_analysis_run WHERE run_id=?",String.class,runId));
    }
}
