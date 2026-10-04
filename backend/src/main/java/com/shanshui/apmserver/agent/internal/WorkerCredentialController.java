package com.shanshui.apmserver.agent.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 网页管理员管理执行凭据，沿用 Session 与 CSRF，不接收模型密钥。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/analysis-workers")
public class WorkerCredentialController {
    /** 凭据管理服务。 */
    private final WorkerCredentialService credentials;

    /** 注入管理服务。 */
    public WorkerCredentialController(WorkerCredentialService credentials) { this.credentials = credentials; }

    /** 应用由路由确定，有效期由服务端固定，不提供扩权字段。 */
    public record CreateRequest(/** 最小管理名称。 */ @NotBlank @Size(max=100) String name) {}

    /** 完整秘密仅在此响应展示一次，禁止缓存。 */
    @PostMapping
    public ResponseEntity<WorkerCredentialService.Created> create(@PathVariable UUID appId, @Valid @RequestBody CreateRequest request, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(credentials.create(appId, request.name(), authentication));
    }

    /** 管理列表仅含前缀，使用有界分页。 */
    @GetMapping
    public ResponseEntity<List<WorkerCredentialService.Metadata>> list(@PathVariable UUID appId, Authentication authentication,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(credentials.list(appId, authentication, page, size));
    }

    /** 重复撤销不生成新值；即使功能关闭也保留撤销入口。 */
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID appId, @PathVariable UUID credentialId, Authentication authentication) {
        credentials.revoke(appId, credentialId, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
