package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证诊断模型的合法空值、线程身份和独立资源边界。 */
class CrashDiagnosticsValidatorTests {
    /** 使用与业务一致的 JSON 映射器。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 被测诊断校验器。 */
    private final CrashDiagnosticsValidator validator = new CrashDiagnosticsValidator(mapper);

    /** 完整现场和空帧线程都能保持原样通过。 */
    @Test
    void acceptsCompleteSnapshotAndEmptyFrames() {
        // 使用合成数据，覆盖七个分区及可区分的零值。
        String json = """
                {"crashedThread":{"status":"ok","truncated":false,"capturedAt":1,
                    "javaId":"11","name":"worker","state":"RUNNABLE","priority":5,
                    "daemon":false,"mainThread":false,"systemTid":21},
                 "otherThreads":{"status":"ok","truncated":false,"observedCount":1,"omittedCount":0,
                    "threads":[{"javaId":"12","name":"worker","state":"WAITING","priority":5,
                    "daemon":true,"mainThread":true,"truncated":false,"frames":[]}]},
                 "logcat":{"status":"ok","truncated":false,
                    "main":{"status":"ok","truncated":false,"lines":[]},
                    "system":{"status":"unavailable","reason":"permission_denied","truncated":false,"lines":[]},
                    "events":{"status":"ok","truncated":false,"lines":["测试"]}},
                 "memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0,"javaHeapPssBytes":20},
                 "openFiles":{"status":"ok","truncated":false,"count":0,"entries":[]},
                 "process":{"status":"ok","truncated":false,"pid":22,"sdkInitializedAt":1},
                 "device":{"status":"ok","truncated":false,"apiLevel":29,"abis":["arm64-v8a"]}}
                """;
        CrashDiagnostics diagnostics = mapper.readValue(json, CrashDiagnostics.class);
        List<ValidationIssue> issues = validate(diagnostics);
        assertTrue(issues.isEmpty(), issues.toString());
        assertTrue(diagnostics.otherThreads().threads().getFirst().frames().isEmpty());
        assertTrue(diagnostics.memory().javaHeapUsedBytes() == 0L);
        assertTrue(mapper.writeValueAsString(diagnostics).contains("\"javaHeapUsedBytes\":0"));
    }

    /** 无现场和只带核心裁剪说明均合法，主异常不序列化空诊断。 */
    @Test
    void acceptsMissingAndMinimalDiagnostics() {
        assertTrue(validate(null).isEmpty());
        assertTrue(validate(mapper.readValue("{\"coreTruncation\":{\"truncated\":true,\"reason\":\"size_limit\"}}",
                CrashDiagnostics.class)).isEmpty());
        assertFalse(mapper.writeValueAsString(new CrashPayload("jvm", true, List.of())).contains("diagnostics"));
    }

