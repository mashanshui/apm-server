package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.StoredEvent;

import java.util.List;
import java.util.Optional;

public interface EventRepository {

    AppendResult append(java.util.UUID appId, List<StoredEvent> events);

    List<StoredEvent> findAll(java.util.UUID appId);

    Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId);
}
