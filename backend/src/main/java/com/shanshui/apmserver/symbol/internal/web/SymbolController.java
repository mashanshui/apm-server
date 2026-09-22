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

    /** 注入控制器依赖。 */
    public SymbolController(AppAccessControl accessControl,
                            SymbolManagementService managementService) {
        this.accessControl = accessControl;
        this.managementService = managementService;
    }

    /** 查询当前应用的符号表元数据。 */
    @GetMapping
    public ResponseEntity<SymbolFilePage> list(@PathVariable UUID appId,
                                               @RequestParam(required = false) String buildId,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               Authentication authentication) {
        accessControl.requireView(appId, authentication);
        return noStore(managementService.list(appId, buildId, cursor, limit));
    }

    /** 首次上传一个构建的单份 mapping。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SymbolFileMetadata> upload(@PathVariable UUID appId,
                                                      @RequestPart("buildId") String buildId,
                                                      @RequestPart("file") MultipartFile file,
                                                      Authentication authentication) {
        accessControl.requireEdit(appId, authentication);
        UUID userId = accessControl.requireUserId(authentication);
        SymbolManagementService.UploadResult result = managementService.upload(appId, userId, buildId, file);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (result.created()) {
            builder.location(URI.create("/api/v1/apps/" + appId + "/symbols/" + result.metadata().symbolId()));
        }
        return builder.body(result.metadata());
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

    /** 为符号表元数据和冲突响应统一禁止缓存。 */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}
