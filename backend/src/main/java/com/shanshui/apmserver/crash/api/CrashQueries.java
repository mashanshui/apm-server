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
    /** 取得同次还原的 mapping 摘要，供已授权分析冻结使用。 */
    CrashAnalysisSnapshot analysisSnapshot(UUID appId, String eventId);
    /** 准备分析前读取指定事件元数据与原始链，不执行 Retrace。 */
    CrashEventDetailResponse rawEvent(UUID appId, String eventId);
}
