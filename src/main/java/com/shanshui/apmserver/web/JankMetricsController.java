package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.FpsMetricsResponse;
import com.shanshui.apmserver.domain.MetricDimensionsResponse;
import com.shanshui.apmserver.domain.MetricTrendResponse;
import com.shanshui.apmserver.domain.SuspensionRateResponse;
import com.shanshui.apmserver.service.JankMetricsQueryService;
import com.shanshui.apmserver.service.AppAuthorizationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 卡顿相关场景 FPS 和设备日挂起率查询入口。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/jank-metrics")
public class JankMetricsController {

    private final JankMetricsQueryService queryService;
    private final AppAuthorizationService authorizationService;

    public JankMetricsController(JankMetricsQueryService queryService,
                                  AppAuthorizationService authorizationService) {
        this.queryService = queryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/fps")
    public FpsMetricsResponse fps(@PathVariable java.util.UUID appId,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @ModelAttribute QueryParams params,
                                  Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.fps(appId, from, to, params);
    }

    @GetMapping("/suspension-rate")
    public SuspensionRateResponse suspensionRate(@PathVariable java.util.UUID appId,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @ModelAttribute QueryParams params,
                                                 Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.suspensionRate(appId, from, to, params);
    }

    @GetMapping("/dimensions")
    public MetricDimensionsResponse dimensions(@PathVariable java.util.UUID appId,
                                               @RequestParam String metric,
                                               @RequestParam String dimension,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @ModelAttribute QueryParams params,
                                               Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.dimensions(appId, metric, dimension, from, to, params);
    }

    @GetMapping("/trend")
    public MetricTrendResponse trend(@PathVariable java.util.UUID appId,
                                     @RequestParam String metric,
                                     @RequestParam String interval,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @ModelAttribute QueryParams params,
                                     Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        return queryService.trend(appId, metric, interval, from, to, params);
    }
}
