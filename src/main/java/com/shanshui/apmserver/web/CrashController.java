package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.CrashEventDetailResponse;
import com.shanshui.apmserver.domain.CrashEventListResponse;
import com.shanshui.apmserver.domain.CrashIssueResponse;
import com.shanshui.apmserver.domain.CrashOverviewResponse;
import com.shanshui.apmserver.domain.CrashTrendResponse;
import com.shanshui.apmserver.service.CrashQueryService;
import com.shanshui.apmserver.service.AppAuthorizationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/apps/{appId}/crashes")
public class CrashController {

    private final CrashQueryService queryService;
    private final AppAuthorizationService authorizationService;

    public CrashController(CrashQueryService queryService,
                           AppAuthorizationService authorizationService) {
        this.queryService = queryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/overview")
    public CrashOverviewResponse overview(@PathVariable java.util.UUID appId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @ModelAttribute QueryParams params,
                                          Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.overview(appId, from, to, params);
    }

    @GetMapping("/trend")
    public CrashTrendResponse trend(@PathVariable java.util.UUID appId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "hour") String interval,
                                    @ModelAttribute QueryParams params,
                                    Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.trend(appId, from, to, interval, params);
    }

    @GetMapping("/issues")
    public CrashIssueResponse issues(@PathVariable java.util.UUID appId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @ModelAttribute QueryParams params,
                                     Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.issues(appId, from, to, params);
    }

    @GetMapping("/issues/{fingerprint}/events")
    public CrashEventListResponse events(@PathVariable java.util.UUID appId,
                                         @PathVariable String fingerprint,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @ModelAttribute QueryParams params,
                                         Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.events(appId, fingerprint, from, to, params);
    }

    @GetMapping("/events/{eventId}")
    public CrashEventDetailResponse event(@PathVariable java.util.UUID appId,
                                          @PathVariable String eventId,
                                          Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.event(appId, eventId);
    }
}
