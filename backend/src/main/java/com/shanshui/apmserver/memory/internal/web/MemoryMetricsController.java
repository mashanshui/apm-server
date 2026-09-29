package com.shanshui.apmserver.memory.internal.web;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.memory.api.MemoryMetricsSummaryResponse;
import com.shanshui.apmserver.memory.api.MemoryMetricQuery;
import com.shanshui.apmserver.memory.api.MemoryMetricQueries;
import com.shanshui.apmserver.memory.api.MemoryTrendResponse;
import com.shanshui.apmserver.platform.api.QueryParams;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** 内存指标概览和趋势查询入口，要求 Session 成员授权。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/memory-metrics")
public class MemoryMetricsController {

    private static final Set<String> COMMON_PARAMS = Set.of("from", "to", "appVersion", "osVersion",
            "deviceModel", "processName", "scene", "foreground", "limit", "timeoutMs");
    private static final Set<String> TREND_PARAMS = Set.of("metric", "interval");

    private final MemoryMetricQueries queryService;
    private final AppAccessControl authorizationService;

    public MemoryMetricsController(MemoryMetricQueries queryService, AppAccessControl authorizationService) {
        this.queryService = queryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/summary")
    public MemoryMetricsSummaryResponse summary(@PathVariable UUID appId,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @ModelAttribute QueryParams params,
                                                @RequestParam MultiValueMap<String, String> query,
                                                Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        validateParameters(query, false);
        return queryService.summary(appId, from, to, command(params, query));
    }

    @GetMapping("/trend")
    public MemoryTrendResponse trend(@PathVariable UUID appId,
                                     @RequestParam(required = false) String metric,
                                     @RequestParam(required = false) String interval,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @ModelAttribute QueryParams params,
                                     @RequestParam MultiValueMap<String, String> query,
                                     Authentication authentication) {
        authorizationService.requireView(appId, authentication);
        validateParameters(query, true);
        return queryService.trend(appId, metric, interval, from, to, command(params, query));
    }

    private MemoryMetricQuery command(QueryParams params, MultiValueMap<String, String> query) {
        Boolean foreground = parseForeground(query.getFirst("foreground"));
        return new MemoryMetricQuery(params.getAppVersion(), params.getOsVersion(), params.getDeviceModel(),
                query.getFirst("processName"), params.getScene(), foreground, params.getLimit(), params.getTimeoutMs());
    }

    private void validateParameters(MultiValueMap<String, String> query, boolean trend) {
        for (String name : query.keySet()) {
            if (!COMMON_PARAMS.contains(name) && !(trend && TREND_PARAMS.contains(name))) {
                throw new QueryValidationException("INVALID_FILTER", "查询参数不在允许的白名单中: " + name, 400);
            }
        }
        parseForeground(query.getFirst("foreground"));
    }

    private Boolean parseForeground(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new QueryValidationException("INVALID_FOREGROUND", "foreground 必须是 true 或 false", 400);
    }
}
