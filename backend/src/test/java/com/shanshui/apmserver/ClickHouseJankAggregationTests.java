package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.api.*;
import com.shanshui.apmserver.jank.internal.application.*;
import com.shanshui.apmserver.jank.internal.domain.*;
import com.shanshui.apmserver.jank.internal.persistence.*;
import com.shanshui.apmserver.platform.api.*;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实事实表证明统计输入不受页大小裁剪，并记录十万事件资源证据。 */
@Testcontainers
class ClickHouseJankAggregationTests {
    /** 固定版本及合成凭据，避免依赖现有开发数据库。 */
    @Container private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test").withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);
    /** 测试应用与时间窗固定，数据不包含真实设备信息。 */
    private static final UUID APP = TestAppIds.id("aggregation");
    private static final String FROM = "2026-10-01T00:00:00Z", TO = "2026-10-02T00:00:00Z";

    @Test void completeAggregatesBoundedPagesAndBudgets() throws Exception {
        // 使用发布迁移中的真实列、引擎与排序键，不用简化替代表。
        RecordingQueryClient client = client();
        String schema = Files.readString(Path.of("src/main/resources/db/clickhouse/004_application_identity_schema.sql"));
        int start = schema.indexOf("CREATE TABLE apm.apm_jank_event");
        String table = schema.substring(start, schema.indexOf(';', start)).replace("apm.apm_jank_event", "apm_jank_event");
        client.execute(table);
        client.execute("ALTER TABLE apm_jank_event ADD COLUMN process_id String DEFAULT ''");
        insert(client, 120);
        ObjectMapper mapper = new ObjectMapper();
        QueryProperties properties = CrashTestSupport.queryProperties();
        JankQueryService query = service(client, properties, mapper);
        // 60 个 Issue，其中 fp-0 有 61 条，剩余 59 个各一条；有效零值保留。
        for (int limit : List.of(1, 20, 50)) {
            JankQueryCommand command = JankQueryCommand.empty().withLimit(limit);
            assertEquals(120, query.overview(APP, FROM, TO, command).stats().jankEvents());
            assertEquals(59.0, query.overview(APP, FROM, TO, command).stats().exactMessageDuration().p50Ms());
            assertEquals(107.0, query.overview(APP, FROM, TO, command).stats().exactMessageDuration().p90Ms());
            assertEquals(118.0, query.overview(APP, FROM, TO, command).stats().exactMessageDuration().p99Ms());
            assertEquals(120, query.trend(APP, FROM, TO, "hour", command).points().getFirst().stats().jankEvents());
            var first = query.issues(APP, FROM, TO, command);
            assertEquals(61, first.issues().getFirst().eventCount());
            assertEquals(30.0, first.issues().getFirst().exactMessageDuration().p50Ms());
            assertEquals(30.0, first.issues().getFirst().estimatedStackDuration().p50Ms());
            Set<String> issues = new HashSet<>();
            String cursor = null;
            do {
                var page = query.issues(APP, FROM, TO, command.withCursor(cursor));
                page.issues().forEach(issue -> assertTrue(issues.add(issue.fingerprint())));
                cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(60, issues.size());
            Set<String> events = new HashSet<>(); cursor = null;
            do {
                var page = query.events(APP, "fp-0", FROM, TO, command.withCursor(cursor));
                page.events().forEach(event -> assertTrue(events.add(event.eventId())));
                cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(61, events.size());
        }
        // 奇数、偶数、单值与合法零值按 ceil(N*p) 而非近似 quantile 计算。
        assertEquals(0.0, query.issues(APP, FROM, TO, JankQueryCommand.empty().withFingerprint("fp-0"))
                .issues().getFirst().exactMessageDuration().p50Ms() - 30.0);
        assertNull(query.overview(APP, "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", JankQueryCommand.empty()).stats().exactMessageDuration().p50Ms());
        client.execute("SYSTEM FLUSH LOGS");
        String evidence = client.execute("SELECT max(result_rows) AS rows, countIf(position(query,'jank_payload_json')>0 OR position(query,'jank_analysis_json')>0) AS payloads FROM system.query_log WHERE type='QueryFinish' AND position(query,'FROM apm_jank_event FINAL')>0 AND position(query,'system.query_log')=0 FORMAT JSONEachRow");
        assertEquals(0, mapper.readTree(evidence).path("payloads").asInt());
        assertTrue(mapper.readTree(evidence).path("rows").asInt() <= 51);
        QueryProperties tiny = CrashTestSupport.queryProperties(); tiny.setMaxRowsToRead(1);
        JankQueryService rowLimited = service(client,tiny,mapper);
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class, () -> rowLimited.overview(APP,FROM,TO,JankQueryCommand.empty())).getCode());
        tiny = CrashTestSupport.queryProperties(); tiny.setMaxMemoryUsage(1);
        JankQueryService memoryLimited = service(client,tiny,mapper);
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class, () -> memoryLimited.issues(APP,FROM,TO,JankQueryCommand.empty())).getCode());
        assertEquals("QUERY_TIMEOUT", assertThrows(QueryValidationException.class, () -> client.executeQuery("SELECT sleep(1)",new QueryBudget(10,1000000,100000000,100000000,8192))).getCode());
        assertThrows(EventStoreUnavailableException.class, () -> client.executeQuery("SELECT * FROM missing_table",new QueryBudget(2000,5000000,536870912,268435456,8388608)));
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class, () -> client.executeQuery(
                ClickHouseJankQuerySql.overview(query.filter(APP,FROM,TO,JankQueryCommand.empty())),
                new QueryBudget(2000,5000000,1,268435456,8388608))).getCode());
        assertEquals(0.0,query.events(APP,"fp-0",FROM,TO,JankQueryCommand.empty().withLimit(1)).events().getFirst().exactMessageDurationMs());
        // 同一实例扩展到十万事件；保存精确执行计划和 query_log，供知识库引用。
        client.execute("TRUNCATE TABLE apm_jank_event"); insert(client,100000);
        String plan = client.execute("EXPLAIN indexes=1 " + ClickHouseJankQuerySql.issues(query.filter(APP,FROM,TO,JankQueryCommand.empty().withLimit(1)),null).replace(" FORMAT JSONEachRow", ""));
        for (int limit : List.of(1,50)) {
            assertEquals(100000,query.overview(APP,FROM,TO,JankQueryCommand.empty().withLimit(limit)).stats().jankEvents());
            query.issues(APP,FROM,TO,JankQueryCommand.empty().withLimit(limit));
            query.events(APP,"fp-0",FROM,TO,JankQueryCommand.empty().withLimit(limit));
        }
        client.execute("SYSTEM FLUSH LOGS");
        String log = client.execute("SELECT query,query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes FROM system.query_log WHERE type='QueryFinish' AND position(query,'FROM apm_jank_event FINAL')>0 AND position(query,'system.query_log')=0 ORDER BY event_time_microseconds DESC LIMIT 6 FORMAT JSONEachRow");
        Files.createDirectories(Path.of("build/query-evidence"));
        Files.writeString(Path.of("build/query-evidence/jank.txt"),"ClickHouse 26.7.3.19\n"+client.execute("SELECT version(),getSetting('max_threads') FORMAT JSONEachRow")+"\n"+plan+"\n"+log+"\n"+client.last(6));
    }
    /** 数据库生成合成事实，避免 Java 接收批量原始正文。 */
    private void insert(ClickHouseHttpClient client,int count) {
        client.execute("INSERT INTO apm_jank_event (app_id,package_name,event_id,event_time,received_time,schema_version,session_id,anonymous_device_id,app_version,version_code,build_id,channel,environment,os_version,device_model,network_type,scene,algorithm_version,message_duration_ns,threshold_ns,sampling_interval_ns,estimated_duration_ns,estimated_unattributed_duration_ns,covered_duration_ns,uncovered_duration_ns,expected_sample_count,parsed_sample_count,missing_sample_count,fingerprint,fingerprint_version,jank_payload_json,jank_analysis_json) SELECT '"+APP+"','synthetic',concat('event-',leftPad(toString(number),6,'0')),toDateTime64('2026-10-01 00:00:00',3,'UTC'),toDateTime64('2026-10-01 01:00:00',3,'UTC'),2,concat('s-',toString(number%7)),concat('d-',toString(number%3)),'1.0',1,'build','official','production','16','Pixel','wifi','scene','v1',number*1000000,0,0,number*1000000,0,0,0,0,0,0,concat('fp-',toString(if(number<61,0,1+(number-61)%59))),'v1','deliberately invalid JSON payload','deliberately invalid JSON analysis' FROM numbers("+count+")");
    }
    /** 适配真实查询依赖，摘要不得触碰无效详情 JSON。 */
    private JankQueryService service(ClickHouseHttpClient client, QueryProperties properties,ObjectMapper mapper) {
        return new JankQueryService(new ClickHouseJankAggregationRepository(client,new ClickHouseJankEventRepository(client,mapper,new MicrometerTelemetryMetrics(new SimpleMeterRegistry())),properties,mapper),properties);
    }
    /** 独立容器连接配置。 */
    private RecordingQueryClient client() {
        ClickHouseProperties properties = new ClickHouseProperties(); properties.setUrl("http://"+DATABASE.getHost()+":"+DATABASE.getMappedPort(8123)); properties.setDatabase("default"); properties.setUsername("apm_test"); properties.setPassword("apm_test_password"); return new RecordingQueryClient(properties);
    }
}