    /** 线程同名允许，但 Java ID 重复以及未知状态必须拒绝。 */
    @Test
    void rejectsDuplicateIdentityAndUnknownStatus() {
        String json = """
                {"crashedThread":{"status":"ok","truncated":false,"javaId":"1","name":"same","state":"RUNNABLE"},
                 "otherThreads":{"status":"future","truncated":false,"threads":[
                   {"javaId":"1","name":"same","state":"WAITING","priority":5,
                    "daemon":false,"mainThread":false,"truncated":false,"frames":[]}]}}
                """;
        List<ValidationIssue> issues = validate(mapper.readValue(json, CrashDiagnostics.class));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("重复")));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("状态")));
    }

    /** 拒绝负值、超安全整数和日志超长，避免绕过 SDK 的不可信输入。 */
    @Test
    void rejectsInvalidNumbersAndOversizedLogs() {
        String longLine = "测".repeat(800);
        String json = "{\"memory\":{\"status\":\"ok\",\"truncated\":false,\"javaHeapUsedBytes\":-1,"
                + "\"totalPssBytes\":9007199254740992},\"logcat\":{\"status\":\"ok\",\"truncated\":false,"
                + "\"main\":{\"status\":\"ok\",\"truncated\":false,\"lines\":["
                + mapper.writeValueAsString(longLine) + "]}}}";
        List<ValidationIssue> issues = validate(mapper.readValue(json, CrashDiagnostics.class));
        assertTrue(issues.stream().filter(issue -> issue.message().contains("数值")).count() >= 2);
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("日志行")));
    }

    /** 不可信客户端绕过 SDK 时，线程数量和每线程帧数分别拒绝超限。 */
    @Test
    void rejectsTooManyThreadsAndPerThreadFrames() {
        String emptyThread = """
                {"javaId":"%d","name":"worker","state":"WAITING","priority":5,
                 "daemon":false,"mainThread":false,"truncated":false,"frames":[]}
                """;
        String threads = IntStream.rangeClosed(1, 65).mapToObj(emptyThread::formatted).collect(java.util.stream.Collectors.joining(","));
        String tooMany = "{\"otherThreads\":{\"status\":\"ok\",\"truncated\":false,\"threads\":[" + threads + "]}}";
        assertTrue(validate(mapper.readValue(tooMany, CrashDiagnostics.class)).stream()
                .anyMatch(issue -> issue.message().contains("线程数量")));
        String frames = IntStream.range(0, 33).mapToObj(index -> "{\"className\":\"A\",\"methodName\":\"run\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String oversized = "{\"otherThreads\":{\"status\":\"ok\",\"truncated\":false,\"threads\":["
                + emptyThread.formatted(1).replace("\"frames\":[]", "\"frames\":[" + frames + "]") + "]}}";
        assertTrue(validate(mapper.readValue(oversized, CrashDiagnostics.class)).stream()
                .anyMatch(issue -> issue.message().contains("线程帧")));
    }

    /** 主异常 200 帧与辅助 32 帧分别计数，完整事件仍受 256 KiB 限制。 */
    @Test
    void keepsPrimaryAndAuxiliaryFrameBudgetsIndependent() {
        var properties = new IngestConfigurationProperties();
        var eventValidator = new CrashEventValidator(properties, mapper);
        String frames = IntStream.range(0, 32).mapToObj(index -> "{\"className\":\"A\",\"methodName\":\"run\"}")
                .collect(java.util.stream.Collectors.joining(","));
        var diagnostics = mapper.readValue("{\"otherThreads\":{\"status\":\"ok\",\"truncated\":false,\"threads\":["
                + "{\"javaId\":\"2\",\"name\":\"worker\",\"state\":\"WAITING\",\"priority\":5,"
                + "\"daemon\":false,\"mainThread\":true,\"truncated\":false,\"frames\":[" + frames + "]}]}}", CrashDiagnostics.class);
        var frame = new StackFrame("A", "run", null, null, null);
        eventValidator.validate(event(List.of(new ThrowableNode("E", null, java.util.Collections.nCopies(200, frame))), diagnostics, Map.of()));
        var tooMany = assertThrows(EventValidationException.class, () -> eventValidator.validate(
                event(List.of(new ThrowableNode("E", null, java.util.Collections.nCopies(201, frame))), diagnostics, Map.of())));
        assertTrue(tooMany.getIssues().stream().anyMatch(issue -> issue.code().equals("TOO_MANY_STACK_FRAMES")));
        var tooLarge = assertThrows(EventValidationException.class, () -> eventValidator.validate(
                event(List.of(new ThrowableNode("E", null, List.of(frame))), diagnostics, Map.of("large", "x".repeat(256 * 1024)))));
        assertTrue(tooLarge.getIssues().stream().anyMatch(issue -> issue.code().equals("EVENT_TOO_LARGE")));
    }

    /** 只构造合法且不包含真实身份的事件包络。 */
    private CrashIngestCommand event(List<ThrowableNode> nodes, CrashDiagnostics diagnostics, Map<String, Object> attributes) {
        return new CrashIngestCommand(2, "event-bounds", "crash", System.currentTimeMillis(), "session",
                "11111111-1111-4111-8111-111111111111", "device", "com.example.app", "1", 1,
                "build", "test", "test", "35", "synthetic", null, Map.of(), attributes,
                new CrashPayload("jvm", true, nodes, diagnostics));
    }

    /** 收集校验问题，便于同时检查多个安全错误。 */
    private List<ValidationIssue> validate(CrashDiagnostics diagnostics) {
        List<ValidationIssue> issues = new ArrayList<>();
        validator.validate(diagnostics, issues);
        return issues;
    }
}
