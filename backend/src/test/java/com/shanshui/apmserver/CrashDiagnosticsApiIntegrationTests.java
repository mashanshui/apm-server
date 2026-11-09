package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
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

/** 通过真实 HTTP JSON 校验诊断接收、部分成功和授权详情。 */
@SpringBootTest
@AutoConfigureMockMvc
class CrashDiagnosticsApiIntegrationTests extends AppIngestApiTestSupport {
    /** MVC 测试入口。 */ @Autowired private MockMvc mvc;
    /** 隔离内存事件存储。 */ @Autowired private InMemoryCrashRepository repository;
    /** 与真实接收器一致的 JSON 映射器。 */ @Autowired private ObjectMapper mapper;

    /** 每个测试使用新应用凭据与空遥测集。 */
    @BeforeEach
    void reset() {
        repository.clear();
        resetAppCredential("com.example.app");
    }

    /** 合法 Crash 被接收，未知状态及 app_start 携带 Crash 各自拒绝。 */
    @Test
    void acceptsDiagnosticsAndRejectsInvalidEventsIndividually() throws Exception {
        long occurredAt = System.currentTimeMillis();
        String valid = event("valid", "crash", "ok", "first", occurredAt);
        String invalid = event("invalid", "crash", "future", "other", occurredAt);
        String start = event("start", "app_start", "ok", "other", occurredAt);
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"mixed\",\"events\":[" + valid + "," + invalid + "," + start + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(2));
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/v1/apps/" + appId() + "/crashes/events/valid").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stack.content.diagnostics").doesNotExist())
                .andExpect(jsonPath("$.diagnostics.crashedThread.systemTid").value(42))
                .andExpect(jsonPath("$.diagnostics.otherThreads.threads[0].symbolicationStatus").value("not_applicable"))
                .andExpect(jsonPath("$.diagnostics.otherThreads.threads[0].stack.content").isArray())
                .andExpect(jsonPath("$.diagnostics.memory.javaHeapUsedBytes").value(0))
                .andExpect(content().string(not(containsString("secret-one"))))
                .andExpect(content().string(not(containsString("secret-two"))));
        mvc.perform(get("/api/v1/apps/" + UUID.randomUUID() + "/crashes/events/valid").session(session))
                .andExpect(status().isNotFound());
        String changed = event("valid", "crash", "ok", "changed", occurredAt);
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"retry\",\"events\":[" + changed + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("CRASH_CONTENT_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].retryable").value(false));
    }

    /** 未知嵌套字段必须在绑定前拒绝，不能整批确认后丢掉字段。 */
    @Test
    void rejectsUnknownNestedFieldsBeforeBinding() throws Exception {
        String event = event("unknown-field", "crash", "ok", "line", System.currentTimeMillis())
                .replace("\"javaId\":\"2\"", "\"unexpected\":\"sentinel\",\"javaId\":\"2\"");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"unknown\",\"events\":[" + event + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH"));
    }

    /** 文档引用的完整合成样例经真实 HTTP 绑定与校验，不依赖样例中的历史发生时间。 */
    @Test
    void acceptsThePublishedCompleteSyntheticExample() throws Exception {
        ObjectNode batch = (ObjectNode) mapper.readTree(Files.readString(Path.of("../docs/examples/crash-diagnostics-batch.json")));
        ((ObjectNode) batch.path("events").get(0)).put("occurredAt", System.currentTimeMillis());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(batch)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    /** 只使用固定合成设备和凭据哨兵。 */
    private String event(String id, String eventType, String threadStatus, String line, long occurredAt) {
        return """
                {"schemaVersion":2,"eventId":"%s","eventType":"%s","occurredAt":%d,
                 "sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"1",
                 "versionCode":1,"buildId":"build","environment":"test","channel":"test",
                 "osVersion":"35","deviceModel":"synthetic",
                 "crash":{"kind":"jvm","fatal":true,"throwableChain":[{"type":"E",
                   "frames":[{"className":"A","methodName":"run"}]}],"diagnostics":{
                   "crashedThread":{"status":"ok","truncated":false,"javaId":"1","name":"worker",
                       "state":"RUNNABLE","priority":5,"daemon":false,"mainThread":false,"systemTid":42},
                   "otherThreads":{"status":"%s","truncated":false,"threads":[{"javaId":"2","name":"worker",
                       "state":"WAITING","priority":5,"daemon":true,"mainThread":true,"truncated":false,"frames":[]}]},
                   "memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0},
                   "logcat":{"status":"ok","truncated":false,"main":{"status":"ok","truncated":false,
                       "lines":["%s","Cookie: a=secret-one; b=secret-two"]}}}}}
                """.formatted(id, eventType, occurredAt, threadStatus, line);
    }
}
