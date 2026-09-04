package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.telemetry.api.StackFrame;

import java.util.List;
import java.util.Map;

/** 服务端根据卡顿采样生成的证据摘要。所有 estimated 字段都明确表示采样估算。 */
public record JankAnalysis(
        long exactMessageDurationNs,
        long estimatedDurationNs,
        long estimatedUnattributedDurationNs,
        long coveredDurationNs,
        long uncoveredDurationNs,
        List<JankSampleSlice> sampleSlices,
        List<JankCallTreeNode> callTree,
        Map<String, List<StackFrame>> stackDictionary,
        int expectedSampleCount,
        int parsedSampleCount,
        int missingSampleCount,
        String algorithmVersion,
        List<String> warnings) {
}
