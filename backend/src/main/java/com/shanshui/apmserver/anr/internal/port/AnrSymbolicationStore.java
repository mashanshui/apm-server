package com.shanshui.apmserver.anr.internal.port;

import com.shanshui.apmserver.anr.api.AnrJavaView;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 独立于原始事件的 ANR Java 派生视图。 */
public interface AnrSymbolicationStore {
    /** 查询单个分区当前视图。 */
    Optional<AnrJavaView> find(UUID appId, String eventId, String section);

    /** 幂等写入指定版本的单分区视图。 */
    void write(UUID appId, String eventId, Instant occurredAt, String section, long version, AnrJavaView view);
}
