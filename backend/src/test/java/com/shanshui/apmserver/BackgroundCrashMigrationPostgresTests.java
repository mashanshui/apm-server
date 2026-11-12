package com.shanshui.apmserver;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在隔离 PostgreSQL 核验新模型迁移、来源保留和旧任务原字节。 */
@Testcontainers(disabledWithoutDocker = true)
class BackgroundCrashMigrationPostgresTests {
    /** 专用临时数据库，不连接开发实例。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 迁移只新增结构，软删除后活动唯一约束允许新身份上传。 */
    @Test
    void migratesWithoutUpgradingLegacyTasksAndPreservesDeletedSymbolAudit() {
        // 每次测试使用独立 schema，包含真实已发布迁移历史。
        String schema = "background_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("14").load().migrate();
        // 连接只限定到该 schema。
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        // 合成身份和旧任务，证据必须原样保留且不自动标为当前契约。
        UUID user = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID oldSymbol = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'background@example.invalid','迁移测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.background','迁移测试',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        jdbc.update("INSERT INTO crash_analysis_task(task_id,app_id,event_id,idempotency_key,created_by,fingerprint,state,evidence,evidence_text) VALUES (?,?,'old',?,?,'old','SUCCEEDED','{\"schemaVersion\":2}'::jsonb,'{\"schemaVersion\":2}')", task, app, UUID.randomUUID(), user);
        symbol(jdbc, oldSymbol, app, user);
        // 本测试限定 V15–V16；重复执行不会再次修改数据或结构。
        Flyway flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("16").load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertNull(jdbc.queryForObject("SELECT contract_version FROM crash_analysis_task WHERE task_id=?", Integer.class, task));
        assertEquals("{\"schemaVersion\":2}", jdbc.queryForObject("SELECT evidence_text FROM crash_analysis_task WHERE task_id=?", String.class, task));
        assertThrows(RuntimeException.class, () -> symbol(jdbc, UUID.randomUUID(), app, user));
        jdbc.update("UPDATE app_symbol_file SET deleted_at=clock_timestamp() WHERE symbol_id=?", oldSymbol);
        jdbc.update("INSERT INTO app_symbol_file_audit(audit_id,symbol_id,app_id,build_id,old_revision,old_sha256,new_revision,new_sha256,changed_by,changed_at,action) VALUES (?,?,?,'build',1,?,1,?,?,clock_timestamp(),'DELETE')", UUID.randomUUID(), oldSymbol, app, "a".repeat(64), "a".repeat(64), user);
        // 删除后重传产生不同 symbolId、revision=1，旧来源和删除审计都存在。
        UUID newSymbol = UUID.randomUUID();
        symbol(jdbc, newSymbol, app, user);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file WHERE app_id=?", Integer.class, app));
        assertEquals(newSymbol, jdbc.queryForObject("SELECT symbol_id FROM app_symbol_file WHERE app_id=? AND deleted_at IS NULL", UUID.class, app));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit WHERE symbol_id=? AND action='DELETE'", Integer.class, oldSymbol));
        jdbc.update("INSERT INTO crash_symbolication_job(app_id,build_id) VALUES (?,'build')", app);
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO crash_symbolication_job(app_id,build_id) VALUES (?,'build')", app));
        jdbc.update("INSERT INTO crash_symbolication_publication(app_id,event_id,build_id,event_time,expires_at,state_version,symbol_id,symbol_revision,symbol_sha256,result_sha256,pending_payload) VALUES (?,'event','build',clock_timestamp(),clock_timestamp()+interval '30 days',nextval('crash_symbolication_state_version'),?,1,?,?,'{}')", app, oldSymbol, "a".repeat(64), "b".repeat(64));
        // 新单份上限严格按 UTF-8 字节生效，超限仍被数据库拒绝。
        jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=repeat('x',4194304)");
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=repeat('x',4194305)"));
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO crash_symbolication_publication SELECT * FROM crash_symbolication_publication"));
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=NULL"));
        jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=NULL,published_at=clock_timestamp()");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_publication WHERE symbol_id=?", Integer.class, oldSymbol));
        // 新契约只能显式设置为 3，未知版本不合法。
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE crash_analysis_task SET contract_version=2 WHERE task_id=?", task));
    }

    /** 插入同一构建的合成文件记录，检查真实局部唯一约束。 */
    private void symbol(JdbcTemplate jdbc, UUID id, UUID app, UUID user) {
        jdbc.update("INSERT INTO app_symbol_file(symbol_id,app_id,build_id,revision,storage_key,original_filename,size_bytes,sha256,uploaded_by,uploaded_at,updated_at) VALUES (?,?,'build',1,?,'mapping.txt',10,?,?,clock_timestamp(),clock_timestamp())",
                id, app, id + ".mapping", "a".repeat(64), user);
    }
}
