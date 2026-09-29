package com.shanshui.apmserver.agentquery.internal.web;

import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashEventListResponse;
import com.shanshui.apmserver.crash.api.CrashIssueResponse;
import com.shanshui.apmserver.crash.api.CrashOverviewResponse;
import com.shanshui.apmserver.crash.api.CrashQueries;
import com.shanshui.apmserver.crash.api.CrashTrendResponse;
import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.jank.api.FpsMetricsResponse;
import com.shanshui.apmserver.jank.api.JankEventDetailResponse;
import com.shanshui.apmserver.jank.api.JankEventListResponse;
import com.shanshui.apmserver.jank.api.JankIssueResponse;
import com.shanshui.apmserver.jank.api.JankMetricQueries;
import com.shanshui.apmserver.jank.api.JankOverviewResponse;
import com.shanshui.apmserver.jank.api.JankQueries;
import com.shanshui.apmserver.jank.api.JankTrendResponse;
import com.shanshui.apmserver.jank.api.MetricDimensionsResponse;
import com.shanshui.apmserver.jank.api.MetricTrendResponse;
import com.shanshui.apmserver.jank.api.SuspensionRateResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakQueries;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.api.MemoryMetricQueries;
import com.shanshui.apmserver.memory.api.MemoryMetricQuery;
import com.shanshui.apmserver.memory.api.MemoryMetricsSummaryResponse;
import com.shanshui.apmserver.memory.api.MemoryTrendResponse;
import com.shanshui.apmserver.platform.api.QueryParams;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 只读 Agent HTTP 入口；应用身份仅从每次验证的 Token 推导。 */
@RestController
@RequestMapping("/api/agent/v1")
public class AgentQueryController {

    private static final Set<String> CRASH = Set.of("from", "to", "appVersion", "channel", "environment",
            "osVersion", "deviceModel", "fingerprint", "limit", "cursor", "timeoutMs");
    private static final Set<String> JANK = union(CRASH, Set.of("scene", "algorithmVersion"));
    private static final Set<String> METRIC = Set.of("from", "to", "appVersion", "channel", "environment",
            "osVersion", "deviceModel", "scene", "algorithmVersion", "limit", "timeoutMs");
    private static final Set<String> MEMORY = Set.of("from", "to", "appVersion", "osVersion", "deviceModel",
            "processName", "scene", "foreground", "limit", "timeoutMs");

    private final CrashQueries crashes;
    private final JankQueries janks;
    private final JankMetricQueries jankMetrics;
    private final MemoryMetricQueries memoryMetrics;
    private final MemoryLeakQueries memoryLeaks;

    /** 只注入领域公开查询契约，不跨域依赖内部类。 */
    public AgentQueryController(CrashQueries crashes, JankQueries janks, JankMetricQueries jankMetrics,
                                MemoryMetricQueries memoryMetrics, MemoryLeakQueries memoryLeaks) {
        this.crashes = crashes;
        this.janks = janks;
        this.jankMetrics = jankMetrics;
        this.memoryMetrics = memoryMetrics;
        this.memoryLeaks = memoryLeaks;
    }

