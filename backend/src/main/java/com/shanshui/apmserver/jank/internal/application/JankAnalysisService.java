package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.api.JankIngestConfiguration;
import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankCallTreeNode;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.jank.api.JankSampleSlice;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将点采样转换为有限覆盖的估算调用树。这里不把样本数量乘以间隔当成连续执行，
 * 也不把估算叶子耗时命名为 CPU self time。
 */
@Service
public class JankAnalysisService {

    private final JankIngestConfiguration properties;

    public JankAnalysisService(JankIngestConfiguration properties) {
        this.properties = properties;
    }

    public JankAnalysis analyze(JankPayload payload) {
        long duration = payload.messageDurationNs();
        long interval = payload.samplingIntervalNs();
        List<JankSample> samples = new ArrayList<>(payload.samples());
        samples.sort(Comparator.comparingLong(JankSample::offsetNs));

        List<JankSampleSlice> slices = new ArrayList<>();
        MutableNode root = new MutableNode("<root>", "<message>");
        long covered = 0L;
        long cursor = 0L;
        long maxContinuousGap = safeMultiply(interval, 2L);
        for (int i = 0; i < samples.size(); i++) {
            JankSample sample = samples.get(i);
            long start = Math.max(0L, sample.offsetNs());
            if (start > cursor) {
                addSlice(slices, cursor, start, null, false);
            }
            long end;
            if (i + 1 < samples.size()) {
                long next = Math.max(start, samples.get(i + 1).offsetNs());
                end = Math.min(duration, next);
                if (next - start > maxContinuousGap) {
                    end = Math.min(duration, start + interval);
                }
            } else {
                end = Math.min(duration, start + interval);
            }
            if (end > start) {
                addSlice(slices, start, end, sample.stackId(), true);
                long part = end - start;
                covered += part;
                addPath(root, payload.stackDictionary().get(sample.stackId()), part);
                cursor = Math.max(cursor, end);
            }
            if (i + 1 < samples.size()) {
                long next = Math.min(duration, Math.max(start, samples.get(i + 1).offsetNs()));
                if (next > cursor) {
                    addSlice(slices, cursor, next, null, false);
                    cursor = next;
                }
            }
        }
        if (cursor < duration) {
            addSlice(slices, cursor, duration, null, false);
        }
        NodeResult tree = root.toResult();
        long unattributed = tree.unattributedDurationNs();
        return new JankAnalysis(
                duration,
                covered,
                unattributed,
                covered,
                Math.max(0L, duration - covered),
                List.copyOf(slices),
                tree.children(),
                copyDictionary(payload.stackDictionary()),
                payload.expectedSampleCount(),
                payload.parsedSampleCount(),
                payload.missingSampleCount(),
                payload.algorithmVersion(),
                List.of());
    }

    private void addPath(MutableNode root, List<StackFrame> frames, long duration) {
        if (frames == null || frames.isEmpty()) {
            root.durationNs += duration;
            return;
        }
        MutableNode current = root;
        current.durationNs += duration;
        int depth = 0;
        for (StackFrame frame : frames) {
            if (frame == null || frame.className() == null || frame.methodName() == null
                    || depth++ >= properties.getMaxJankStackDepth()) {
                break;
            }
            String key = frame.className() + "#" + frame.methodName();
            current = current.children.computeIfAbsent(key,
                    ignored -> new MutableNode(frame.className(), frame.methodName()));
            current.durationNs += duration;
        }
    }

    private void addSlice(List<JankSampleSlice> slices, long start, long end, String stackId, boolean covered) {
        if (end > start) {
            slices.add(new JankSampleSlice(start, end, stackId, covered));
        }
    }

    private Map<String, List<StackFrame>> copyDictionary(Map<String, List<StackFrame>> dictionary) {
        Map<String, List<StackFrame>> copy = new LinkedHashMap<>();
        dictionary.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0 || left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static final class MutableNode {
        private final String className;
        private final String methodName;
        private final Map<String, MutableNode> children = new LinkedHashMap<>();
        private long durationNs;

        private MutableNode(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        private NodeResult toResult() {
            List<NodeResult> childResults = children.values().stream().map(MutableNode::toResult).toList();
            long childDuration = childResults.stream().mapToLong(NodeResult::durationNs).sum();
            long unattributed = Math.max(0L, durationNs - childDuration);
            List<JankCallTreeNode> nodes = childResults.stream().map(NodeResult::node).toList();
            JankCallTreeNode node = new JankCallTreeNode(className, methodName, durationNs, unattributed, nodes);
            return new NodeResult(node, durationNs, unattributed, nodes);
        }
    }

    private record NodeResult(JankCallTreeNode node, long durationNs, long unattributedDurationNs,
                              List<JankCallTreeNode> children) {
    }
}
