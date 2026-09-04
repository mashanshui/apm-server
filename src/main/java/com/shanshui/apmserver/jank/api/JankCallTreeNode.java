package com.shanshui.apmserver.jank.api;

import java.util.List;

/** 由采样路径重建的估算调用树。estimatedUnattributedDurationNs 不是 CPU self time。 */
public record JankCallTreeNode(
        String className,
        String methodName,
        long estimatedDurationNs,
        long estimatedUnattributedDurationNs,
        List<JankCallTreeNode> children) {
}
