package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.internal.application.CrashEventValidator;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.jank.api.JankMetricIngestCommand;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventValidator;
import com.shanshui.apmserver.memory.api.MemoryIngestCommand;
import com.shanshui.apmserver.memory.api.MemoryPeak;
import com.shanshui.apmserver.memory.api.MemoryPeakSummaryPayload;
import com.shanshui.apmserver.memory.internal.application.MemoryEventValidator;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** mapping 登记可省略构建，但现有事件入口仍必须提供构建。 */
class EventBuildRequiredTests {
    /** Crash/启动事件拒绝省略、null 和空白构建，完整事件仍正常通过。 */
    @Test
    void requiresBuildForCrashAndAppStart() {
        /** 当前实际校验器和 JSON 转换器。 */
        var mapper = new ObjectMapper();
        var validator = new CrashEventValidator(CrashTestSupport.ingestProperties(), mapper);
        for (String type : List.of("app_start", "crash")) {
            /** 使用既有合法事件，确保拒绝原因来自构建字段。 */
            ObjectNode event = mapper.valueToTree(CrashTestSupport.crashCommand(CrashTestSupport.event(
                    "required-build", type, "session", "device", "3.2.0", Instant.now().toEpochMilli(),
                    "crash".equals(type) ? CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a") : null)));
            assertDoesNotThrow(() -> validator.validate(mapper.treeToValue(event, CrashIngestCommand.class)));
            for (String variant : List.of("absent", "null", "blank")) {
                setMissingBuild(event, variant);
                /** 必须包含构建缺失问题，不能由其他字段偶然触发断言。 */
                var failure = assertThrows(EventValidationException.class,
                        () -> validator.validate(mapper.treeToValue(event, CrashIngestCommand.class)));
                assertTrue(failure.getIssues().stream().anyMatch(issue -> "MISSING_BUILDID".equals(issue.code())));
            }
        }
    }

    /** 两种帧指标沿用构建必填约束，不受默认 mapping 影响。 */
    @Test
    void requiresBuildForBothFrameMetrics() throws Exception {
        /** 与固定数据集使用同一配置，先验证原样事件合法。 */
        var mapper = new ObjectMapper();
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(List.of("suspension-v1", "suspension-v2"));
        var validator = new JankMetricEventValidator(properties, mapper);
        var batch = mapper.readValue(getClass().getResourceAsStream("/fixtures/jank-dataset.json"), EventBatchRequest.class);
        for (var source : batch.events()) {
            if (!List.of("frame_scene_summary", "foreground_suspension_summary").contains(source.eventType())) continue;
            /** 仅改变待验证的构建，不修改事件的其他统计材料。 */
            ObjectNode event = mapper.valueToTree(CrashTestSupport.metricCommand(source));
            assertDoesNotThrow(() -> validator.validate(mapper.treeToValue(event, JankMetricIngestCommand.class)));
            for (String variant : List.of("absent", "null", "blank")) {
                setMissingBuild(event, variant);
                /** 两种事件都必须明确拒绝构建缺失。 */
                var failure = assertThrows(EventValidationException.class,
                        () -> validator.validate(mapper.treeToValue(event, JankMetricIngestCommand.class)));
                assertTrue(failure.getIssues().stream().anyMatch(issue -> "MISSING_BUILDID".equals(issue.code())));
            }
        }
    }

    /** 内存峰值保留原非空构建，不引入缺失值存储表示。 */
    @Test
    void requiresBuildForMemoryPeak() {
        /** 合法采集区间和一个独立峰值。 */
        long now = Instant.now().toEpochMilli();
        var mapper = new ObjectMapper();
        var validator = new MemoryEventValidator(CrashTestSupport.ingestProperties(), mapper);
        ObjectNode event = mapper.valueToTree(new MemoryIngestCommand(2, "memory", "memory_peak_summary", now,
                "session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0", 1, "build-A",
                "test", "official", "16", "device", null, null, null,
                new MemoryPeakSummaryPayload("com.example.app", now - 1, now,
                        new MemoryPeak(1L, now, true, null), null, null)));
        assertDoesNotThrow(() -> validator.validate(mapper.treeToValue(event, MemoryIngestCommand.class)));
        for (String variant : List.of("absent", "null", "blank")) {
            setMissingBuild(event, variant);
            /** 明确检查构建错误，而非仅断言任意异常。 */
            var failure = assertThrows(EventValidationException.class,
                    () -> validator.validate(mapper.treeToValue(event, MemoryIngestCommand.class)));
            assertTrue(failure.getIssues().stream().anyMatch(issue -> "MISSING_BUILDID".equals(issue.code())));
        }
    }

    /** 构造三种缺失表示，均应被事件领域校验拒绝。 */
    private void setMissingBuild(ObjectNode event, String variant) {
        if ("absent".equals(variant)) event.remove("buildId");
        else if ("null".equals(variant)) event.putNull("buildId");
        else event.put("buildId", "   ");
    }
}
