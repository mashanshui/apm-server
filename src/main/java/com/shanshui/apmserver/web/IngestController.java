package com.shanshui.apmserver.web;

import tools.jackson.databind.ObjectMapper;
import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.BatchIngestResponse;
import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.service.CrashIngestionService;
import com.shanshui.apmserver.service.InvalidBatchException;
import com.shanshui.apmserver.service.InvalidProjectKeyException;
import com.shanshui.apmserver.service.PayloadTooLargeException;
import com.shanshui.apmserver.service.ProjectKeyAuthenticator;
import com.shanshui.apmserver.service.UnsupportedMediaTypeException;
import com.shanshui.apmserver.service.UnsupportedSchemaVersionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/ingest/v1")
public class IngestController {

    private final ObjectMapper objectMapper;
    private final IngestProperties properties;
    private final ProjectKeyAuthenticator authenticator;
    private final CrashIngestionService ingestionService;

    public IngestController(ObjectMapper objectMapper,
                            IngestProperties properties,
                            ProjectKeyAuthenticator authenticator,
                            CrashIngestionService ingestionService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.authenticator = authenticator;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/batches")
    public ResponseEntity<BatchIngestResponse> ingest(
            @RequestHeader(value = "X-Project-Key", required = false) String projectKey,
            @RequestHeader(value = "X-Schema-Version", required = false) String schemaVersion,
            HttpServletRequest request) {
        String projectId = authenticator.authenticate(projectKey);
        validateHeaderSchema(schemaVersion);
        EventBatchRequest batch = parseBatch(request);
        return ResponseEntity.ok(ingestionService.ingest(projectId, batch));
    }

    private EventBatchRequest parseBatch(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new UnsupportedMediaTypeException("首期仅支持 application/json，Protobuf 接入待后续版本启用");
        }
        try {
            if (request.getContentLengthLong() > properties.getMaxRequestBytes()) {
                throw new PayloadTooLargeException("请求体超过大小上限");
            }
            InputStream input = new LimitedInputStream(request.getInputStream(), properties.getMaxRequestBytes());
            String encoding = request.getHeader("Content-Encoding");
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                input = new GZIPInputStream(input);
            } else if (encoding != null && !encoding.isBlank() && !encoding.equalsIgnoreCase("identity")) {
                throw new UnsupportedMediaTypeException("不支持的 Content-Encoding");
            }
            input = new LimitedInputStream(input, properties.getMaxDecompressedBytes());
            return objectMapper.readValue(input, EventBatchRequest.class);
        } catch (PayloadTooLargeException | UnsupportedMediaTypeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new InvalidBatchException("请求体不是有效的 JSON 或 gzip 数据");
        }
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
