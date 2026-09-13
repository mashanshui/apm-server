package com.shanshui.apmserver.memory.internal.web;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.identity.api.AppKeyAuthentication;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakReportResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakAttachmentStoreException;
import com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakReportIngestService;
import com.shanshui.apmserver.memory.internal.application.MemoryLeakQueryService;
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
    private static final Set<String> COMMON_QUERY_PARAMS = Set.of("from", "to", "appVersion", "deviceModel", "processName", "scene", "manufacturer", "sdkInt", "dumpReason", "anonymousDeviceId", "signature", "keyword");
    private static final Set<String> ISSUE_QUERY_PARAMS = union(COMMON_QUERY_PARAMS, Set.of("page", "pageSize", "sort", "order"));
    private static final Set<String> TREND_QUERY_PARAMS = union(COMMON_QUERY_PARAMS, Set.of("interval"));
    private final AppKeyAuthentication authenticator;
    private final AppAccessControl accessControl;
    private final MemoryLeakReportIngestService ingestService;
    private final MemoryLeakQueryService queryService;
    private final ObjectMapper objectMapper;
    private final com.shanshui.apmserver.memory.api.MemoryLeakReportConfiguration config;

    public MemoryLeakReportController(AppKeyAuthentication authenticator, AppAccessControl accessControl,
                                      MemoryLeakReportIngestService ingestService, MemoryLeakQueryService queryService,
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
        accessControl.requireView(appId, authentication); validateNames(query.keySet(), ISSUE_QUERY_PARAMS);
        MemoryLeakQueryFilter filter = filter(appId, query); int page = positive(query, "page", 1); int pageSize = positive(query, "pageSize", 20);
        if (pageSize > 100) throw invalid("pageSize", "pageSize 最大为 100");
        String sort = query.getOrDefault("sort", "occurrences"), order = query.getOrDefault("order", "desc");
        if (!Set.of("occurrences", "affectedDevices", "lastOccurredAt").contains(sort) || !Set.of("asc", "desc").contains(order)) throw invalid("sort", "sort/order 参数无效");
        return queryService.issues(filter, page, pageSize, sort, order);
    }

    @GetMapping("/api/v1/apps/{appId}/memory-leaks/trend")
    public MemoryLeakTrendResponse trend(@PathVariable UUID appId, @RequestParam java.util.Map<String,String> query, Authentication authentication) {
        accessControl.requireView(appId, authentication); validateNames(query.keySet(), TREND_QUERY_PARAMS); MemoryLeakQueryFilter filter = filter(appId, query);
        String interval = query.getOrDefault("interval", "hour"); if (!Set.of("5m", "hour", "day").contains(interval)) throw invalid("interval", "interval 参数无效");
        return queryService.trend(filter, interval);
    }

    private MemoryLeakQueryFilter filter(UUID appId, java.util.Map<String,String> query) {
        Instant to = query.containsKey("to") ? instant(query.get("to")) : Instant.now(); Instant from = query.containsKey("from") ? instant(query.get("from")) : to.minus(24, java.time.temporal.ChronoUnit.HOURS);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) throw invalid("range", "时间范围必须为正且不超过 31 天");
        Integer sdk = query.get("sdkInt") == null ? null : integer(query.get("sdkInt"), "sdkInt");
        return new MemoryLeakQueryFilter(appId, from, to, query.get("appVersion"), query.get("deviceModel"), query.get("processName"), query.get("scene"), query.get("manufacturer"), sdk, query.get("dumpReason"), query.get("anonymousDeviceId"), query.get("signature"), query.get("keyword"));
    }
    private Instant instant(String value) { try { return Instant.parse(value); } catch (DateTimeException ex) { try { return Instant.ofEpochMilli(Long.parseLong(value)); } catch (RuntimeException ignored) { throw invalid("time", "from/to 必须为 ISO-8601 或 Unix 毫秒"); } } }
    private int integer(String value, String field) { try { int parsed = Integer.parseInt(value); if (parsed < 0) throw new NumberFormatException(); return parsed; } catch (NumberFormatException ex) { throw invalid(field, field + " 必须是非负整数"); } }
    private int positive(java.util.Map<String,String> query, String field, int defaultValue) { int value = query.containsKey(field) ? integer(query.get(field), field) : defaultValue; if (value < 1) throw invalid(field, field + " 必须大于 0"); return value; }
    private void validateNames(Set<String> names, Set<String> allowed) { for (String name : names) if (!allowed.contains(name)) throw invalid(name, "查询参数不在允许白名单中: " + name); }
    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> values = new java.util.HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }
    private QueryValidationException invalid(String field, String message) { return new QueryValidationException("INVALID_FILTER", message, 400); }
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
