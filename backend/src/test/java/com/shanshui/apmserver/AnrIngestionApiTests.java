package com.shanshui.apmserver;

import com.shanshui.apmserver.anr.internal.persistence.InMemoryAnrRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过公共 HTTP 接收链验证 ANR 分派、校验和存储前脱敏。 */
@SpringBootTest
@AutoConfigureMockMvc
class AnrIngestionApiTests extends AppIngestApiTestSupport {
    /** 实际批次 HTTP 入口。 */
    @Autowired private MockMvc mvc;
    /** 隔离内存存储。 */
    @Autowired private InMemoryAnrRepository repository;
    /** 读取实际分页响应。 */
    @Autowired private ObjectMapper mapper;

    /** 每次测试建立隔离应用和空事件集。 */
    @BeforeEach
    void reset() {
        repository.clear();
        resetAppCredential("com.example.app");
    }

    /** 两种来源经普通 JSON 和 gzip 接收，重试保留首次正文。 */
    @Test
    void acceptsBothSourcesAndPreservesFirstSanitizedEvent() throws Exception {
        String first = batch(event("one", "message_timeout", "token=secret-one"));
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(content().string(not(containsString("secret-one"))));
        String second = batch(event("two", "process_error_state", "Cookie: secret-two"));
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                        .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(gzip(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(event("one", "message_timeout", "changed"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(1));
        var stored = repository.find(appId(), "one").orElseThrow();
        assertThat(stored.payload().trace().text()).doesNotContain("secret-one").doesNotContain("changed");
        assertThat(stored.payload().message()).doesNotContain("secret-message");
        assertThat(stored.payload().anrInfo().text()).doesNotContain("secret-info");
        assertThat(stored.payload().diagnostics().logcat().main().lines()).noneMatch(line -> line.contains("secret-log"));
        assertThat(stored.payload().diagnostics().openFiles().entries().getFirst().target()).isEqualTo("[path]");
        assertThat(stored.payload().diagnostics().memory().javaHeapUsedBytes()).isZero();
        assertThat(repository.find(appId(), "two").orElseThrow().payload().source()).isEqualTo("process_error_state");
    }

    /** 同一批次中的无效来源、冲突载荷和不一致分区只拒绝相应事件。 */
    @Test
    void rejectsInvalidEventsIndividually() throws Exception {
        String valid = event("valid", "message_timeout", "safe");
        String badSource = event("bad-source", "unknown", "secret-input");
        String conflict = event("conflict", "message_timeout", "safe").replace("\"anr\":{", "\"crash\":{},\"anr\":{");
        String badStatus = event("bad-status", "message_timeout", "safe").replace("\"status\":\"ok\"", "\"status\":\"unavailable\"");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"mixed\",\"events\":[" + valid + "," + badSource + "," + conflict + "," + badStatus + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(3))
                .andExpect(content().string(not(containsString("secret-input"))));
        assertThat(repository.find(appId(), "valid")).isPresent();
        assertThat(repository.find(appId(), "bad-source")).isEmpty();
    }

    /** 缺少 ANR 载荷或其他事件附带 ANR 均按事件级拒绝。 */
    @Test
    void rejectsMissingAndMisplacedPayloadsIndividually() throws Exception {
        String valid = event("valid", "message_timeout", "safe");
        String missing = valid.replace("\"eventId\":\"valid\"", "\"eventId\":\"missing\"")
                .replace("\"eventType\":\"anr\"", "\"eventType\":\"app_start\"");
        String wrongType = valid.replace("\"eventId\":\"valid\"", "\"eventId\":\"wrong-type\"")
                .replace("\"eventType\":\"anr\"", "\"eventType\":\"app_start\"");
        // 删除载荷后保留 anr 类型，用于覆盖缺失专属载荷。
        missing = missing.substring(0, missing.indexOf(",\"anr\":")) + "}";
        missing = missing.replace("\"eventType\":\"app_start\"", "\"eventType\":\"anr\"");
        String start = missing.replace("\"eventId\":\"missing\"", "\"eventId\":\"start\"")
                .replace("\"eventType\":\"anr\"", "\"eventType\":\"app_start\"");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"payloads\",\"events\":[" + valid + "," + start + "," + missing + "," + wrongType + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(2));
    }

    /** 包名绑定、进程身份、诊断编码大小都在写入前校验。 */
    @Test
    void rejectsPackageIdentityAndOversizedText() throws Exception {
        String valid = event("valid", "message_timeout", "safe");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(valid.replace("com.example.app", "com.example.other"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(valid.replace("11111111-1111-4111-8111-111111111111", "123"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(event("large", "message_timeout", "x".repeat(100 * 1024)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1));
        assertThat(repository.find(appId(), "valid")).isEmpty();
        assertThat(repository.find(appId(), "large")).isEmpty();
    }

    /** 原始 JSON 转义占用的字节同样计入事件上限，其他事件仍可接受。 */
    @Test
    void countsOriginalEscapedEventBytes() throws Exception {
        String valid = event("valid", "message_timeout", "safe");
        String escaped = event("escaped", "message_timeout", "\\u0078".repeat(46_000));
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"escaped\",\"events\":[" + valid + "," + escaped + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("EVENT_TOO_LARGE"));
        assertThat(repository.find(appId(), "escaped")).isEmpty();
    }

    /** 版本、构建和原会话身份按原值保存，不能被自由文本脱敏规则改写。 */
    @Test
    void preservesBuildAndSessionIdentity() throws Exception {
        String event = event("identity", "message_timeout", "safe")
                .replace("\"appVersion\":\"1\"", "\"appVersion\":\"123456789\"")
                .replace("\"sessionId\":\"session\"", "\"sessionId\":\"session-123456789\"");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(event)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        var stored = repository.find(appId(), "identity").orElseThrow();
        assertThat(stored.metadata().appVersion()).isEqualTo("123456789");
        assertThat(stored.metadata().sessionId()).isEqualTo("session-123456789");
    }

    /** 验证应用授权、筛选、同时间游标和详情与列表的内容边界。 */
    @Test
    void queriesAuthorizedEventsWithBoundCursor() throws Exception {
        long time = System.currentTimeMillis();
        String first = event("a", "message_timeout", "trace-a").replaceFirst("\\\"occurredAt\\\":\\d+", "\"occurredAt\":" + time);
        String second = event("b", "message_timeout", "trace-b").replaceFirst("\\\"occurredAt\\\":\\d+", "\"occurredAt\":" + time);
        String third = event("c", "process_error_state", "trace-c").replaceFirst("\\\"occurredAt\\\":\\d+", "\"occurredAt\":" + time)
                .replace("\"anonymousDeviceId\":\"device\"", "\"anonymousDeviceId\":\"other-device\"")
                .replace("\"appVersion\":\"1\"", "\"appVersion\":\"2\"");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"query\",\"events\":[" + first + "," + second + "," + third + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(3));
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        String base = "/api/v1/apps/" + appId() + "/anrs";
        String from = Instant.ofEpochMilli(time - 1_000).toString();
        String to = Instant.ofEpochMilli(time + 1_000).toString();
        mvc.perform(get(base + "/overview").session(session).param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventCount").value(3))
                .andExpect(jsonPath("$.deviceCount").value(2));
        mvc.perform(get(base + "/overview").session(session).param("from", from).param("to", to)
                        .param("appVersion", "1").param("source", "message_timeout"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventCount").value(2))
                .andExpect(jsonPath("$.deviceCount").value(1));
        var result = mvc.perform(get(base + "/events").session(session).param("from", from).param("to", to)
                        .param("appVersion", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].eventId").value("b"))
                .andExpect(content().string(not(containsString("trace-b")))).andReturn();
        String cursor = mapper.readTree(result.getResponse().getContentAsByteArray()).path("nextCursor").asText();
        mvc.perform(get(base + "/events").session(session).param("appVersion", "1").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].eventId").value("a"));
        mvc.perform(get(base + "/events").session(session).param("appVersion", "2").param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get(base + "/events/a").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.processId").value("11111111-1111-4111-8111-111111111111"))
                .andExpect(jsonPath("$.anr.trace.text").value("trace-a"));
        mvc.perform(get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/anrs/events/a").session(session))
                .andExpect(status().isNotFound());
    }

    /** 构造只包含合成文本的 ANR 事件。 */
    private String event(String id, String source, String trace) {
        return """
                {"schemaVersion":2,"eventId":"%s","eventType":"anr","occurredAt":%d,
                 "sessionId":"session","processId":"11111111-1111-4111-8111-111111111111",
                 "anonymousDeviceId":"device","packageName":"com.example.app","appVersion":"1",
                 "versionCode":1,"buildId":"build","environment":"test","channel":"test",
                 "osVersion":"35","deviceModel":"synthetic","anr":{"source":"%s",
                 "mainThreadStack":"a.b(Source.java:1)","mainThreadStackTruncated":false,
                 "message":"token=secret-message",
                 "anrInfo":{"status":"ok","truncated":false,"text":"token=secret-info"},
                 "trace":{"status":"ok","truncated":false,"source":"art_first_write","text":"%s"},
                 "diagnostics":{"memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0},
                 "logcat":{"status":"ok","truncated":false,"main":{"status":"ok","truncated":false,
                 "lines":["token=secret-log"]}},"openFiles":{"status":"ok","truncated":false,"count":1,
                 "entries":[{"fd":10,"target":"/data/user/0/com.example.app/private"}]}}}}
                """.formatted(id, System.currentTimeMillis(), source, trace);
    }

    /** 构造单事件批次。 */
    private String batch(String event) {
        return "{\"requestId\":\"anr-test\",\"events\":[" + event + "]}";
    }

    /** 按实际线上编码压缩完整 UTF-8 正文。 */
    private byte[] gzip(String text) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
}
