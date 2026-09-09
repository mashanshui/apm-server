package com.shanshui.apmserver.memory.internal.port;

import com.shanshui.apmserver.memory.internal.domain.MemoryEvent;
import com.shanshui.apmserver.telemetry.api.AppendResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 内存采样事件写入和原始读取端口。 */
public interface MemoryEventRepository {

    AppendResult append(UUID appId, List<MemoryEvent> events);

    List<MemoryEvent> findAll(UUID appId);

    Optional<MemoryEvent> findByEventId(UUID appId, String eventId);
}
