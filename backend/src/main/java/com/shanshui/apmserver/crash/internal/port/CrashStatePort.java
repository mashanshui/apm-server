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

    /** 先取最新状态再筛选；只取保留期内当前构建的有界候选。 */
    List<CrashWorkItem> candidates(UUID appId, String buildId, UUID symbolId, int revision,
                                Instant from, Instant now, int limit);
}
