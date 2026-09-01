package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.AppCreateRequest;
import com.shanshui.apmserver.domain.AppResponse;
import com.shanshui.apmserver.domain.AppIngestCredentialResponse;
import com.shanshui.apmserver.domain.AppUpdateRequest;
import com.shanshui.apmserver.service.CurrentUserService;
import com.shanshui.apmserver.service.AppManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/apps")
public class AppController {

    private final CurrentUserService currentUserService;
    private final AppManagementService appService;

    public AppController(CurrentUserService currentUserService,
                             AppManagementService appService) {
        this.currentUserService = currentUserService;
        this.appService = appService;
    }

    @GetMapping
    public List<AppResponse> list(@RequestParam(defaultValue = "") String query,
                                     Authentication authentication) {
        return appService.list(currentUserService.requireId(authentication), query);
    }

    @PostMapping
    public ResponseEntity<AppResponse> create(@RequestBody AppCreateRequest request,
                                                  Authentication authentication) {
        AppResponse created = appService.create(currentUserService.requireId(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/apps/" + created.appId())).body(created);
    }

    @GetMapping("/{appId}")
    public AppResponse get(@PathVariable java.util.UUID appId, Authentication authentication) {
        return appService.get(currentUserService.requireId(authentication), appId);
    }

    @PatchMapping("/{appId}")
    public AppResponse update(@PathVariable java.util.UUID appId,
                                  @RequestBody AppUpdateRequest request,
                                  Authentication authentication) {
        return appService.update(currentUserService.requireId(authentication), appId, request);
    }

    @GetMapping("/{appId}/ingest-credential")
    public ResponseEntity<AppIngestCredentialResponse> getIngestCredential(
            @PathVariable java.util.UUID appId, Authentication authentication) {
        AppIngestCredentialResponse credential = appService.getIngestCredential(
                currentUserService.requireId(authentication), appId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("Pragma", "no-cache")
                .body(credential);
    }
}
