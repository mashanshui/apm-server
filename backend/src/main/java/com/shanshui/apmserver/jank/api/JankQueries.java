package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.platform.api.QueryParams;
import java.util.UUID;

/** 网页与 Agent 共用的卡顿个例查询契约。 */
public interface JankQueries {
    JankOverviewResponse overview(UUID appId, String from, String to, QueryParams params);
    JankTrendResponse trend(UUID appId, String from, String to, String interval, QueryParams params);
    JankIssueResponse issues(UUID appId, String from, String to, QueryParams params);
    JankEventListResponse events(UUID appId, String fingerprint, String from, String to, QueryParams params);
    JankEventDetailResponse event(UUID appId, String eventId);
}
