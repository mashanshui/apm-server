package com.shanshui.apmserver;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实升级门禁、历史证据保留及十万登记分页查询计划。 */
@Testcontainers
class VersionedSymbolMigrationTests {
    /** 只承载合成数据的独立 PostgreSQL。 */
    @Container private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 每个用例的独立 schema。 */
    private record Fixture(/** 迁移入口。 */ Flyway flyway, /** 参数化数据库。 */ JdbcTemplate jdbc,
                           /** 合成应用。 */ UUID app, /** 合成上传用户。 */ UUID user) {}
    /** 从指定历史版本建立独立样本。 */
    private Fixture fixture(String version) {
        String schema = "symbols_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema).defaultSchema(schema).target(version).load().migrate();
        var flyway = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl() + (DATABASE.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(source);
        UUID user = UUID.randomUUID(), app = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'migration@example.invalid','迁移验证','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.versioned','迁移验证',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        return new Fixture(flyway, jdbc, app, user);
    }
    /** 有效租约或未确认字节都阻止切换，排空后不重写旧文件、来源或序列。 */
    @Test
    void refusesInflightWorkAndPreservesRetiredJobsAndPublishedSources() {
        var fixture = fixture("17");
        var jdbc = fixture.jdbc();
        UUID symbol = UUID.randomUUID();
        jdbc.update("INSERT INTO app_symbol_file(symbol_id,app_id,build_id,revision,storage_key,original_filename,size_bytes,sha256,uploaded_by,uploaded_at,updated_at) VALUES (?,?,'legacy',3,'legacy.mapping','mapping.txt',10,?,?,clock_timestamp(),clock_timestamp())", symbol, fixture.app(), "a".repeat(64), fixture.user());
        jdbc.update("INSERT INTO app_symbol_file_audit(audit_id,symbol_id,app_id,build_id,new_revision,new_sha256,changed_by,changed_at) VALUES (?,?,?,'legacy',3,?,?,clock_timestamp())", UUID.randomUUID(), symbol, fixture.app(), "a".repeat(64), fixture.user());
        jdbc.update("INSERT INTO crash_symbolication_job(app_id,build_id,state,lease_owner,lease_expires_at,succeeded_count) VALUES (?,'legacy','running',?,clock_timestamp()+interval '1 minute',7)", fixture.app(), UUID.randomUUID());
        assertThrows(RuntimeException.class, () -> fixture.flyway().migrate());
        jdbc.update("UPDATE crash_symbolication_job SET lease_owner=NULL,lease_expires_at=NULL,state='idle'");
        long version = jdbc.queryForObject("SELECT nextval('crash_symbolication_state_version')", Long.class);
        jdbc.update("INSERT INTO crash_symbolication_publication(app_id,event_id,build_id,event_time,expires_at,state_version,symbol_id,symbol_revision,symbol_sha256,result_sha256,pending_payload) VALUES (?,'fixed','legacy',clock_timestamp()-interval '1 day',clock_timestamp()+interval '29 days',?,?,3,?,?,'original-result-bytes')", fixture.app(), version, symbol, "a".repeat(64), "b".repeat(64));
        assertThrows(RuntimeException.class, () -> fixture.flyway().migrate());
        assertEquals("original-result-bytes", jdbc.queryForObject("SELECT pending_payload FROM crash_symbolication_publication", String.class));
        jdbc.update("UPDATE crash_symbolication_publication SET pending_payload=NULL,published_at=clock_timestamp()");
        fixture.flyway().migrate();
        assertEquals("waiting_mapping", jdbc.queryForObject("SELECT state FROM crash_symbolication_job WHERE app_version IS NULL", String.class));
        assertEquals("legacy_unversioned", jdbc.queryForObject("SELECT error_category FROM crash_symbolication_job WHERE app_version IS NULL", String.class));
        assertEquals(7, jdbc.queryForObject("SELECT succeeded_count FROM crash_symbolication_job WHERE app_version IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crash_symbolication_job WHERE app_version IS NOT NULL", Integer.class));
        assertEquals(version, jdbc.queryForObject("SELECT last_value FROM crash_symbolication_state_version", Long.class));
        assertEquals(symbol, jdbc.queryForObject("SELECT symbol_id FROM crash_symbolication_publication", UUID.class));
        assertEquals("b".repeat(64), jdbc.queryForObject("SELECT result_sha256 FROM crash_symbolication_publication", String.class));
        assertNull(jdbc.queryForObject("SELECT app_version FROM crash_symbolication_publication", String.class));
        assertEquals("legacy.mapping", jdbc.queryForObject("SELECT storage_key FROM app_symbol_file", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app_symbol_file_audit", Integer.class));
    }
    /** 混合十万登记按类型和版本在数据库内分页，只返回五十一项元数据。 */
    @Test
    void usesVersionPageIndexForOneHundredThousandMixedRegistrations() throws Exception {
        var fixture = fixture("19");
        var jdbc = fixture.jdbc();
        jdbc.update("""
                INSERT INTO app_symbol_file(symbol_id,app_id,symbol_type,app_version,build_id,native_abi,elf_build_id,
                  has_function_symbols,has_debug_info,has_debug_line,revision,storage_key,original_filename,size_bytes,sha256,uploaded_by,uploaded_at,updated_at)
                SELECT gen_random_uuid(),?,CASE WHEN n%2=0 THEN 'MAPPING' ELSE 'NATIVE_SO' END,'v-'||(n%100),
                  CASE WHEN n%2=0 THEN 'b-'||n END,CASE WHEN n%2=1 THEN 'arm64-v8a' END,
                  CASE WHEN n%2=1 THEN lpad(to_hex(n),40,'0') END,
                  CASE WHEN n%2=1 THEN true END,CASE WHEN n%2=1 THEN false END,CASE WHEN n%2=1 THEN false END,
                  1,gen_random_uuid()||'.mapping','fixture',10,repeat('a',64),?,clock_timestamp(),clock_timestamp()
                FROM generate_series(1,100000) AS n
                """, fixture.app(), fixture.user());
        jdbc.execute("ANALYZE app_symbol_file");
        String sql = "SELECT symbol_id,app_version,build_id FROM app_symbol_file WHERE app_id=? AND symbol_type='MAPPING' AND app_version='v-50' AND deleted_at IS NULL AND build_id IS NOT NULL ORDER BY updated_at DESC,symbol_id DESC LIMIT 51";
        String plan = jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + sql, String.class, fixture.app());
        assertNotNull(plan);
        assertTrue(plan.contains("app_symbol_file_version_page_idx"), plan);
        assertTrue(plan.contains("Index Scan"), plan);
        assertEquals(51, jdbc.queryForList(sql, fixture.app()).size());
        Files.createDirectories(Path.of("build/query-evidence"));
        Files.writeString(Path.of("build/query-evidence/versioned-symbol-list.json"), plan);
    }
}
