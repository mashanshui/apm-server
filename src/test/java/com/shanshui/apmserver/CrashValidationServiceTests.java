package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.service.CrashEventProcessor;
import com.shanshui.apmserver.service.CrashEventValidator;
import com.shanshui.apmserver.service.CrashFingerprintService;
import com.shanshui.apmserver.service.CrashSanitizer;
import com.shanshui.apmserver.service.EventValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrashValidationServiceTests {

    @Test
    void rejectsNativeAndNonFatalCrashWithoutRetry() {
        var properties = CrashTestSupport.ingestProperties();
        var processor = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), properties);
        EventEnvelope event = CrashTestSupport.event("bad-1", "crash", "session-1", "device-1", "3.2.0",
                CrashTestSupport.nowMillis(), CrashTestSupport.crash("java.lang.RuntimeException", "boom", 1, "A"));
        EventEnvelope invalidEvent = new EventEnvelope(event.schemaVersion(), event.eventId(), event.eventType(), event.occurredAt(),
                event.sessionId(), event.anonymousDeviceId(), event.appId(), event.appVersion(), event.versionCode(),
                event.buildId(), event.environment(), event.channel(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.measurements(), event.attributes(),
                new com.shanshui.apmserver.domain.CrashPayload("native", false, event.crash().throwableChain()));

        EventValidationException exception = assertThrows(EventValidationException.class,
                () -> processor.process("project-a", invalidEvent, java.time.Instant.now()));
        assertTrue(exception.getIssues().stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_CRASH_KIND")));
        assertTrue(exception.getIssues().stream().anyMatch(issue -> issue.code().equals("NON_FATAL_CRASH")));
    }

    @Test
    void rejectsOversizedMessage() {
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxMessageLength(8);
        var processor = new CrashEventProcessor(new CrashEventValidator(properties, CrashTestSupport.objectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), properties);
        EventEnvelope event = CrashTestSupport.event("bad-2", "crash", "session-1", "device-1", "3.2.0",
                CrashTestSupport.nowMillis(), CrashTestSupport.crash("java.lang.RuntimeException", "too-long-message", 1, "A"));

        EventValidationException exception = assertThrows(EventValidationException.class,
                () -> processor.process("project-a", event, java.time.Instant.now()));
        assertTrue(exception.getIssues().stream().anyMatch(issue -> issue.code().equals("MESSAGE_TOO_LONG")));
    }
}
