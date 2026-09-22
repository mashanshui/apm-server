package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Crash 异常链转换为 Retrace 输入时保留原因顺序和行号。 */
class CrashStackTraceFormatterTests {

    /** 根异常和 cause 必须按完整链顺序转换成标准 Java 堆栈文本。 */
    @Test
    void keepsThrowableChainAndFrameLocations() {
        StackFrame rootFrame = new StackFrame("a.b.C", "d", "C.java", 12, true);
        StackFrame causeFrame = new StackFrame("x.y.Z", "q", null, null, true);
        CrashPayload payload = new CrashPayload("jvm_fatal", true, List.of(
                new ThrowableNode("a.b.E", "boom", List.of(rootFrame)),
                new ThrowableNode("x.y.F", "cause", List.of(causeFrame))));

        assertEquals(List.of(
                "a.b.E: boom",
                "\tat a.b.C.d(C.java:12)",
                "Caused by: x.y.F: cause",
                "\tat x.y.Z.q(Unknown Source)"), CrashStackTraceFormatter.lines(payload));
    }
}
