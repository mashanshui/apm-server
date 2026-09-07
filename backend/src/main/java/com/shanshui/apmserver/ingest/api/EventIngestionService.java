package com.shanshui.apmserver.ingest.api;

import com.shanshui.apmserver.ingest.api.BatchIngestResponse;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;

/** 公共批次接收契约；具体事件由处理器按 eventType 分派。 */
public interface EventIngestionService {

    BatchIngestResponse ingest(AuthenticatedApp app, EventBatchRequest request);
}
