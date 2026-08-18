package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.StoredEvent;

import java.util.List;
import java.util.Optional;

public interface EventRepository {

    AppendResult append(String projectId, List<StoredEvent> events);

    List<StoredEvent> findAll(String projectId);

    Optional<StoredEvent> findByEventId(String projectId, String eventId);
}
