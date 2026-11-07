package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.internal.application.CrashFingerprintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.shanshui.apmserver.symbol.api.RetraceFingerprintInput;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CrashFingerprintServiceTests {

    /** 无状态指纹算法。 */
    private final CrashFingerprintService service = new CrashFingerprintService();
    /** 合成 mapping 仅写测试临时目录。 */
    @TempDir Path directory;

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

    /** 两个不同混淆构建还原为同一路径时合组，临时结果则保持构建隔离。 */
    @Test
    void resolvesDifferentMappingsIntoOneStableIssue() throws Exception {
        // 相同源码调用在两次构建中使用不同名称和行号。
        Path firstMapping = Files.writeString(directory.resolve("first.mapping"), "com.example.Main -> a:\n    1:1:void run():10:10 -> b\n");
        Path secondMapping = Files.writeString(directory.resolve("second.mapping"), "com.example.Main -> x:\n    7:7:void run():90:90 -> y\n");
        R8RetraceEngine engine = new R8RetraceEngine();
        var first = engine.retrace(firstMapping, List.of("java.lang.IllegalStateException: state for user 12345", "\tat a.b(SourceFile:1)"), 1048576);
        var second = engine.retrace(secondMapping, List.of("java.lang.IllegalStateException: state for user 98765", "\tat x.y(SourceFile:7)"), 1048576);
        java.util.UUID app = TestAppIds.id("app-a");
        assertEquals(service.resolved(app, "com.example", first.fingerprintInput()), service.resolved(app, "com.example", second.fingerprintInput()));
        CrashPayload raw = CrashTestSupport.crash("java.lang.IllegalStateException", "state for user 12345", 1, "a");
        assertNotEquals(service.temporary(app, "com.example", "build-one", raw), service.temporary(app, "com.example", "build-two", raw));
        assertNotEquals(service.temporary(app, "com.example", "build-one", raw), service.resolved(app, "com.example", first.fingerprintInput()));
    }

    /** 候选排列变化不拆组，完整候选差异和内联顺序变化仍可区分。 */
    @Test
    void normalizesCandidateSetsWithoutPickingOneCandidate() {
        java.util.UUID app = TestAppIds.id("app-a");
        RetraceFingerprintInput first = RetraceFingerprintInput.fromText("java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)\n<OR> at com.example.Other.call(Other.java:2)");
        RetraceFingerprintInput reverse = RetraceFingerprintInput.fromText("java.lang.RuntimeException\n\tat com.example.Other.call(Other.java:9)\n<OR> at com.example.Main.run(Main.java:8)");
        RetraceFingerprintInput missing = RetraceFingerprintInput.fromText("java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)");
        assertEquals(service.resolved(app, "com.example", first), service.resolved(app, "com.example", reverse));
        assertNotEquals(service.resolved(app, "com.example", first), service.resolved(app, "com.example", missing));
    }
}
