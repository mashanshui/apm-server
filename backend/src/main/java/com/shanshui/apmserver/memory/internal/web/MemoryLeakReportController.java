package com.shanshui.apmserver.memory.internal.web;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.identity.api.AppKeyAuthentication;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakReportResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakQueries;
import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakAttachmentStoreException;
import com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakReportIngestService;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.platform.api.LimitedInputStream;
import com.shanshui.apmserver.platform.api.PayloadTooLargeException;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.platform.api.UnsupportedMediaTypeException;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.identity.api.PackageNameMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping
public class MemoryLeakReportController {
    private final AppKeyAuthentication authenticator;
    private final AppAccessControl accessControl;
    private final MemoryLeakReportIngestService ingestService;
    private final MemoryLeakQueries queryService;
    private final ObjectMapper objectMapper;
    private final com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration config;

    public MemoryLeakReportController(AppKeyAuthentication authenticator, AppAccessControl accessControl,
                                      MemoryLeakReportIngestService ingestService, MemoryLeakQueries queryService,
                                      ObjectMapper objectMapper, com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration config) {
        this.authenticator = authenticator; this.accessControl = accessControl; this.ingestService = ingestService; this.queryService = queryService; this.objectMapper = objectMapper; this.config = config;
    }

    @PostMapping(value = "/ingest/v1/memory-reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MemoryLeakReportResponse> rejectJsonUpload() {
        throw new UnsupportedMediaTypeException("内存报告必须使用 multipart/form-data 文件上传");
    }

    @PostMapping(value = "/ingest/v1/memory-reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MemoryLeakReportResponse> ingestMultipart(@RequestHeader(value = "X-App-Key", required = false) String appKey,
                                                                      @RequestPart(value = "metadata", required = false) MultipartFile metadata,
                                                                      @RequestPart(value = "report", required = false) MultipartFile report,
                                                                      @RequestPart(value = "hprof", required = false) MultipartFile hprof,
                                                                      HttpServletRequest request) {
        AuthenticatedApp app = authenticator.authenticate(appKey); rejectEncoding(request); checkLength(request.getContentLengthLong(), config.getMaxRequestBytes());
        requirePart(metadata, "metadata");
        requirePart(report, "report");
        requireJsonPart(metadata, "metadata");
        requireJsonPart(report, "report");
        if (metadata.getSize() > config.getMaxMetadataBytes()) throw new PayloadTooLargeException("报告 metadata 超过大小上限");
        if (report.getSize() > config.getMaxReportBytes()) throw new PayloadTooLargeException("report 文件超过大小上限");
        if (hprof != null && hprof.getSize() > config.getMaxAttachmentBytes()) throw new PayloadTooLargeException("HPROF 附件超过大小上限");
        try (InputStream metadataInput = new LimitedInputStream(metadata.getInputStream(), config.getMaxMetadataBytes(), "报告 metadata 超过大小上限");
             InputStream reportInput = new LimitedInputStream(report.getInputStream(), config.getMaxReportBytes(), "report 文件超过大小上限");
             InputStream attachment = hprof == null ? null : hprof.getInputStream()) {
            JsonNode metadataNode = readJson(metadataInput, "metadata");
            JsonNode reportNode = readJson(reportInput, "report");
            return ResponseEntity.ok(ingestService.ingest(app.appId(), app.packageName(), metadataNode, reportNode, attachment));
        } catch (PayloadTooLargeException | UnsupportedMediaTypeException | MemoryLeakReportValidationException |
                 MemoryLeakAttachmentStoreException | MemoryLeakEventConflictException | EventStoreUnavailableException |
                 PackageNameMismatchException ex) { throw ex;
        } catch (Exception ex) { throw new MemoryLeakReportValidationException("metadata 或 report JSON 无法解析", ex); }
    }

    @GetMapping("/api/v1/apps/{appId}/memory-leaks/issues")
    public MemoryLeakIssuesResponse issues(@PathVariable UUID appId, @RequestParam java.util.Map<String,String> query, Authentication authentication) {
        accessControl.requireView(appId, authentication);
        return queryService.issues(appId, query);
    }

    @GetMapping("/api/v1/apps/{appId}/memory-leaks/trend")
    public MemoryLeakTrendResponse trend(@PathVariable UUID appId, @RequestParam java.util.Map<String,String> query, Authentication authentication) {
        accessControl.requireView(appId, authentication);
        return queryService.trend(appId, query);
    }

    private void rejectEncoding(HttpServletRequest request) { if (request.getHeader("Content-Encoding") != null) throw new UnsupportedMediaTypeException("不支持压缩请求"); }
    private void checkLength(long length, long limit) { if (length > limit) throw new PayloadTooLargeException("报告请求超过大小上限"); }

    private void requirePart(MultipartFile part, String name) {
        if (part == null || part.isEmpty()) throw new MemoryLeakReportValidationException(name + " 文件不能为空");
    }

    private void requireJsonPart(MultipartFile part, String name) {
        String contentType = part.getContentType();
        if (contentType == null || !isJson(contentType)) {
            throw new UnsupportedMediaTypeException(name + " part 必须使用 application/json");
        }
    }

    private boolean isJson(String contentType) {
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private JsonNode readJson(InputStream input, String name) {
        try {
            JsonNode value = objectMapper.readTree(input);
            if (value == null) throw new MemoryLeakReportValidationException(name + " JSON 不能为空");
            return value;
        } catch (MemoryLeakReportValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MemoryLeakReportValidationException(name + " JSON 无法解析", ex);
        }
    }
}
