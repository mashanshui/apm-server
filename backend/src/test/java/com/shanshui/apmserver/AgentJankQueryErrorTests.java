package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.platform.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 有效应用 Token 下，领域错误穿过真实 Agent 过滤链保持状态与代码。 */
@SpringBootTest(properties="apm.agent.query.enabled=true")
@AutoConfigureMockMvc
class AgentJankQueryErrorTests extends AppIngestApiTestSupport {
    /** 真实安全链与 MVC 处理器。 */
    @Autowired private MockMvc mvc;
    /** Token 仅从测试成功响应读取，不输出。 */
    @Autowired private ObjectMapper mapper;
    /** 控制故障类型，资源限制本身另由真实数据库专项验证。 */
    @MockitoBean private JankAggregationRepository aggregation;
    /** 为当前测试建立独立应用凭据。 */
    @BeforeEach void setUp() { resetAppCredential("com.example.agentqueryerror"); }
    /** 400/408/422/503 不得改为成功、空数据或通用内部错误。 */
    @Test void preservesQueryFailures() throws Exception {
        var login=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}")).andExpect(status().isOk()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        var created=mvc.perform(post("/api/v1/apps/"+appId()+"/query-tokens").session(session).with(csrf())
                .contentType("application/json").content("{\"name\":\"错误传播测试\"}")).andExpect(status().isCreated()).andReturn();
        String token=mapper.readTree(created.getResponse().getContentAsString()).path("token").asText();
        for(int code:java.util.List.of(400,408,422,503)) {
            String error=switch(code) { case 400 -> "INVALID_CURSOR"; case 408 -> "QUERY_TIMEOUT"; case 422 -> "QUERY_RESOURCE_LIMIT"; default -> "EVENT_STORE_UNAVAILABLE"; };
            RuntimeException failure=code==503?new EventStoreUnavailableException():new QueryValidationException(error,"受控失败",code);
            doThrow(failure).when(aggregation).overview(any());
            mvc.perform(get("/api/agent/v1/janks/overview").header("Authorization","Bearer "+token))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(error));
        }
    }
}