    /** Crash 概览。 */
    @GetMapping("/crashes/overview")
    public CrashOverviewResponse crashOverview(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                               @ModelAttribute QueryParams params) {
        common(query, CRASH, params);
        return crashes.overview(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** Crash UTC 趋势。 */
    @GetMapping("/crashes/trend")
    public CrashTrendResponse crashTrend(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                         @ModelAttribute QueryParams params) {
        common(query, union(CRASH, Set.of("interval")), params);
        return crashes.trend(app(auth), query.getFirst("from"), query.getFirst("to"),
                query.getFirst("interval") == null ? "hour" : query.getFirst("interval"), params);
    }

    /** Crash 问题页。 */
    @GetMapping("/crashes/issues")
    public CrashIssueResponse crashIssues(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                          @ModelAttribute QueryParams params) {
        common(query, CRASH, params);
        return crashes.issues(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** Crash 问题内事件摘要页。 */
    @GetMapping("/crashes/issues/{fingerprint}/events")
    public CrashEventListResponse crashEvents(Authentication auth, @PathVariable String fingerprint,
                                              @RequestParam MultiValueMap<String, String> query,
                                              @ModelAttribute QueryParams params) {
        common(query, CRASH, params);
        return crashes.events(app(auth), fingerprint, query.getFirst("from"), query.getFirst("to"), params);
    }

    /** Crash 单事件详情。 */
    @GetMapping("/crashes/events/{eventId}")
    public CrashEventDetailResponse crashEvent(Authentication auth, @PathVariable String eventId,
                                               @RequestParam MultiValueMap<String, String> query) {
        AgentQueryParameters.validate(query, Set.of());
        return crashes.event(app(auth), eventId);
    }

    /** 卡顿概览。 */
    @GetMapping("/janks/overview")
    public JankOverviewResponse jankOverview(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                             @ModelAttribute QueryParams params) {
        common(query, JANK, params);
        return janks.overview(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 卡顿趋势。 */
    @GetMapping("/janks/trend")
    public JankTrendResponse jankTrend(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                       @ModelAttribute QueryParams params) {
        common(query, union(JANK, Set.of("interval")), params);
        return janks.trend(app(auth), query.getFirst("from"), query.getFirst("to"),
                query.getFirst("interval") == null ? "hour" : query.getFirst("interval"), params);
    }

    /** 卡顿 Issue 页。 */
    @GetMapping("/janks/issues")
    public JankIssueResponse jankIssues(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                        @ModelAttribute QueryParams params) {
        common(query, JANK, params);
        return janks.issues(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 卡顿问题内事件摘要页。 */
    @GetMapping("/janks/issues/{fingerprint}/events")
    public JankEventListResponse jankEvents(Authentication auth, @PathVariable String fingerprint,
                                            @RequestParam MultiValueMap<String, String> query,
                                            @ModelAttribute QueryParams params) {
        common(query, JANK, params);
        return janks.events(app(auth), fingerprint, query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 卡顿单事件详情。 */
    @GetMapping("/janks/events/{eventId}")
    public JankEventDetailResponse jankEvent(Authentication auth, @PathVariable String eventId,
                                             @RequestParam MultiValueMap<String, String> query) {
        AgentQueryParameters.validate(query, Set.of());
        return janks.event(app(auth), eventId);
    }

    /** FPS 指标。 */
    @GetMapping("/jank-metrics/fps")
    public FpsMetricsResponse fps(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                  @ModelAttribute QueryParams params) {
        common(query, METRIC, params);
        return jankMetrics.fps(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 设备日挂起率。 */
    @GetMapping("/jank-metrics/suspension-rate")
    public SuspensionRateResponse suspensionRate(Authentication auth,
                                                 @RequestParam MultiValueMap<String, String> query,
                                                 @ModelAttribute QueryParams params) {
        common(query, METRIC, params);
        return jankMetrics.suspensionRate(app(auth), query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 卡顿指标趋势。 */
    @GetMapping("/jank-metrics/trend")
    public MetricTrendResponse metricTrend(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                           @ModelAttribute QueryParams params) {
        common(query, union(METRIC, Set.of("metric", "interval")), params);
        return jankMetrics.trend(app(auth), query.getFirst("metric"), query.getFirst("interval"),
                query.getFirst("from"), query.getFirst("to"), params);
    }

    /** 卡顿指标白名单维度。 */
    @GetMapping("/jank-metrics/dimensions")
    public MetricDimensionsResponse dimensions(Authentication auth,
                                               @RequestParam MultiValueMap<String, String> query,
                                               @ModelAttribute QueryParams params) {
        common(query, union(METRIC, Set.of("metric", "dimension")), params);
        return jankMetrics.dimensions(app(auth), query.getFirst("metric"), query.getFirst("dimension"),
                query.getFirst("from"), query.getFirst("to"), params);
    }

    /** PSS/VSS/Java 堆概览。 */
    @GetMapping("/memory-metrics/summary")
    public MemoryMetricsSummaryResponse memorySummary(Authentication auth,
                                                      @RequestParam MultiValueMap<String, String> query,
                                                      @ModelAttribute QueryParams params) {
        common(query, MEMORY, params);
        return memoryMetrics.summary(app(auth), query.getFirst("from"), query.getFirst("to"), memory(query, params));
    }

    /** 单一内存指标趋势。 */
    @GetMapping("/memory-metrics/trend")
    public MemoryTrendResponse memoryTrend(Authentication auth, @RequestParam MultiValueMap<String, String> query,
                                           @ModelAttribute QueryParams params) {
        common(query, union(MEMORY, Set.of("metric", "interval")), params);
        return memoryMetrics.trend(app(auth), query.getFirst("metric"), query.getFirst("interval"),
                query.getFirst("from"), query.getFirst("to"), memory(query, params));
    }

    /** SDK 内存异常问题页。 */
    @GetMapping("/memory-leaks/issues")
    public MemoryLeakIssuesResponse memoryLeakIssues(Authentication auth,
                                                    @RequestParam MultiValueMap<String, String> query) {
        AgentQueryParameters.validate(query, union(LEAK, Set.of("page", "pageSize", "sort", "order")));
        return memoryLeaks.issues(app(auth), query.toSingleValueMap());
    }

    /** SDK 内存异常趋势。 */
    @GetMapping("/memory-leaks/trend")
    public MemoryLeakTrendResponse memoryLeakTrend(Authentication auth,
                                                  @RequestParam MultiValueMap<String, String> query) {
        AgentQueryParameters.validate(query, union(LEAK, Set.of("interval")));
        return memoryLeaks.trend(app(auth), query.toSingleValueMap());
    }

    private static final Set<String> LEAK = Set.of("from", "to", "appVersion", "deviceModel", "processName",
            "scene", "manufacturer", "sdkInt", "dumpReason", "anonymousDeviceId", "signature", "keyword");

    /** 每次路由都以已认证 Token 的应用作为唯一作用域。 */
    private UUID app(Authentication authentication) {
        return ((AuthenticatedQueryToken) authentication.getPrincipal()).appId();
    }

    private void common(MultiValueMap<String, String> query, Set<String> allowed, QueryParams params) {
        AgentQueryParameters.validate(query, allowed);
        AgentQueryParameters.limit(params);
    }

    private MemoryMetricQuery memory(MultiValueMap<String, String> query, QueryParams params) {
        return new MemoryMetricQuery(params.getAppVersion(), params.getOsVersion(), params.getDeviceModel(),
                query.getFirst("processName"), params.getScene(),
                AgentQueryParameters.foreground(query.getFirst("foreground")),
                params.getLimit(), params.getTimeoutMs());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }
}
