package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.BatchIngestResponse;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.domain.EventBatchRequest;

/** 公共批次接收契约；具体事件由处理器按 eventType 分派。 */
public interface EventIngestionService {

    BatchIngestResponse ingest(AuthenticatedApp app, EventBatchRequest request);
}
