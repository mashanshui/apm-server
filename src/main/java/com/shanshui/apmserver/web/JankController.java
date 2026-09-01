package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.JankEventDetailResponse;
import com.shanshui.apmserver.domain.JankEventListResponse;
import com.shanshui.apmserver.domain.JankIssueResponse;
import com.shanshui.apmserver.domain.JankOverviewResponse;
import com.shanshui.apmserver.domain.JankTrendResponse;
import com.shanshui.apmserver.service.JankQueryService;
import com.shanshui.apmserver.service.AppAuthorizationService;
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
    private final AppAuthorizationService authorizationService;

    public JankController(JankQueryService queryService, AppAuthorizationService authorizationService) {
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
        return queryService.overview(appId, from, to, params);
    }

    @GetMapping("/trend")
    public JankTrendResponse trend(@PathVariable java.util.UUID appId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(defaultValue = "hour") String interval,
                                   @ModelAttribute QueryParams params,
                                   Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.trend(appId, from, to, interval, params);
    }

    @GetMapping("/issues")
    public JankIssueResponse issues(@PathVariable java.util.UUID appId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @ModelAttribute QueryParams params,
                                    Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.issues(appId, from, to, params);
    }

    @GetMapping("/issues/{fingerprint}/events")
    public JankEventListResponse events(@PathVariable java.util.UUID appId,
                                        @PathVariable String fingerprint,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @ModelAttribute QueryParams params,
                                        Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.events(appId, fingerprint, from, to, params);
    }

    @GetMapping("/events/{eventId}")
    public JankEventDetailResponse event(@PathVariable java.util.UUID appId,
                                         @PathVariable String eventId,
                                         Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.event(appId, eventId);
    }
}
