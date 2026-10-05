package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.api.MemoryLeakIssueItem;
import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakQueryService;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakReportParser;
import com.shanshui.apmserver.memory.internal.config.MemoryLeakReportProperties;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.StorageProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 SDK 报告边界、signature 去重和分页前统计分母。 */
class MemoryLeakReportTests {
    private static final UUID APP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryLeakReportConfiguration config = new MemoryLeakReportProperties();
    private final MemoryLeakReportParser parser = new MemoryLeakReportParser(objectMapper, config);

    @Test
    void parsesStringCountersAndDoesNotJoinIndependentArrays() {
        var report = parser.parse(APP_ID, objectMapper.readTree(metadata("event-a", "device-a")),
                objectMapper.readTree(reportJson("[\"A\"]",
                        "[{\"className\":\"A\",\"instanceCount\":\"2\"}]",
                        "[{\"className\":\"A\",\"objectId\":\"obj-1\",\"size\":\"1024\"}]")),
                Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(report.paths()).hasSize(1);
        assertThat(report.report().get("classInfos")).hasSize(1);
        assertThat(report.report().get("leakObjects")).hasSize(1);
        assertThat(report.report().get("leakObjects").get(0).get("objectId").asText()).isEqualTo("obj-1");
        assertThat(report.processId()).isEqualTo("11111111-1111-4111-8111-111111111111");
    }

    @Test
    void rejectsInvalidStructuredArrayElementsAndUnknownEnvelopeFields() {
        assertThatThrownBy(() -> parser.parse(APP_ID, objectMapper.readTree(metadata("bad", "device")),
                objectMapper.readTree(reportJson("[]",
                        "[{\"className\":\"A\",\"instanceCount\":\"not-a-number\"}]", "[]")), Instant.now()))
                .isInstanceOf(MemoryLeakReportValidationException.class);
        String unknown = metadata("unknown", "device").replace("\"processName\":\"p\"", "\"extra\":true,\"processName\":\"p\"");
        assertThatThrownBy(() -> parser.parse(APP_ID, objectMapper.readTree(unknown),
                objectMapper.readTree(reportJson("[]", "[]", "[]")), Instant.now()))
                .isInstanceOf(MemoryLeakReportValidationException.class);
    }

    @Test
    void aggregatesEachEventAndSignatureOnceAndKeepsPageDenominators() {
        StorageProperties storage = new StorageProperties();
        InMemoryMemoryLeakReportRepository repository = new InMemoryMemoryLeakReportRepository(storage);
        repository.append(parser.parse(APP_ID, objectMapper.readTree(metadata("e1", "d1")),
                objectMapper.readTree(reportJson("[\"A\",\"A\",\"B\"]", "[]", "[]")), Instant.EPOCH));
        repository.append(parser.parse(APP_ID, objectMapper.readTree(metadata("e2", "d1")),
                objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]")), Instant.EPOCH.plusSeconds(60)));
        repository.append(parser.parse(APP_ID, objectMapper.readTree(metadata("e3", "d2")),
                objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]")), Instant.EPOCH.plusSeconds(120)));
        MemoryLeakQueryFilter filter = new MemoryLeakQueryFilter(APP_ID, Instant.EPOCH.minusSeconds(1), Instant.EPOCH.plusSeconds(600),
                null, null, null, null, null, null, null, null, null, null);
        MemoryLeakIssuesResponse response = new MemoryLeakQueryService(repository).issues(filter, 1, 1, "occurrences", "desc");
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.totalOccurrences()).isEqualTo(4);
        assertThat(response.totalAffectedDevices()).isEqualTo(2);
        MemoryLeakIssueItem top = response.items().get(0);
        assertThat(top.signature()).isEqualTo("A");
        assertThat(top.occurrences()).isEqualTo(3);
        assertThat(top.affectedDevices()).isEqualTo(2);
        assertThat(top.occurrenceRatio()).isEqualTo(0.75D);
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void serializesConcurrentRetriesOfOneEvent() throws Exception {
        StorageProperties storage = new StorageProperties();
        InMemoryMemoryLeakReportRepository repository = new InMemoryMemoryLeakReportRepository(storage);
        var service = new com.shanshui.apmserver.memory.internal.application.MemoryLeakReportIngestService(
                parser, repository,
                new com.shanshui.apmserver.memory.internal.application.MemoryLeakArtifactStore(config));
        var metadata = objectMapper.readTree(metadata("concurrent", "device-a"));
        var report = objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]"));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await(2, TimeUnit.SECONDS);
                    return service.ingest(APP_ID, "com.example.app", metadata, report, null).status();
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (var future : futures) statuses.add(future.get(3, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder("accepted", "duplicate", "duplicate", "duplicate",
                    "duplicate", "duplicate", "duplicate", "duplicate");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void treatsProcessIdentityAsPartOfTheExistingEventConflictContent() {
        StorageProperties storage = new StorageProperties();
        InMemoryMemoryLeakReportRepository repository = new InMemoryMemoryLeakReportRepository(storage);
        var service = new com.shanshui.apmserver.memory.internal.application.MemoryLeakReportIngestService(
                parser, repository,
                new com.shanshui.apmserver.memory.internal.application.MemoryLeakArtifactStore(config));
        var report = objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]"));
        String original = metadata("same-event", "device-a");
        String changed = original.replace("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222");

        service.ingest(APP_ID, "com.example.app", objectMapper.readTree(original), report, null);

        assertThatThrownBy(() -> service.ingest(APP_ID, "com.example.app",
                objectMapper.readTree(changed), report, null))
                .isInstanceOf(com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException.class);
    }

    @Test
    void createsUtcEmptyBucketsAndUsesLiteralKeywordMatching() {
        StorageProperties storage = new StorageProperties();
        InMemoryMemoryLeakReportRepository repository = new InMemoryMemoryLeakReportRepository(storage);
        String first = metadata("trend-1", "device-a", 1000);
        String third = metadata("trend-3", "device-a", 601000);
        repository.append(parser.parse(APP_ID, objectMapper.readTree(first),
                objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]")), Instant.EPOCH));
        repository.append(parser.parse(APP_ID, objectMapper.readTree(third),
                objectMapper.readTree(reportJson("[\"A\"]", "[]", "[]")), Instant.EPOCH));
        MemoryLeakQueryFilter filter = new MemoryLeakQueryFilter(APP_ID, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(900), null, null, null, null, null, null, null, null, null, "A[0]");
        var literal = new MemoryLeakQueryService(repository).issues(filter, 1, 20, "occurrences", "desc");
        assertThat(literal.total()).isZero();

        MemoryLeakQueryFilter all = new MemoryLeakQueryFilter(APP_ID, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(900), null, null, null, null, null, null, null, null, null, null);
        var trend = new MemoryLeakQueryService(repository).trend(all, "5m");
        assertThat(trend.points()).hasSize(3);
        assertThat(trend.points().get(0).occurrenceCount()).isEqualTo(1);
        assertThat(trend.points().get(1).occurrenceCount()).isZero();
        assertThat(trend.points().get(2).occurrenceCount()).isEqualTo(1);
    }

    /** 大页码不得发生 int 乘法溢出，桶边界在访问存储前拒绝。 */
    @Test void rejects2001BucketsBeforeRepositoryAndAllows2000() {
        var repository = org.mockito.Mockito.mock(com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository.class);
        var service = new MemoryLeakQueryService(repository);
        var accepted = new MemoryLeakQueryFilter(APP_ID,Instant.EPOCH,Instant.EPOCH.plusSeconds(2000*300L),null,null,null,null,null,null,null,null,null,null);
        org.mockito.Mockito.when(repository.trend(accepted,300)).thenReturn(List.of());
        assertThat(service.trend(accepted,"5m").points()).hasSize(2000);
        org.mockito.Mockito.clearInvocations(repository);
        var excessive = new MemoryLeakQueryFilter(APP_ID,Instant.EPOCH,accepted.to().plusNanos(1),null,null,null,null,null,null,null,null,null,null);
        assertThatThrownBy(() -> service.trend(excessive,"5m")).isInstanceOf(MemoryLeakReportValidationException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);
        var memory = new InMemoryMemoryLeakReportRepository(new StorageProperties());
        assertThat(new MemoryLeakQueryService(memory).issues(accepted,Integer.MAX_VALUE,100,"occurrences","desc").items()).isEmpty();
        var facade = new com.shanshui.apmserver.memory.internal.application.MemoryLeakQueryFacade(service);
        assertThatThrownBy(() -> facade.issues(APP_ID,java.util.Map.of("page","2147483648")))
                .isInstanceOf(com.shanshui.apmserver.platform.api.QueryValidationException.class);
    }

    private String metadata(String eventId, String device) {
        return metadata(eventId, device, 1000);
    }

    private String metadata(String eventId, String device, long occurredAt) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.nameUUIDFromBytes(eventId.getBytes())
                + "\",\"occurredAt\":" + occurredAt + ",\"packageName\":\"com.example.app\",\"appVersion\":\"1\","
                + "\"versionCode\":1,\"anonymousDeviceId\":\"" + device + "\",\"processId\":\"11111111-1111-4111-8111-111111111111\",\"processName\":\"p\"}";
    }

    private String reportJson(String paths, String classInfos, String leakObjects) {
        String gcPaths = "[]";
        // The helper accepts a list of signatures so tests can exercise repeated signature input.
        if (!paths.equals("[]")) {
            StringBuilder value = new StringBuilder("[");
            String[] signatures = paths.replace("[", "").replace("]", "").replace("\"", "").split(",");
            for (int i = 0; i < signatures.length; i++) {
                if (i > 0) value.append(',');
                value.append("{\"signature\":\"").append(signatures[i]).append("\",\"gcRoot\":\"root\",\"leakReason\":\"reason\",\"instanceCount\":1,\"path\":[{\"reference\":\"").append(signatures[i]).append("\",\"referenceType\":\"instance\"}]}");
            }
            gcPaths = value.append(']').toString();
        }
        return "{\"runningInfo\":{},\"gcPaths\":" + gcPaths + ",\"classInfos\":" + classInfos
                + ",\"leakObjects\":" + leakObjects + "}";
    }
}
