package com.shanshui.apmserver.memory.internal.domain;

import com.shanshui.apmserver.memory.api.MemoryPeakSummaryPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

/** 内存领域内部保存的事件，公共元数据与内存载荷保持分离。 */
public record MemoryEvent(EventMetadata metadata, MemoryPeakSummaryPayload payload) {

    public java.util.UUID appId() { return metadata.appId(); }

    public String eventId() { return metadata.eventId(); }

    public java.time.Instant occurredAt() { return metadata.occurredAt(); }

    /** 返回事件所属的进程实例 UUID，历史行缺失时保持 null。 */
    public String processId() { return metadata.processId(); }

    public String packageName() { return metadata.packageName(); }

    public String appVersion() { return metadata.appVersion(); }

    public String osVersion() { return metadata.osVersion(); }

    public String deviceModel() { return metadata.deviceModel(); }

    public String processName() { return payload.processName(); }





    public Long pssBytes() { return payload.pssBytes(); }

    public Long vssBytes() { return payload.vssBytes(); }

    public Long javaHeapUsedBytes() { return payload.javaHeapUsedBytes(); }
}
