package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.internal.domain.MemoryEvent;
import com.shanshui.apmserver.memory.internal.port.MemoryEventRepository;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 内存事件写入唯一协调入口，统一转发到当前存储适配器。 */
@Service
public class MemoryWriteCoordinator {

    private final MemoryEventRepository repository;

    public MemoryWriteCoordinator(MemoryEventRepository repository) {
        this.repository = repository;
    }

    public AppendResult append(UUID appId, List<MemoryEvent> events) {
        return repository.append(appId, List.copyOf(events));
    }
}
