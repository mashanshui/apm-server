package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashSanitizerTests {

    /** 保留用于 mapping 查找的 buildId，即使其中的数字看起来像电话号码。 */
    @Test
    void preservesBuildIdThatMatchesPhonePattern() {
        /** 当前 Crash 脱敏配置。 */
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        /** 被测 Crash 脱敏器。 */
        CrashSanitizer sanitizer = new CrashSanitizer(properties);
        /** 带日期式数字序列的合法构建标识。 */
        CrashIngestCommand event = new CrashIngestCommand(2, "symbol-event", "crash", 0L, "session",
                CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0", 1,
                "symbol-validation-2026-09-20", "test", "local", "16", "Pixel", "wifi",
                Map.of(), Map.of(), null);

        /** 脱敏后的 buildId 必须仍与 mapping 注册表中的键完全一致。 */
        assertEquals("symbol-validation-2026-09-20", sanitizer.sanitize(event).buildId());
    }

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

    /** 诊断文本二次脱敏，Java ID、PID 和字节数保持原值。 */
    @Test
    void redactsDiagnosticsWithoutChangingStructuredIdentities() {
        /** 使用合成凭据和私有文件名，不引入真实设备信息。 */
        String input = """
                {"crashedThread":{"status":"ok","truncated":false,"javaId":"123456789",
                  "name":"token=secret-one","state":"RUNNABLE","systemTid":42},
                 "otherThreads":{"status":"ok","truncated":false,"threads":[]},
                 "logcat":{"status":"ok","truncated":false,
                    "main":{"status":"ok","truncated":false,"lines":[
                      "Authorization: Bearer secret-two","Cookie: x=secret-three; y=secret-four",
                      "path=/data/user/0/com.example/files/sentinel file"]}},
                 "memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0},
                 "openFiles":{"status":"ok","truncated":false,"count":1,
                    "entries":[{"fd":3,"target":"/data/user/0/com.example/sentinel"}]},
                 "process":{"status":"ok","truncated":false,"pid":123456789},
                 "device":{"status":"ok","truncated":false,"abis":[]}}
                """;
        /** 模拟绕过 SDK 的直接客户端请求。 */
        var mapper = CrashTestSupport.objectMapper();
        var diagnostics = mapper.readValue(input, com.shanshui.apmserver.crash.api.CrashDiagnostics.class);
        var sanitized = new CrashSanitizer(CrashTestSupport.ingestProperties()).sanitizeDiagnostics(diagnostics);
        String output = mapper.writeValueAsString(sanitized);
        assertFalse(output.contains("secret-one"));
        assertFalse(output.contains("secret-two"));
        assertFalse(output.contains("secret-three"));
        assertFalse(output.contains("secret-four"));
        assertFalse(output.contains("sentinel"));
        assertEquals("123456789", sanitized.crashedThread().javaId());
        assertEquals(123456789, sanitized.process().pid());
        assertEquals(0L, sanitized.memory().javaHeapUsedBytes());
    }
}
