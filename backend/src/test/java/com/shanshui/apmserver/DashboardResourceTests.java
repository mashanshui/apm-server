package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardResourceTests {

    @Test
    void dashboardContainsRequiredVariablesAndPanels() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/grafana/dashboards/jvm-crash.json")) {
            JsonNode dashboard = CrashTestSupport.objectMapper().readTree(input);
            assertTrue(dashboard.get("title").asText().contains("Crash"));
            assertTrue(dashboard.get("panels").size() >= 5);
            String json = dashboard.toString();
            assertTrue(json.contains("app_id"));
            assertTrue(json.contains("app_version"));
            assertTrue(json.contains("fingerprint"));
            assertTrue(json.contains("denominator") || json.contains("分母"));
        }
    }
}
