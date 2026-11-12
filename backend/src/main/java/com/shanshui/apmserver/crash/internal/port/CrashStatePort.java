package com.shanshui.apmserver.crash.internal.port;

import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 新数据集事实与状态边界，不提供旧表或旧状态转换入口。 */
public interface CrashStatePort {
    /** 先状态后事实，重试补齐缺失一侧；返回是否首次保存事实。 */
    boolean accept(CrashFact fact, CrashState initialState);

    /** 只返回与当前事实关联的最新状态，孤立状态不可见。 */
    Optional<CrashState> current(UUID appId, String eventId);

    /** 独立发布同一逻辑版本；首次成功登记由 PostgreSQL 端负责。 */
    void publish(CrashState state);

    /** 直接重放已登记的同版本、同字节载荷，不重新计算。 */
    void replay(String payload);

    /** 按版本先取有限轻量身份，不读取堆栈或诊断。 */
    List<Candidate> candidatePage(UUID appId, String appVersion, Instant from, Instant now,
                                Instant afterTime, String afterId, int limit);

    /** 仅为已经选定来源的有限事件读取当前载荷。 */
    List<CrashWorkItem> payloads(UUID appId, String appVersion, List<Candidate> candidates);

    /** 扫描位置及来源判断只使用轻量元数据。 */
    record Candidate(/** 事件身份。 */ String eventId, /** 原构建。 */ String buildId,
                     /** 排序时间。 */ Instant eventTime, /** 当前状态。 */ String status,
                     /** 上次尝试来源。 */ UUID symbolId, /** 上次修订。 */ Integer revision,
                     /** 临时失败退避。 */ Instant retryAt) {}

    /** 先取最新状态再筛选；只取保留期内当前构建的有界候选。 */
    List<CrashWorkItem> candidates(UUID appId, String buildId, UUID symbolId, int revision,
                                Instant from, Instant now, int limit);
}
