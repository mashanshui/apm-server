package com.shanshui.apmserver.jank.internal.port;

import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.telemetry.api.AppendResult;

import java.util.List;
import java.util.Optional;

/** Jank 个例和指标事件的基础存储端口。 */
public interface JankEventRepository {

    AppendResult append(java.util.UUID appId, List<JankStoredSignal> events);

    List<JankStoredSignal> findAll(java.util.UUID appId);

    Optional<JankStoredSignal> findByEventId(java.util.UUID appId, String eventId);
}
