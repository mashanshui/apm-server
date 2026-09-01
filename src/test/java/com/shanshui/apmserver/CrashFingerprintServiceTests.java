package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.service.CrashFingerprintService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CrashFingerprintServiceTests {

    private final CrashFingerprintService service = new CrashFingerprintService();

    @Test
    void lineNumbersAndDynamicMessagesDoNotSplitIssue() {
        CrashPayload first = CrashTestSupport.crash("java.lang.IllegalStateException", "state for user 12345", 100,
                "com.example.checkout.PaymentActivity");
        CrashPayload second = CrashTestSupport.crash("java.lang.IllegalStateException", "state for user 98765", 200,
                "com.example.checkout.PaymentActivity");

        assertEquals(service.fingerprint(TestAppIds.id("app-a"), "app-a", first),
                service.fingerprint(TestAppIds.id("app-a"), "app-a", second));
        assertNotEquals(service.fingerprint(TestAppIds.id("app-a"), "app-a", first),
                service.fingerprint(TestAppIds.id("app-a"), "app-a",
                        CrashTestSupport.crash("java.lang.NullPointerException", "state for user 12345", 100,
                                "com.example.order.OrderRepository")));
    }
}
