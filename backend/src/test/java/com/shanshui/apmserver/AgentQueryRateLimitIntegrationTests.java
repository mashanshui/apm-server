package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 直接 HTTP 与 MCP 共用后端额度，日志不记录 Bearer 明文。 */
@SpringBootTest(properties = {"apm.agent.query.enabled=true", "apm.agent.limits.token-per-minute=2",
        "apm.agent.limits.invalid-per-ip-per-minute=2"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AgentQueryRateLimitIntegrationTests extends AppIngestApiTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AppQueryTokenRepository tokens;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
        resetAppCredential("com.example.agentlimit");
    }

    @Test
    void directRequestsCannotBypassTokenQuotaAndLogsOmitSecret(CapturedOutput output) throws Exception {
        String secret = token();
        for (int index = 0; index < 2; index++) {
            mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                            .header("Authorization", "Bearer " + secret))
                    .andExpect(status().isOk());
        }
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("AGENT_RATE_LIMITED"));
        assertTrue(!output.getOut().contains(secret));
    }

    private String token() throws Exception {
        MvcResult login = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession owner = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/v1/apps/" + appId() + "/query-tokens")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"limit-test\"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).path("token").asText();
    }
}
