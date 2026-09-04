package com.shanshui.apmserver.crash.internal.web;

import com.shanshui.apmserver.platform.api.QueryParams;

import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashEventListResponse;
import com.shanshui.apmserver.crash.api.CrashIssueResponse;
import com.shanshui.apmserver.crash.api.CrashOverviewResponse;
import com.shanshui.apmserver.crash.api.CrashTrendResponse;
import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import com.shanshui.apmserver.identity.api.AppAccessControl;
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
    private final AppAccessControl authorizationService;

    public CrashController(CrashQueryService queryService,
                           AppAccessControl authorizationService) {
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
        return queryService.overview(appId, from, to, command(params));
    }

    @GetMapping("/trend")
    public CrashTrendResponse trend(@PathVariable java.util.UUID appId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "hour") String interval,
                                    @ModelAttribute QueryParams params,
                                    Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.trend(appId, from, to, interval, command(params));
    }

    @GetMapping("/issues")
    public CrashIssueResponse issues(@PathVariable java.util.UUID appId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @ModelAttribute QueryParams params,
                                     Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.issues(appId, from, to, command(params));
    }

    @GetMapping("/issues/{fingerprint}/events")
    public CrashEventListResponse events(@PathVariable java.util.UUID appId,
                                         @PathVariable String fingerprint,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @ModelAttribute QueryParams params,
                                         Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.events(appId, fingerprint, from, to, command(params));
    }

    @GetMapping("/events/{eventId}")
    public CrashEventDetailResponse event(@PathVariable java.util.UUID appId,
                                          @PathVariable String eventId,
                                          Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.event(appId, eventId);
    }

    private CrashQueryCommand command(QueryParams params) {
        return new CrashQueryCommand(params.getAppVersion(), params.getChannel(), params.getEnvironment(),
                params.getOsVersion(), params.getDeviceModel(), params.getFingerprint(), params.getLimit(),
                params.getCursor(), params.getTimeoutMs());
    }
}
