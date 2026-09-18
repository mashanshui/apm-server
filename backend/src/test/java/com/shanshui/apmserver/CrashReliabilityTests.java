package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrashReliabilityTests {

    @Test
    void retryUsesSameEventIdAndCountsDuplicateOnlyOnce() {
        InMemoryCrashRepository repository = new InMemoryCrashRepository(new com.shanshui.apmserver.platform.api.StorageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = CrashTestSupport.event("retry-event", "crash", "retry-session", "retry-device",
                "3.2.0", Instant.now().toEpochMilli(),
                CrashTestSupport.crash("java.lang.IllegalStateException", "boom", 1, "A"));

        assertEquals(1, ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event))).accepted());
        var retry = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event)));
        assertEquals(0, retry.accepted());
        assertEquals(1, retry.duplicate());
        assertEquals(CrashTestSupport.PROCESS_ID,
                repository.findByEventId(TestAppIds.id("app-a"), "retry-event").orElseThrow().processId());
    }

    @Test
    void unavailableStoreIsRetryable() {
        InMemoryCrashRepository repository = new InMemoryCrashRepository(new com.shanshui.apmserver.platform.api.StorageProperties());
        repository.setAvailable(false);
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = CrashTestSupport.event("store-down", "crash", "session", "device", "3.2.0",
                Instant.now().toEpochMilli(), CrashTestSupport.crash("java.lang.RuntimeException", "boom", 1, "A"));

        assertThrows(EventStoreUnavailableException.class,
                () -> ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event))));
    }
}
