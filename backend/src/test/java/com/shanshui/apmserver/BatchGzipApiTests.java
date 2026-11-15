package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 批次完整 gzip、内容编码和大小边界回归，不连接既有业务库。 */
@SpringBootTest(properties = {"apm.ingest.max-request-bytes=2048", "apm.ingest.max-decompressed-bytes=4096"})
@AutoConfigureMockMvc
class BatchGzipApiTests extends AppIngestApiTestSupport {
    /** 固定原事件时间，重试不得重新采样。 */
    private final long eventTime = System.currentTimeMillis();

    /** 真实 MVC 接收链路。 */
    @Autowired private MockMvc mvc;

    /** 隔离内存存储，用于确认解析失败没有写入事件。 */
    @Autowired private com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository crashRepository;

    /** 完整 gzip 内的语法错误、截断 JSON 和尾随 token 均为不可重试批次错误。 */
    @Test void rejectsMalformedJsonWithoutWritingEvents() throws Exception {
        resetAppCredential("com.example.app");
        for (String malformed : new String[] {"not-json", "{\"requestId\":", batch() + "{}"}) {
            for (String encoding : new String[] {"identity", "gzip"}) {
                /** 分别验证普通 JSON 与完整 gzip 的相同解析边界。 */
                byte[] body = encoding.equals("gzip") ? gzip(malformed) : malformed.getBytes(StandardCharsets.UTF_8);
                mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                        .header("Content-Encoding", encoding).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH"))
                        .andExpect(jsonPath("$.retryable").value(false));
                org.junit.jupiter.api.Assertions.assertTrue(crashRepository.findAll(appId()).isEmpty());
            }
        }
    }

    /** 生成 UTF-8 合成正文的完整 gzip。 */
    private byte[] gzip(String body) throws Exception {
        /** 当前 gzip 输出。 */
        var output = new ByteArrayOutputStream();
        try (var stream = new GZIPOutputStream(output)) { stream.write(body.getBytes(StandardCharsets.UTF_8)); }
        return output.toByteArray();
    }

    /** 完整 JSON 后的 gzip CRC 和尾部必须参与校验。 */
    @Test void rejectsCorruptAndTruncatedTrailers() throws Exception {
        resetAppCredential("com.example.app");
        /** 可完整解出 JSON 的原始 gzip。 */
        byte[] valid = gzip("{\"requestId\":\"trailer\",\"events\":[]}");
        /** 破坏 CRC，保留可解析 JSON。 */
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 8] ^= 1;
        for (byte[] body : new byte[][] {corrupt, Arrays.copyOf(valid, valid.length - 4), "{}".getBytes(StandardCharsets.UTF_8)}) {
            mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                    .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH"));
        }
    }

    /** 未知子串、重复头和多层编码必须拒绝。 */
    @Test void rejectsUnsupportedEncodings() throws Exception {
        resetAppCredential("com.example.app");
        for (String encoding : new String[] {"xgzip", "br", "gzip, br", "gzip, gzip"}) {
            mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                    .header("Content-Encoding", encoding).contentType(MediaType.APPLICATION_JSON).content(gzip("{}")))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.retryable").value(false));
        }
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "gzip", "gzip").contentType(MediaType.APPLICATION_JSON).content(gzip("{}")))
                .andExpect(status().isUnsupportedMediaType());
    }

    /** 已解析 JSON 后的空白也计入解压限制。 */
    @Test void countsFullDecodedBody() throws Exception {
        resetAppCredential("com.example.app");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "GZip").contentType(MediaType.APPLICATION_JSON)
                .content(gzip("{\"requestId\":\"limit\",\"events\":[]}" + " ".repeat(5000))))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    /** 启动事件的最小合法批次，用于验证真实写入与去重。 */
    private String batch() {
        return """
                {"requestId":"gzip-valid","events":[{"schemaVersion":2,"eventId":"gzip-start",
                "eventType":"app_start","occurredAt":%d,"sessionId":"s",
                "processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d",
                "packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build",
                "environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic"}]}
                """.formatted(eventTime);
    }

    /** 普通 JSON、标准 gzip 和大小写空白均保持合法接收与重复确认。 */
    @Test void acceptsEncodingsAndDuplicates() throws Exception {
        resetAppCredential("com.example.app");
        for (String encoding : new String[] {"", "identity", "gzip", " GZip "}) {
            /** 本次线上正文。 */
            byte[] body = encoding.trim().equalsIgnoreCase("gzip") ? gzip(batch()) : batch().getBytes(StandardCharsets.UTF_8);
            mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                    .header("Content-Encoding", encoding).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(gzip(batch())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(1));
    }

    /** 完整性失败时即使含合法事件也不能写入。 */
    @Test void trailerFailureDoesNotWriteEvents() throws Exception {
        resetAppCredential("com.example.app");
        /** 合法事件配损坏的长度尾部。 */
        byte[] bytes = gzip(batch());
        bytes[bytes.length - 1] ^= 1;
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(bytes))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(gzip(batch())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
    }

    /** 声明和未知长度的实际流均受限制，鉴权失败不得读取正文。 */
    @Test void limitsWireBytesAndAuthenticatesBeforeReading() throws Exception {
        resetAppCredential("com.example.app");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .contentType(MediaType.APPLICATION_JSON).content(" ".repeat(2049)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.retryable").value(false));
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .contentType(MediaType.APPLICATION_JSON).content(" ".repeat(2049)).with(request -> {
                    // 模拟没有 Content-Length 的受限实际流。
                    return new org.springframework.mock.web.MockHttpServletRequest(request.getServletContext()) {{
                        setMethod("POST"); setRequestURI("/ingest/v1/batches");
                        addHeader("X-App-Key", appKey()); setContentType("application/json");
                        setContent(" ".repeat(2049).getBytes(StandardCharsets.UTF_8));
                    }
                        /** 模拟未知线上长度。 */
                        @Override public long getContentLengthLong() { return -1; }
                        /** 模拟未知线上长度。 */
                        @Override public int getContentLength() { return -1; }
                    };
                })) .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", "invalid")
                .contentType(MediaType.APPLICATION_JSON).content("broken gzip").header("Content-Encoding", "gzip"))
                .andExpect(status().isUnauthorized());
    }

    /** 前台挂起只做合成协议验证，不假设 Android 已有采集上传器。 */
    @Test void acceptsSyntheticSuspensionGzip() throws Exception {
        resetAppCredential("com.example.app");
        /** 使用同一公共信封构造服务端已有的挂起载荷。 */
        String suspension = batch().replace("\"eventType\":\"app_start\"", "\"eventType\":\"foreground_suspension_summary\"")
                .replace("\"deviceModel\":\"synthetic\"", "\"deviceModel\":\"synthetic\",\"foregroundSuspensionSummary\":{\"algorithmVersion\":\"suspension-v1\",\"foregroundDurationMs\":1000,\"suspensionDurationMs\":400,\"suspensionCount\":2,\"thresholdMs\":200}");
        mvc.perform(post("/ingest/v1/batches").header("X-App-Key", appKey())
                .header("Content-Encoding", "gzip").contentType(MediaType.APPLICATION_JSON).content(gzip(suspension)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
    }
}
