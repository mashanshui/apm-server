package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.application.*;
import com.shanshui.apmserver.memory.internal.domain.*;
import com.shanshui.apmserver.memory.internal.persistence.*;
import com.shanshui.apmserver.memory.api.*;
import com.shanshui.apmserver.platform.api.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实报告事实验证路径聚合、完整分母与万份多路径报告预算。 */
@Testcontainers
class ClickHouseMemoryLeakAggregationTests {
    /** 固定数据库版本和合成测试账户。 */
    @Container private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER","apm_test").withEnv("CLICKHOUSE_PASSWORD","apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT","1").withExposedPorts(8123);
    /** 固定应用和纳秒时间边界。 */
    private static final UUID APP = TestAppIds.id("memory-aggregation");
    private static final Instant FROM=Instant.parse("2026-10-01T00:00:00Z"), TO=FROM.plusSeconds(86400);
    /** 路径序列化与返回字段核对。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void comparesFullReferenceAndBoundedPages() throws Exception {
        RecordingQueryClient client=client();
        client.execute(Files.readString(Path.of("src/main/resources/db/clickhouse/006_memory_leak_reports.sql")).replace("apm.apm_memory_report","apm_memory_report"));
        client.execute("ALTER TABLE apm_memory_report ADD COLUMN process_id String DEFAULT ''");
        InMemoryMemoryLeakReportRepository reference=new InMemoryMemoryLeakReportRepository(new StorageProperties());
        // 同事件同 signature 重复、跨设备、跨版本、同时间及空报告都与参考逐字段比较。
        for (int index=1;index<=3;index++) {
            List<MemoryLeakPath> paths=index==1?List.of(path("A","first"),path("A","duplicate"),path("B","B")):List.of(path("A","latest-"+index));
            MemoryLeakReport report=report(APP,index,"v"+index,index<3?"d1":"d2",paths);
            appendRaw(client,report); reference.append(report);
            if(index==1) appendRaw(client,report);
        }
        appendRaw(client,report(TestAppIds.id("other"),4,"other","other",List.of(path("A","other"))));
        appendRaw(client,report(APP,5,"empty","d3",List.of()));
        for(int index=10;index<140;index++) {
            MemoryLeakReport report=report(APP,index,"v"+index,"d"+(index%4),List.of(path("S"+index,"full-chain")));
            appendRaw(client,report); reference.append(report);
        }
        MemoryLeakReport special=report(APP,200,"special","d4",List.of(path("A%_\\'[]","literal%_\\'[]")));
        appendRaw(client,special); reference.append(special);
        QueryProperties properties=new QueryProperties();
        MemoryLeakQueryService query=new MemoryLeakQueryService(new ClickHouseMemoryLeakReportRepository(client,mapper,properties));
        MemoryLeakQueryService expected=new MemoryLeakQueryService(reference);
        for(String sort:List.of("occurrences","affectedDevices","lastOccurredAt")) for(String order:List.of("asc","desc"))
            for(int size:List.of(1,20,100)) for(int page:List.of(1,2,200,Integer.MAX_VALUE)) {
                var actual=query.issues(filter(null,null),page,size,sort,order);
                var referencePage=expected.issues(filter(null,null),page,size,sort,order);
                assertEquals(referencePage.items(),actual.items()); assertEquals(referencePage.total(),actual.total());
                assertEquals(referencePage.totalOccurrences(),actual.totalOccurrences()); assertEquals(referencePage.totalAffectedDevices(),actual.totalAffectedDevices());
            }
        var a=query.issues(filter("A",null),1,1,"occurrences","desc");
        assertEquals(3,a.totalOccurrences()); assertEquals(3,a.items().getFirst().versions().size());
        assertEquals(expected.issues(filter("A",null),1,1,"occurrences","desc").items(),a.items());
        for(String keyword:List.of("%","_","\\","'","[]","literal","referenceType")) {
            assertEquals(expected.issues(filter(null,keyword),1,100,"occurrences","desc").items(),query.issues(filter(null,keyword),1,100,"occurrences","desc").items());
        }
        assertEquals(expected.trend(filter(null,null),"hour").points(),query.trend(filter(null,null),"hour").points());
        assertEquals(0,query.issues(new MemoryLeakQueryFilter(APP,TO,TO.plusSeconds(3600),null,null,null,null,null,null,null,null,null,null),1,20,"occurrences","desc").total());
        QueryProperties tiny=new QueryProperties(); tiny.setMaxResponseBytes(20);
        MemoryLeakQueryService responseLimited=new MemoryLeakQueryService(new ClickHouseMemoryLeakReportRepository(client,mapper,tiny));
        assertEquals("QUERY_RESOURCE_LIMIT",assertThrows(QueryValidationException.class,()->responseLimited.issues(filter(null,null),1,20,"occurrences","desc")).getCode());
        tiny=new QueryProperties(); tiny.setMaxRowsToRead(1);
        MemoryLeakQueryService scanLimited=new MemoryLeakQueryService(new ClickHouseMemoryLeakReportRepository(client,mapper,tiny));
        assertEquals("QUERY_RESOURCE_LIMIT",assertThrows(QueryValidationException.class,()->scanLimited.trend(filter(null,null),"hour")).getCode());
        client.execute("SYSTEM FLUSH LOGS");
        assertEquals(0,mapper.readTree(client.execute("SELECT countIf(position(query,'report_json')>0) AS bodies FROM system.query_log WHERE type='QueryFinish' AND position(query,'expanded AS')>0 AND position(query,'system.query_log')=0 FORMAT JSONEachRow")).path("bodies").asInt());
        // 至少一万份各三条路径的报告：输入由数据库生成，Java 仅接收一个问题页。
        client.execute("TRUNCATE TABLE apm_memory_report");
        String paths=mapper.writeValueAsString(List.of(path("A","a"),path("B","b"),path("C","c")));
        client.execute("INSERT INTO apm_memory_report (app_id,event_id,event_time,received_time,package_name,app_version,version_code,anonymous_device_id,process_name,session_id,build_id,environment,channel,device_model,scene,manufacturer,sdk_int,dump_reason,report_json,gc_paths_json,payload_hash,attachment_bytes) SELECT '"+APP+"',toUUID(concat('00000000-0000-4000-8000-',leftPad(toString(number+1),12,'0'))),toDateTime64('2026-10-01 00:00:00',3,'UTC'),toDateTime64('2026-10-01 01:00:00',3,'UTC'),'synthetic',concat('v',toString(number%150)),1,concat('d',toString(number%100)),'p','','','production','official','Pixel','s','m',30,'test','invalid report body','"+escape(paths)+"',repeat('0',64),0 FROM numbers(10000)");
        String plan=client.execute("EXPLAIN indexes=1 "+ClickHouseMemoryLeakQuerySql.issues(filter(null,null),1,1,"occurrences","desc").replace(" FORMAT JSONEachRow",""));
        for(int size:List.of(1,100)) {
            var page=query.issues(filter(null,null),1,size,"occurrences","desc"); assertEquals(30000,page.totalOccurrences()); assertEquals(150,page.items().getFirst().versions().size());
        }
        assertEquals(30000,query.trend(filter(null,null),"hour").points().getFirst().occurrenceCount());
        client.execute("SYSTEM FLUSH LOGS");
        String log=client.execute("SELECT query,query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes FROM system.query_log WHERE type='QueryFinish' AND position(query,'expanded AS')>0 AND position(query,'system.query_log')=0 ORDER BY event_time_microseconds DESC LIMIT 3 FORMAT JSONEachRow");
        Files.createDirectories(Path.of("build/query-evidence")); Files.writeString(Path.of("build/query-evidence/memory.txt"),"ClickHouse 26.7.3.19\n"+plan+"\n"+log+"\n"+client.last(3));
        QueryProperties shortTime=new QueryProperties(); shortTime.setDefaultTimeoutMs(1);
        MemoryLeakQueryService timeLimited=new MemoryLeakQueryService(new ClickHouseMemoryLeakReportRepository(client,mapper,shortTime));
        assertEquals("QUERY_TIMEOUT",assertThrows(QueryValidationException.class,()->timeLimited.issues(filter(null,null),1,20,"occurrences","desc")).getCode());
        client.execute("DROP TABLE apm_memory_report");
        assertThrows(EventStoreUnavailableException.class,()->query.issues(filter(null,null),1,20,"occurrences","desc"));
    }
    /** 构造一条完整引用链，字段全部为合成值。 */
    private MemoryLeakPath path(String signature,String reference) { return new MemoryLeakPath(signature,"root","reason",1,List.of(new MemoryLeakPathNode("root","static","Root"),new MemoryLeakPathNode(reference,"instance","Class")),""); }
    /** 同一时间的最新路径以事件 UUID 升序选择。 */
    private MemoryLeakReport report(UUID app,int index,String version,String device,List<MemoryLeakPath> paths) {
        return new MemoryLeakReport(app,UUID.fromString("00000000-0000-4000-8000-"+String.format("%012d",index)),FROM,FROM.plusSeconds(index),"synthetic",version,1,device,null,"p",null,null,"production","official","Pixel","s","m",30,"test",mapper.readTree("{}"),paths,"0".repeat(64),null,null,0);
    }
    /** 直接写事实列并置无效 report_json，证明聚合不读取正文。 */
    private void appendRaw(ClickHouseHttpClient client,MemoryLeakReport report) {
        client.execute("INSERT INTO apm_memory_report (app_id,event_id,event_time,received_time,package_name,app_version,version_code,anonymous_device_id,process_name,session_id,build_id,environment,channel,device_model,scene,manufacturer,sdk_int,dump_reason,report_json,gc_paths_json,payload_hash,attachment_bytes) VALUES ('"+report.appId()+"','"+report.eventId()+"','2026-10-01 00:00:00','2026-10-01 01:00:00','synthetic','"+report.appVersion()+"',1,'"+report.anonymousDeviceId()+"','p','','','production','official','Pixel','s','m',30,'test','invalid report','"+escape(mapper.writeValueAsString(report.paths()))+"',repeat('0',64),0)");
    }
    /** 固定范围及路径条件。 */
    private MemoryLeakQueryFilter filter(String signature,String keyword) { return new MemoryLeakQueryFilter(APP,FROM,TO,null,null,null,null,null,null,null,null,signature,keyword); }
    /** 仅用于测试 SQL 数据字面量。 */
    private String escape(String value) { return value.replace("\\","\\\\").replace("'","\\'"); }
    /** 独立测试数据库。 */
    private RecordingQueryClient client() {
        ClickHouseProperties properties=new ClickHouseProperties(); properties.setUrl("http://"+DATABASE.getHost()+":"+DATABASE.getMappedPort(8123)); properties.setDatabase("default"); properties.setUsername("apm_test"); properties.setPassword("apm_test_password"); return new RecordingQueryClient(properties);
    }
}
