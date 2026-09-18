package com.shanshui.apmserver;

import com.shanshui.apmserver.ingest.internal.protocol.EventSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证公共批次入口对进程实例身份的格式、缺失和旧数字 PID 边界。 */
class ProcessIdentityValidationTests {

    private static final String UUID_V4 = "11111111-1111-4111-8111-111111111111";
    private static final String UPPERCASE_UUID_V4 = "11111111-1111-4111-8111-111111111111".toUpperCase();
    private final EventSchemaValidator validator = new EventSchemaValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsLowerAndUpperCaseUuidV4AndPreservesTheRawValueForBinding() throws Exception {
        assertThat(com.shanshui.apmserver.telemetry.api.ProcessIdentity.isUuidV4(UUID_V4)).isTrue();
        assertThat(com.shanshui.apmserver.telemetry.api.ProcessIdentity.isUuidV4(UPPERCASE_UUID_V4)).isTrue();
        assertThat(objectMapper.readTree(validEventJson(UUID_V4)).path("processId").asText()).isEqualTo(UUID_V4);
        assertThat(objectMapper.readTree(validEventJson(UPPERCASE_UUID_V4)).path("processId").asText()).isEqualTo(UPPERCASE_UUID_V4);
        validator.validateBatch(objectMapper.readTree(batchJson(validEventJson(UPPERCASE_UUID_V4))));
    }

    @Test
    void rejectsMissingBlankNumericV1AndV3ProcessIdsAtTheBatchBoundary() {
        assertInvalid(null, "必须是 UUID v4");
        assertInvalid("\"\"", "必须是 UUID v4");
        assertInvalid("17100", "必须是 UUID v4");
        assertInvalid("\"11111111-1111-3111-8111-111111111111\"", "必须是 UUID v4");
        assertInvalid("\"11111111-1111-4111-7111-111111111111\"", "必须是 UUID v4");
    }

    private void assertInvalid(String processIdJson, String message) {
        String event = rawEventJson(processIdJson);
        assertThatThrownBy(() -> validator.validateBatch(objectMapper.readTree(batchJson(event))))
                .hasMessageContaining(message);
    }

    private String batchJson(String event) {
        return "{\"requestId\":\"identity-test\",\"events\":[" + event + "]}";
    }

    private String validEventJson(String processId) {
        return rawEventJson("\"" + processId + "\"");
    }

    private String rawEventJson(String processIdJson) {
        String processField = processIdJson == null ? "" : ",\"processId\":" + processIdJson;
        return "{\"schemaVersion\":2,\"eventId\":\"identity-event\",\"eventType\":\"app_start\","
                + "\"occurredAt\":1788006588468,\"sessionId\":\"session\"" + processField
                + ",\"anonymousDeviceId\":\"device\",\"packageName\":\"com.example.app\","
                + "\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build\","
                + "\"environment\":\"test\",\"channel\":\"official\",\"osVersion\":\"16\","
                + "\"deviceModel\":\"Pixel\",\"networkType\":\"wifi\"}";
    }
}
