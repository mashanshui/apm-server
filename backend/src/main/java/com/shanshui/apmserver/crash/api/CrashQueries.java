package com.shanshui.apmserver.crash.api;

import com.shanshui.apmserver.platform.api.QueryParams;
import java.util.UUID;

/** 网页与 Agent 共用的 Crash 只读查询契约，授权由各自入口执行。 */
public interface CrashQueries {
    CrashOverviewResponse overview(UUID appId, String from, String to, QueryParams params);
    CrashTrendResponse trend(UUID appId, String from, String to, String interval, QueryParams params);
    CrashIssueResponse issues(UUID appId, String from, String to, QueryParams params);
    CrashEventListResponse events(UUID appId, String fingerprint, String from, String to, QueryParams params);
    CrashEventDetailResponse event(UUID appId, String eventId);
}
