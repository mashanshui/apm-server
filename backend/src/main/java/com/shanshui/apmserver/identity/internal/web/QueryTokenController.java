package com.shanshui.apmserver.identity.internal.web;

import com.shanshui.apmserver.identity.internal.application.AppQueryTokenService;
import com.shanshui.apmserver.identity.internal.application.CurrentUserService;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenCreateRequest;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenCreated;
import com.shanshui.apmserver.identity.internal.domain.QueryTokenPage;
import org.springframework.http.CacheControl;
import org.springframework.beans.factory.annotation.Value;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** 网页 Session 管理接口；不接受应用查询 Bearer 替代登录态。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/query-tokens")
public class QueryTokenController {

    private final CurrentUserService users;
    private final AppQueryTokenService tokens;
    /** 关闭外部查询时仍保留列表与撤销，但停止发放新 Token。 */
    @Value("${apm.agent.query.enabled:false}")
    private boolean queryEnabled = true;

    /** 注入当前网页用户与 Token 生命周期服务。 */
    public QueryTokenController(CurrentUserService users, AppQueryTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** 创建响应只返回一次完整值，并禁止浏览器和代理缓存。 */
    @PostMapping
    public ResponseEntity<QueryTokenCreated> create(@PathVariable UUID appId,
                                                     @RequestBody QueryTokenCreateRequest request,
                                                     Authentication authentication) {
        if (!queryEnabled) {
            throw new QueryValidationException("AGENT_QUERY_DISABLED", "Agent 查询入口暂不可用", 503);
        }
        QueryTokenCreated created = tokens.create(users.requireId(authentication), appId,
                request.name(), request.expiresInDays());
        return ResponseEntity.created(URI.create("/api/v1/apps/" + appId + "/query-tokens/" + created.metadata().id()))
                .cacheControl(CacheControl.noStore().cachePrivate()).header("Pragma", "no-cache")
                .body(created);
    }

    /** 列表仅返回元数据和派生状态。 */
    @GetMapping
    public ResponseEntity<QueryTokenPage> list(@PathVariable UUID appId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(tokens.list(users.requireId(authentication), appId, page, size));
    }

    /** 同应用重复撤销仍返回 204。 */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID appId, @PathVariable UUID tokenId,
                                       Authentication authentication) {
        tokens.revoke(users.requireId(authentication), appId, tokenId);
        return ResponseEntity.noContent().build();
    }
}
