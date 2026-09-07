package com.shanshui.apmserver.crash.internal.port;

import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;

import java.util.List;
import java.util.Optional;

/** Crash 聚合查询和事件详情读取端口。 */
public interface CrashQueryPort {

    List<CrashStoredSignal> findAll(java.util.UUID appId);

    Optional<CrashStoredSignal> findByEventId(java.util.UUID appId, String eventId);
}
