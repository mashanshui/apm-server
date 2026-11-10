package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 通过实际 HTTP 入口验证 Native 原始材料和共享诊断。 */
@SpringBootTest
@AutoConfigureMockMvc
class NativeCrashApiIntegrationTests extends AppIngestApiTestSupport {
    /** HTTP 测试入口。 */ @Autowired private MockMvc mvc;
    /** 隔离内存事件存储。 */ @Autowired private InMemoryCrashRepository repository;
    /** 与真实接收器一致的 JSON 映射器。 */ @Autowired private ObjectMapper mapper;

    /** 每个测试使用独立应用凭据和空事件集合。 */
    @BeforeEach
    void reset() {
        repository.clear();
        resetAppCredential("com.example.app");
    }

    /** Native 与 JVM 混合接收，非法 Native 地址按事件拒绝。 */
    @Test
    void acceptsMixedEventsAndReturnsPreciseNativeDetails() throws Exception {
        long timestamp = System.currentTimeMillis();
        String nativeEvent = nativeEvent("native-one", timestamp, "0xffffffffffffffff", "0x0", "first");
        String invalid = nativeEvent("bad", timestamp, "0x00", "0x0", "first");
        String jvm = jvmEvent("jvm-one", timestamp);
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"mixed\",\"events\":[" + nativeEvent + "," + invalid + "," + jvm + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_NATIVE_CRASH"));
        MockHttpSession session = login();
        mvc.perform(get("/api/v1/apps/" + appId() + "/crashes/events/native-one").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crashKind").value("native"))
                .andExpect(jsonPath("$.stack.format").value("raw_native"))
                .andExpect(jsonPath("$.symbolicationReason").value("native_symbolication_deferred"))
                .andExpect(jsonPath("$.stack.content.signal.faultAddress").value("0x0"))
                .andExpect(jsonPath("$.stack.content.registers.values.pc").value("0xffffffffffffffff"))
                .andExpect(jsonPath("$.stack.content.modules[0].elfBuildId").value("abcdef12"))
                .andExpect(jsonPath("$.diagnostics.nativeThreads.threads[0].systemTid").value(8))
                .andExpect(jsonPath("$.diagnostics.nativeThreads.threads[0].backtrace.frames[0].relativePc").value("0x11"))
                .andExpect(jsonPath("$.diagnostics.otherThreads.threads[0].relationToCrash").value("unknown"))
                .andExpect(jsonPath("$.diagnostics.otherThreads.threads[0].stack.content").isArray())
                .andExpect(jsonPath("$.diagnostics.memory.javaHeapUsedBytes").value(0))
                .andExpect(content().string(not(containsString("secret-value"))));
        // 原始 Native 辅助栈只存在于状态载荷，事实中的诊断元数据不重复保存帧。
        var stored = (CrashEvent) repository.findByEventId(appId(), "native-one").orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(stored.diagnosticsPayload().contains("relativePc"));
        org.junit.jupiter.api.Assertions.assertTrue(stored.state().threadStacksPayload().contains("0x11"));
        mvc.perform(get("/api/v1/apps/" + UUID.randomUUID() + "/crashes/events/native-one").session(session))
                .andExpect(status().isNotFound());
    }

    /** 相同身份和内容可重试，已确认内容改变时不得覆盖。 */
    @Test
    void rejectsContentChangesOnRetry() throws Exception {
        long timestamp = System.currentTimeMillis();
        String first = nativeEvent("retry", timestamp, "0x10", "0x0", "first");
        mvc.perform(postBatch(first)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        mvc.perform(postBatch(first)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(1));
        String changed = nativeEvent("retry", timestamp, "0x11", "0x0", "first");
        mvc.perform(postBatch(changed)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("CRASH_CONTENT_CONFLICT"));
        String changedModule = first.replace("abcdef12", "abcdef13");
        mvc.perform(postBatch(changedModule)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("CRASH_CONTENT_CONFLICT"));
        String changedLog = nativeEvent("retry", timestamp, "0x10", "0x0", "different");
        mvc.perform(postBatch(changedLog)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("CRASH_CONTENT_CONFLICT"));
    }

    /** 禁止字段必须在绑定前拒绝，不能丢弃后确认成功。 */
    @Test
    void rejectsForbiddenDumpFieldsBeforeBinding() throws Exception {
        String event = nativeEvent("dump", System.currentTimeMillis(), "0x0", "0x0", "first")
                .replace("\"abi\":\"arm64-v8a\"", "\"stack\":\"raw-memory\",\"abi\":\"arm64-v8a\"");
        mvc.perform(postBatch(event)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH"));
    }

    /** 即使通过日志或通用属性提交，内存转储也不能被确认。 */
    @Test
    void rejectsDumpsInsideTextAndAttributes() throws Exception {
        String event = nativeEvent("dump-text", System.currentTimeMillis(), "0x0", "0x0", "memory map:");
        mvc.perform(postBatch(event)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_NATIVE_CRASH"));
        /** 带日志标签的两列栈内存。 */
        String tagged = nativeEvent("tagged-stack", System.currentTimeMillis(), "0x0", "0x0",
                "Demo: 0000007fe0ef1ffa  0000007be0ef226f");
        mvc.perform(postBatch(tagged)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_NATIVE_CRASH"));
        /** 带日志标签的虚拟内存映射。 */
        String taggedMap = nativeEvent("tagged-map", System.currentTimeMillis(), "0x0", "0x0",
                "Demo: 0000007fe0ef1ffa-0000007be0ef226f rw-p 00000000");
        mvc.perform(postBatch(taggedMap)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_NATIVE_CRASH"));
        /** 带日志标签的寄存器附近内存标题。 */
        String taggedHeader = nativeEvent("tagged-header", System.currentTimeMillis(), "0x0", "0x0", "Demo: memory near x0:");
        mvc.perform(postBatch(taggedHeader)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_NATIVE_CRASH"));
        String attribute = nativeEvent("dump-attribute", System.currentTimeMillis(), "0x0", "0x0", "first")
                .replace("\"crash\":{", "\"attributes\":{\"stack\":\"raw\"},\"crash\":{");
        mvc.perform(postBatch(attribute)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1));
    }

    /** 上报 Key 即使出现在诊断日志中也不能进入已保存详情。 */
    @Test
    void redactsAppKeyAndStandaloneCredentialsInNativeLogs() throws Exception {
        /** 包含合成上报 Key 请求头的日志。 */
        String event = nativeEvent("credential", System.currentTimeMillis(), "0x0", "0x0",
                "X-App-Key: apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ");
        mvc.perform(postBatch(event)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        /** 授权读取测试应用的保存结果。 */
        MockHttpSession session = login();
        mvc.perform(get("/api/v1/apps/" + appId() + "/crashes/events/credential").session(session))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ"))));
        /** 单独出现的合成只读查询凭据。 */
        String standalone = nativeEvent("standalone-credential", System.currentTimeMillis(), "0x0", "0x0",
                "query apm_qt_AbCdefGHijkLmnoPQRstuVwXYZ");
        mvc.perform(postBatch(standalone)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        mvc.perform(get("/api/v1/apps/" + appId() + "/crashes/events/standalone-credential").session(session))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("apm_qt_AbCdefGHijkLmnoPQRstuVwXYZ"))));
    }

    /** 文档样例经真实 HTTP 接收，测试只刷新公开合成样例的发生时间。 */
    @Test
    void acceptsPublishedSyntheticExample() throws Exception {
        ObjectNode batch = (ObjectNode) mapper.readTree(Files.readString(Path.of("../docs/examples/native-crash-batch.json")));
        ((ObjectNode) batch.path("events").get(0)).put("occurredAt", System.currentTimeMillis());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(batch)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    /** 显式提供隔离设备样本时，验证 Android 实际冻结事件可由真实 HTTP 入口接收。 */
    @Test
    void acceptsExplicitAndroidDeviceSamples() throws Exception {
        String fixture = System.getenv("APM_NATIVE_CAPTURE_FIXTURE");
        assumeTrue(fixture != null && !fixture.isBlank(), "未提供隔离设备样本");
        var events = mapper.readTree(Files.readString(Path.of(fixture)));
        org.junit.jupiter.api.Assertions.assertTrue(events.isArray() && !events.isEmpty());
        for (var value : events) {
            ObjectNode event = (ObjectNode) value;
            event.put("packageName", "com.example.app");
            mvc.perform(postBatch(mapper.writeValueAsString(event)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                    .andExpect(jsonPath("$.rejected").value(0));
        }
    }

    /** 主回溯失败的空帧事件仍可接收，混合异常链和悬空模块则明确拒绝。 */
    @Test
    void acceptsMinimalCoreAndRejectsMixedOrDanglingFrames() throws Exception {
        ObjectNode minimal = (ObjectNode) mapper.readTree(nativeEvent("minimal", System.currentTimeMillis(), "0x0", "0x0", "first"));
        ObjectNode crash = (ObjectNode) minimal.path("crash");
        crash.remove("diagnostics");
        ObjectNode nativeCrash = (ObjectNode) crash.path("nativeCrash");
        nativeCrash.set("modules", mapper.createArrayNode());
        nativeCrash.set("backtrace", mapper.readTree("{\"status\":\"unavailable\",\"reason\":\"io_error\",\"truncated\":false,\"frames\":[]}"));
        mvc.perform(postBatch(mapper.writeValueAsString(minimal))).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        crash.set("throwableChain", mapper.readTree("[{\"type\":\"E\",\"frames\":[{\"className\":\"A\",\"methodName\":\"run\"}]}]"));
        mvc.perform(postBatch(mapper.writeValueAsString(minimal))).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1));
        String dangling = nativeEvent("dangling", System.currentTimeMillis(), "0x1", "0x0", "first")
                .replace("\"moduleId\":\"module\"", "\"moduleId\":\"absent\"");
        mvc.perform(postBatch(dangling)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1));
    }

    /** 按 JSON 转义后的 UTF-8 字节拒绝超长中文日志。 */
    @Test
    void rejectsOversizedUtf8LogLine() throws Exception {
        String event = nativeEvent("oversized", System.currentTimeMillis(), "0x0", "0x0", "中".repeat(700));
        mvc.perform(postBatch(event)).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CRASH_DIAGNOSTICS"));
    }

    /** 创建有凭据的合成批次请求。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postBatch(String event) {
        return post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"synthetic\",\"events\":[" + event + "]}");
    }

    /** 登录测试应用的管理员。 */
    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    /** 仅使用合成信号、线程和可重复的敏感值哨兵。 */
    private String nativeEvent(String id, long time, String pc, String faultAddress, String line) {
        return """
                {"schemaVersion":2,"eventId":"%s","eventType":"crash","occurredAt":%d,
                 "sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"1",
                 "versionCode":1,"buildId":"build","environment":"test","channel":"test",
                 "osVersion":"35","deviceModel":"synthetic",
                 "crash":{"kind":"native","fatal":true,"nativeCrash":{
                   "abi":"arm64-v8a","systemTid":7,"threadName":"worker","mainThread":false,
                   "signal":{"number":11,"name":"SIGSEGV","code":1,"faultAddress":"%s",
                             "abortMessage":"token=secret-value"},
                   "registers":{"status":"ok","truncated":false,"capturedAt":%d,"values":{"pc":"%s"}},
                   "modules":[{"id":"module","name":"libexample.so","abi":"arm64-v8a","elfBuildId":"abcdef12"}],
                   "backtrace":{"status":"ok","truncated":false,"capturedAt":%d,
                                "frames":[{"index":0,"moduleId":"module","relativePc":"0xb884"}]}},
                   "diagnostics":{"crashedThread":{"status":"ok","truncated":false,"systemTid":7},
                     "otherThreads":{"status":"ok","truncated":false,"threads":[{"javaId":"2","name":"worker",
                       "state":"RUNNABLE","priority":5,"daemon":false,"mainThread":true,"truncated":false,
                       "frames":[],"relationToCrash":"unknown"}]},
                     "nativeThreads":{"status":"ok","truncated":false,"threads":[{"systemTid":8,"name":"worker",
                       "mainThread":true,"backtrace":{"status":"ok","truncated":false,
                         "frames":[{"index":0,"moduleId":"module","relativePc":"0x11"}]}}]},
                     "logcat":{"status":"ok","truncated":false,"main":{"status":"ok","truncated":false,
                       "lines":["%s"]}},"memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0}}}}
                """.formatted(id, time, faultAddress, time, pc, time, line);
    }

    /** 保留现有 JVM 异常链语义的合成事件。 */
    private String jvmEvent(String id, long time) {
        return """
                {"schemaVersion":2,"eventId":"%s","eventType":"crash","occurredAt":%d,
                 "sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"1",
                 "versionCode":1,"buildId":"build","environment":"test","channel":"test",
                 "osVersion":"35","deviceModel":"synthetic","crash":{"kind":"jvm","fatal":true,
                 "throwableChain":[{"type":"java.lang.Error","frames":[{"className":"A","methodName":"run"}]}]}}
                """.formatted(id, time);
    }
}
