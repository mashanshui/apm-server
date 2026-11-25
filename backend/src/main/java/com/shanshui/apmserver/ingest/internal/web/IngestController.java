package com.shanshui.apmserver.ingest.internal.web;

import com.shanshui.apmserver.platform.api.LimitedInputStream;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import com.shanshui.apmserver.ingest.api.IngestConfiguration;
import com.shanshui.apmserver.ingest.api.BatchIngestResponse;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventIngestionService;
import com.shanshui.apmserver.ingest.internal.protocol.EventSchemaValidator;
import com.shanshui.apmserver.ingest.api.InvalidBatchException;
import com.shanshui.apmserver.identity.api.InvalidAppKeyException;
import com.shanshui.apmserver.platform.api.PayloadTooLargeException;
import com.shanshui.apmserver.identity.api.AppKeyAuthentication;
import com.shanshui.apmserver.platform.api.UnsupportedMediaTypeException;
import com.shanshui.apmserver.ingest.api.UnsupportedSchemaVersionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/ingest/v1")
public class IngestController {

    /** 批次传输安全日志，不记录正文和凭据。 */
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final ObjectMapper objectMapper;
    private final IngestConfiguration properties;
    private final AppKeyAuthentication authenticator;
    private final EventIngestionService ingestionService;
    private final EventSchemaValidator schemaValidator;

    public IngestController(ObjectMapper objectMapper,
                            IngestConfiguration properties,
                            AppKeyAuthentication authenticator,
                            EventIngestionService ingestionService,
                            EventSchemaValidator schemaValidator) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.authenticator = authenticator;
        this.ingestionService = ingestionService;
        this.schemaValidator = schemaValidator;
    }

    @PostMapping("/batches")
    public ResponseEntity<BatchIngestResponse> ingest(
            @RequestHeader(value = "X-App-Key", required = false) String appKey,
            @RequestHeader(value = "X-Schema-Version", required = false) String schemaVersion,
            HttpServletRequest request) {
        AuthenticatedApp app = authenticator.authenticate(appKey);
        validateHeaderSchema(schemaVersion);
        EventBatchRequest batch = parseBatch(request);
        return ResponseEntity.ok(ingestionService.ingest(app, batch));
    }

    /** 在业务分派前完成受限读取、内容编码和完整性校验。 */
    private EventBatchRequest parseBatch(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new UnsupportedMediaTypeException("首期仅支持 application/json，Protobuf 接入待后续版本启用");
        }
        try {
            if (request.getContentLengthLong() > properties.getMaxRequestBytes()) {
                throw new PayloadTooLargeException("请求体超过大小上限");
            }
            /** 原始线上流独立计数，包含解压器不需要的尾随字节。 */
            InputStream wire = new LimitedInputStream(request.getInputStream(), properties.getMaxRequestBytes());
            /** 按编码包装后的当前读取流。 */
            InputStream input = wire;
            // 读取所有头值，拒绝重复和叠加编码，不能按子串识别 gzip。
            /** 本次是否采用 gzip 内容编码。 */
            boolean compressed = gzipEncoding(request);
            if (compressed) {
                input = new GZIPInputStream(input);
            }
            input = new LimitedInputStream(input, properties.getMaxDecompressedBytes());
            // 完整读至 EOF 才能验证 gzip 尾部与 JSON 后空白的字节上限。
            /** 完整性校验完成后的有界正文。 */
            byte[] decoded;
            try (InputStream bounded = input) {
                decoded = bounded.readAllBytes();
                // 解压器可能预读或忽略尾随非 gzip 字节；剩余线上字节仍必须计入请求预算。
                wire.transferTo(java.io.OutputStream.nullOutputStream());
            }
            log.debug("batch decoded: gzip={} decodedBytes={}", compressed, decoded.length);
            var root = objectMapper.readTree(decoded);
            schemaValidator.validateBatch(root);
            EventBatchRequest batch = objectMapper.readValue(objectMapper.writeValueAsString(root), EventBatchRequest.class);
            var eventBytes = eventByteLengths(decoded);
            if (eventBytes.size() != batch.events().size()) throw new InvalidBatchException("事件计量结果与批次不一致");
            return new EventBatchRequest(batch.requestId(), batch.events(), eventBytes);
        } catch (PayloadTooLargeException | UnsupportedMediaTypeException ex) {
            log.debug("batch decode rejected: type={}", ex.getClass().getSimpleName());
            throw ex;
        } catch (IOException | JacksonException ex) {
            log.debug("batch decode failed: type={}", ex.getClass().getSimpleName());
            throw new InvalidBatchException("请求体不是有效的 JSON 或 gzip 数据");
        }
    }

    /** 按原始 UTF-8 token 位置计量事件，保留转义和空白的实际线上开销。 */
    private java.util.List<Long> eventByteLengths(byte[] decoded) {
        var sizes = new java.util.ArrayList<Long>();
        boolean foundEvents = false;
        try (var parser = objectMapper.createParser(decoded)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new InvalidBatchException("批次必须是对象");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("events".equals(name) && value == JsonToken.START_ARRAY) {
                    if (foundEvents) throw new InvalidBatchException("events 不能重复");
                    foundEvents = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        long start = parser.currentTokenLocation().getByteOffset();
                        parser.skipChildren();
                        long end = parser.currentLocation().getByteOffset();
                        if (start < 0 || end < start) throw new InvalidBatchException("无法计量事件大小");
                        sizes.add(end - start);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return java.util.List.copyOf(sizes);
    }

    /** 识别唯一内容编码，普通 JSON 的既有形式继续接受。 */
    private boolean gzipEncoding(HttpServletRequest request) {
        /** 线上全部同名头，不能仅获取首个值。 */
        var values = Collections.list(request.getHeaders("Content-Encoding"));
        if (values.isEmpty()) return false;
        if (values.size() != 1) throw new UnsupportedMediaTypeException("不支持重复 Content-Encoding");
        /** 大小写无关且去掉首尾空白的完整 token。 */
        String encoding = values.getFirst().trim();
        if (encoding.isEmpty() || encoding.equalsIgnoreCase("identity")) return false;
        if (encoding.equalsIgnoreCase("gzip")) return true;
        throw new UnsupportedMediaTypeException("不支持的 Content-Encoding");
    }

    private void validateHeaderSchema(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return;
        }
        try {
            if (Integer.parseInt(schemaVersion) != properties.getSupportedSchemaVersion()) {
                throw new UnsupportedSchemaVersionException("不支持的 Schema 版本");
            }
        } catch (NumberFormatException ex) {
            throw new InvalidBatchException("Schema 版本必须是整数");
        }
    }
}
