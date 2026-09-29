package com.shanshui.apmserver.agentquery.internal.web;

import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.identity.api.QueryApplicationInfo;
import com.shanshui.apmserver.identity.api.QueryApplicationLookup;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 由独立 Bearer 安全链注入应用身份的 Agent 查询入口。 */
@RestController
@RequestMapping("/api/agent/v1")
public class AgentApplicationController {

    private final QueryApplicationLookup applications;

    /** 仅依赖 identity 公共查询契约。 */
    public AgentApplicationController(QueryApplicationLookup applications) { this.applications = applications; }

    /** 返回当前 Token 唯一绑定的应用，不接收外部 appId。 */
    @GetMapping("/application")
    public QueryApplicationInfo current(Authentication authentication,
                                        @RequestParam MultiValueMap<String, String> query) {
        AgentQueryParameters.validate(query, java.util.Set.of());
        AuthenticatedQueryToken principal = (AuthenticatedQueryToken) authentication.getPrincipal();
        return applications.get(principal.appId());
    }
}
