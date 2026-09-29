package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立开关关闭查询和新 Token 发放，网页与撤销管理仍可运行。 */
@SpringBootTest(properties = "apm.agent.query.enabled=false")
@AutoConfigureMockMvc
class AgentQueryDisabledIntegrationTests extends AppIngestApiTestSupport {

    @Autowired private MockMvc mvc;

    /** 为当前测试建立应用，避免依赖其他测试的执行顺序。 */
    @BeforeEach
    void setUp() {
        resetAppCredential("com.example.agentdisabled");
    }

    /** 外部入口不接受已存在 Token；管理列表仍由 Session 访问。 */
    @Test
    void queryDisabledButManagementRemainsAvailable() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer placeholder"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_QUERY_DISABLED"));
        MvcResult login = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession owner = (MockHttpSession) login.getRequest().getSession(false);
        String path = "/api/v1/apps/" + appId() + "/query-tokens";
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(path).session(owner)).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.post(path).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"disabled\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_QUERY_DISABLED"));
        String ingest = "{\"requestId\":\"disabled-query-ingest\",\"events\":[{\"schemaVersion\":2,"
                + "\"eventId\":\"disabled-query-start\",\"eventType\":\"app_start\","
                + "\"occurredAt\":" + System.currentTimeMillis() + ",\"sessionId\":\"disabled-query-session\","
                + "\"processId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"anonymousDeviceId\":\"disabled-query-device\",\"packageName\":\"com.example.agentdisabled\","
                + "\"appVersion\":\"1.0\",\"versionCode\":1,\"buildId\":\"build-1\","
                + "\"environment\":\"test\",\"channel\":\"local\",\"osVersion\":\"16\","
                + "\"deviceModel\":\"Pixel-8\"}]}";
        mvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", appKey())
                        .contentType(MediaType.APPLICATION_JSON).content(ingest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
    }
}
