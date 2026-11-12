package com.shanshui.apmserver.symbol.internal.web;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.symbol.api.SymbolFileMetadata;
import com.shanshui.apmserver.symbol.api.SymbolFilePage;
import com.shanshui.apmserver.symbol.internal.application.SymbolManagementService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

/** 应用符号表列表、首次上传和管理员替换 HTTP 接口。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/symbols")
public class SymbolController {

    /** 应用访问权限边界。 */
    private final AppAccessControl accessControl;
    /** 符号表管理服务。 */
    private final SymbolManagementService managementService;
    /** 全批 SO 校验与事务提交。 */
    private final com.shanshui.apmserver.symbol.internal.application.NativeBatchService nativeBatches;
    /** 当前 JSON 确认协议。 */
    private final tools.jackson.databind.ObjectMapper mapper;
    /** 后台只读进度公开边界。 */
    private final com.shanshui.apmserver.crash.api.CrashSymbolicationWork work;

    /** 注入控制器依赖。 */
    public SymbolController(AppAccessControl accessControl,
                            SymbolManagementService managementService,
                            com.shanshui.apmserver.crash.api.CrashSymbolicationWork work,
                            com.shanshui.apmserver.symbol.internal.application.NativeBatchService nativeBatches,
                            tools.jackson.databind.ObjectMapper mapper) {
        this.accessControl = accessControl;
        this.managementService = managementService;
        this.work = work;
        this.nativeBatches = nativeBatches;
        this.mapper = mapper;
    }

    /** 查询当前应用的符号表元数据。 */
    @GetMapping
    public ResponseEntity<SymbolFilePage> list(@PathVariable UUID appId,
                                               @RequestParam(required = false) String buildId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String appVersion,
                                               @RequestParam(required = false) String mappingScope,
                                               @RequestParam(required = false) String abi,
                                               @RequestParam(required = false) String elfBuildId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               Authentication authentication) {
        accessControl.requireView(appId, authentication);
        return noStore(managementService.list(appId, type, appVersion, mappingScope, buildId, abi, elfBuildId, cursor, limit));
    }

    /** 首次上传一个构建的单份 mapping。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SymbolFileMetadata> upload(@PathVariable UUID appId,
                                                      @RequestPart("appVersion") String appVersion,
                                                      @RequestPart(value = "buildId", required = false) String buildId,
                                                      @RequestPart("file") MultipartFile file,
                                                      Authentication authentication) {
        accessControl.requireEdit(appId, authentication);
        UUID userId = accessControl.requireUserId(authentication);
        SymbolManagementService.UploadResult result = managementService.upload(appId, userId, appVersion, buildId, file);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (result.created()) {
            builder.location(URI.create("/api/v1/apps/" + appId + "/symbols/" + result.metadata().symbolId()));
        }
        return builder.body(result.metadata());
    }

    /** SO 仅登记实际 ELF 身份，不接受应用构建。 */
    @PostMapping(value = "/native-batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<com.shanshui.apmserver.symbol.internal.application.NativeBatch.Result> uploadNative(
            @PathVariable UUID appId, @RequestPart("appVersion") String appVersion,
            @RequestPart("file") MultipartFile file, @RequestPart(value="targetAbi", required=false) String targetAbi,
            @RequestPart(value="targetElfBuildId", required=false) String targetElfBuildId,
            @RequestPart(value="expectedEntries", required=false) String expectedEntries,
            Authentication authentication, jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException, jakarta.servlet.ServletException {
        accessControl.requireEdit(appId, authentication);
        var multipart = org.springframework.web.util.WebUtils.getNativeRequest(request, org.springframework.web.multipart.MultipartHttpServletRequest.class);
        if (request.getParameterMap().containsKey("buildId") || (multipart != null && multipart.getFileMap().containsKey("buildId"))) {
            throw new com.shanshui.apmserver.symbol.api.SymbolValidationException("SYMBOL_TYPE_MISMATCH", "SO 不接受应用 buildId", 400);
        }
        /** 有界确认 JSON，拒绝不明确的类型或多余身份。 */
        java.util.List<com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected> expected = null;
        if (expectedEntries != null) {
            if (expectedEntries.length() > 65536) throw new com.shanshui.apmserver.symbol.api.SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "确认过大", 400);
            try {
                var tree = mapper.readTree(expectedEntries);
                if (!tree.isArray() || tree.size() > 128) throw new IllegalArgumentException();
                var values = new java.util.ArrayList<com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected>();
                for (var entry : tree) {
                    if (!entry.isObject() || entry.size() != 4 || !entry.path("abi").isTextual() || !entry.path("elfBuildId").isTextual()
                            || !entry.has("symbolId") || !entry.has("revision")) throw new IllegalArgumentException();
                    if (!entry.path("symbolId").isNull() && !entry.path("symbolId").isTextual()) throw new IllegalArgumentException();
                    if (!entry.path("revision").isNull() && (!entry.path("revision").isIntegralNumber()
                            || !entry.path("revision").canConvertToInt() || entry.path("revision").asInt() < 1)) throw new IllegalArgumentException();
                    values.add(new com.shanshui.apmserver.symbol.internal.application.NativeBatch.Expected(
                            entry.path("abi").asText(), entry.path("elfBuildId").asText(),
                            entry.path("symbolId").isNull() ? null : UUID.fromString(entry.path("symbolId").asText()),
                            entry.path("revision").isNull() ? null : entry.path("revision").asInt()));
                }
                expected = java.util.List.copyOf(values);
            } catch (RuntimeException invalid) { throw new com.shanshui.apmserver.symbol.api.SymbolValidationException("INVALID_SYMBOL_CONFIRMATION", "确认格式无效", 400); }
        }
        /** 生产请求必须已在 multipart 前取得许可；测试也经过同一过滤器。 */
        Object deadline = request.getAttribute("nativeUploadDeadline");
        if (!(deadline instanceof Long)) throw new com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException("未取得 SO 接收许可");
        var result = nativeBatches.upload(appId, accessControl.requireUserId(authentication), appVersion, file,
                targetAbi, targetElfBuildId, expected, (Long) deadline);
        return ResponseEntity.status(result.createdCount() > 0 ? 201 : 200).cacheControl(CacheControl.noStore().cachePrivate()).body(result);
    }

    /** 管理员确认并替换一个已有构建的 mapping。 */
    @PutMapping(value = "/{symbolId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SymbolFileMetadata> replace(@PathVariable UUID appId,
                                                       @PathVariable UUID symbolId,
                                                       @RequestPart("file") MultipartFile file,
                                                       @RequestPart("expectedRevision") String expectedRevision,
                                                       Authentication authentication) {
        accessControl.requireEdit(appId, authentication);
        int revision;
        try {
            revision = Integer.parseInt(expectedRevision);
        } catch (NumberFormatException ex) {
            throw new com.shanshui.apmserver.symbol.api.SymbolValidationException(
                    "INVALID_REVISION", "expectedRevision 必须是正整数", 400);
        }
        UUID userId = accessControl.requireUserId(authentication);
        SymbolFileMetadata metadata = managementService.replace(appId, symbolId, userId, revision, file);
        return noStore(metadata);
    }

    /** revision 条件删除，授权与 CSRF 沿用统一网页安全链路。 */
    @DeleteMapping("/{symbolId}")
    public ResponseEntity<Void> delete(@PathVariable UUID appId, @PathVariable UUID symbolId,
            @RequestParam int expectedRevision, Authentication authentication) {
        accessControl.requireEdit(appId, authentication);
        managementService.delete(appId, symbolId, accessControl.requireUserId(authentication), expectedRevision);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore().cachePrivate()).build();
    }

    /** 活动符号表的构建进度，普通应用成员同样可查看。 */
    @GetMapping("/{symbolId}/crash-symbolication")
    public ResponseEntity<com.shanshui.apmserver.crash.api.CrashSymbolicationWork.Progress> progress(
            @PathVariable UUID appId, @PathVariable UUID symbolId, Authentication authentication) {
        accessControl.requireView(appId, authentication);
        return noStore(managementService.progress(appId, symbolId, work));
    }

    /** 为符号表元数据和冲突响应统一禁止缓存。 */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}
