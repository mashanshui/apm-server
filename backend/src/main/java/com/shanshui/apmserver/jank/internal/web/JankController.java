package com.shanshui.apmserver.jank.internal.web;

import com.shanshui.apmserver.platform.api.QueryParams;

import com.shanshui.apmserver.jank.api.JankEventDetailResponse;
import com.shanshui.apmserver.jank.api.JankEventListResponse;
import com.shanshui.apmserver.jank.api.JankIssueResponse;
import com.shanshui.apmserver.jank.api.JankOverviewResponse;
import com.shanshui.apmserver.jank.api.JankTrendResponse;
import com.shanshui.apmserver.jank.internal.application.JankQueryService;
import com.shanshui.apmserver.jank.internal.domain.JankQueryCommand;
import com.shanshui.apmserver.identity.api.AppAccessControl;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/apps/{appId}/janks")
public class JankController {

    private final JankQueryService queryService;
    private final AppAccessControl authorizationService;

    public JankController(JankQueryService queryService, AppAccessControl authorizationService) {
        this.queryService = queryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/overview")
    public JankOverviewResponse overview(@PathVariable java.util.UUID appId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @ModelAttribute QueryParams params,
                                         Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.overview(appId, from, to, command(params));
    }

    @GetMapping("/trend")
    public JankTrendResponse trend(@PathVariable java.util.UUID appId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(defaultValue = "hour") String interval,
                                   @ModelAttribute QueryParams params,
                                   Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.trend(appId, from, to, interval, command(params));
    }

    @GetMapping("/issues")
    public JankIssueResponse issues(@PathVariable java.util.UUID appId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @ModelAttribute QueryParams params,
                                    Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.issues(appId, from, to, command(params));
    }

    @GetMapping("/issues/{fingerprint}/events")
    public JankEventListResponse events(@PathVariable java.util.UUID appId,
                                        @PathVariable String fingerprint,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @ModelAttribute QueryParams params,
                                        Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.events(appId, fingerprint, from, to, command(params));
    }

    @GetMapping("/events/{eventId}")
    public JankEventDetailResponse event(@PathVariable java.util.UUID appId,
                                         @PathVariable String eventId,
                                         Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.event(appId, eventId);
    }

    private JankQueryCommand command(QueryParams params) {
        return new JankQueryCommand(params.getAppVersion(), params.getChannel(), params.getEnvironment(),
                params.getOsVersion(), params.getDeviceModel(), params.getFingerprint(), params.getScene(),
                params.getAlgorithmVersion(), params.getLimit(), params.getCursor(), params.getTimeoutMs());
    }
}
