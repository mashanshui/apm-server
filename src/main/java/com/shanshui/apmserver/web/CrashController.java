package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.CrashEventDetailResponse;
import com.shanshui.apmserver.domain.CrashEventListResponse;
import com.shanshui.apmserver.domain.CrashIssueResponse;
import com.shanshui.apmserver.domain.CrashOverviewResponse;
import com.shanshui.apmserver.domain.CrashTrendResponse;
import com.shanshui.apmserver.service.CrashQueryService;
import com.shanshui.apmserver.service.ProjectAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/crashes")
public class CrashController {

    private final CrashQueryService queryService;
    private final ProjectAuthorizationService authorizationService;

    public CrashController(CrashQueryService queryService,
                           ProjectAuthorizationService authorizationService) {
        this.queryService = queryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/overview")
    public CrashOverviewResponse overview(@PathVariable String projectId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @ModelAttribute QueryParams params,
                                          HttpServletRequest request) {
        authorizationService.requireView(projectId, request);
        return queryService.overview(projectId, from, to, params);
    }

    @GetMapping("/trend")
    public CrashTrendResponse trend(@PathVariable String projectId,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "hour") String interval,
                                    @ModelAttribute QueryParams params,
                                    HttpServletRequest request) {
        authorizationService.requireView(projectId, request);
        return queryService.trend(projectId, from, to, interval, params);
    }

    @GetMapping("/issues")
    public CrashIssueResponse issues(@PathVariable String projectId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @ModelAttribute QueryParams params,
                                     HttpServletRequest request) {
        authorizationService.requireView(projectId, request);
        return queryService.issues(projectId, from, to, params);
    }

    @GetMapping("/issues/{fingerprint}/events")
    public CrashEventListResponse events(@PathVariable String projectId,
                                         @PathVariable String fingerprint,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @ModelAttribute QueryParams params,
                                         HttpServletRequest request) {
        authorizationService.requireView(projectId, request);
        return queryService.events(projectId, fingerprint, from, to, params);
    }

    @GetMapping("/events/{eventId}")
    public CrashEventDetailResponse event(@PathVariable String projectId,
                                          @PathVariable String eventId,
                                          HttpServletRequest request) {
        authorizationService.requireView(projectId, request);
        return queryService.event(projectId, eventId);
    }
}
