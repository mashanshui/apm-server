package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashSanitizerTests {

    @Test
    void redactsSensitiveTextAndHashesDeviceId() {
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        CrashSanitizer sanitizer = new CrashSanitizer(properties);
        String result = sanitizer.sanitizeText(
                "email alice@example.com url https://example.com/user?id=123 path C:\\Users\\alice\\secret.txt", 4096);

        assertFalse(result.contains("alice@example.com"));
        assertFalse(result.contains("https://example.com"));
        assertFalse(result.contains("C:\\Users\\alice"));
        assertTrue(result.contains("[email]"));
        assertNotEquals("device-001", sanitizer.hashDeviceId("device-001"));
        assertTrue(sanitizer.hashDeviceId("device-001").matches("[0-9a-f]{64}"));
    }
}
