package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.application.AppManagementService;
import com.shanshui.apmserver.identity.internal.domain.AppCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 19 个只读入口与网页查询复用业务结果，并保持 Token 应用作用域。 */
@SpringBootTest(properties = "apm.agent.query.enabled=true")
@AutoConfigureMockMvc
class AgentQueryApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AppQueryTokenRepository tokens;
    @Autowired private AppManagementService apps;
    @Autowired private AppUserRepository users;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
        resetAppCredential("com.example.agentquery");
    }

    @Test
    void allReadRoutesMatchSessionQueryContract() throws Exception {
        MockHttpSession owner = login();
        String secret = token(owner);
        String range = "from=2026-09-27T00:00:00Z&to=2026-09-28T00:00:00Z";
        List<String> routes = List.of(
                "/crashes/overview", "/crashes/trend?interval=hour", "/crashes/issues",
                "/crashes/issues/fp-unknown/events", "/crashes/events/unknown",
                "/janks/overview", "/janks/trend?interval=hour", "/janks/issues",
                "/janks/issues/fp-unknown/events", "/janks/events/unknown",
                "/jank-metrics/fps", "/jank-metrics/suspension-rate",
                "/jank-metrics/trend?metric=fps&interval=hour",
                "/jank-metrics/dimensions?metric=fps&dimension=scene",
                "/memory-metrics/summary", "/memory-metrics/trend?metric=pss&interval=hour",
                "/memory-leaks/issues", "/memory-leaks/trend?interval=hour");
        for (String route : routes) {
            String suffix = route.substring(0, route.indexOf('?') < 0 ? route.length() : route.indexOf('?'));
            String existing = route.contains("?") ? route.substring(route.indexOf('?') + 1) + "&" : "";
            String query = suffix.endsWith("/events/unknown") ? "" : "?" + existing + range;
            MvcResult web = mvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + suffix + query)
                            .session(owner)).andReturn();
            MvcResult agent = mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1" + suffix + query)
                            .header("Authorization", "Bearer " + secret)).andReturn();
            assertEquals(web.getResponse().getStatus(), agent.getResponse().getStatus(), route);
            var webBody = mapper.readTree(web.getResponse().getContentAsString());
            var agentBody = mapper.readTree(agent.getResponse().getContentAsString());
            if (web.getResponse().getStatus() >= 400) {
                assertEquals(webBody.path("code"), agentBody.path("code"), route);
                assertEquals(webBody.path("message"), agentBody.path("message"), route);
            } else {
                assertEquals(webBody, agentBody, route);
            }
        }
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(appId().toString()));
    }

    @Test
    void rejectsCallerAppIdUnknownParametersAndNonBearerCredentials() throws Exception {
        MockHttpSession owner = login();
        String secret = token(owner);
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/crashes/overview?appId=" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application?appId=" + appId())
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/janks/issues?sql=SELECT%201")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/memory-metrics/summary?limit=101")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/crashes/overview").session(owner))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/crashes/overview").header("X-App-Key", appKey()))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret).header("X-App-Key", appKey()))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret)
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/agent/v1/crashes/overview")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isMethodNotAllowed());
        MvcResult withCookie = mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .session(owner).header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk()).andReturn();
        assertTrue(withCookie.getResponse().getContentAsString().contains(appId().toString()));
    }

    @Test
    void tokenForOneApplicationCannotReadAnotherApplicationsEventOrFingerprint() throws Exception {
        MockHttpSession owner = login();
        String secret = token(owner);
        var user = users.findByEmailNormalized("test@example.com").orElseThrow();
        var other = apps.create(user.getId(), new AppCreateRequest("com.example.other"));
        String otherKey = apps.getIngestCredential(user.getId(), other.appId()).appKey();
        String eventId = "crash-other-" + System.nanoTime();
        var event = CrashTestSupport.event(eventId, "crash", "session-other", "device-other", "3.2.0",
                CrashTestSupport.nowMillis(),
                CrashTestSupport.crash("java.lang.IllegalStateException", "other", 1, "Other"));
        ObjectNode batch = (ObjectNode) mapper.valueToTree(CrashTestSupport.batch(List.of(event)));
        ((ObjectNode) batch.path("events").get(0)).put("packageName", "com.example.other");
        mvc.perform(MockMvcRequestBuilders.post("/ingest/v1/batches")
                        .header("X-App-Key", otherKey).header("X-Schema-Version", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        String from = Instant.now().minusSeconds(60).toString();
        String to = Instant.now().plusSeconds(60).toString();
        MvcResult otherIssues = mvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + other.appId()
                        + "/crashes/issues").session(owner).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(1)).andReturn();
        String fingerprint = mapper.readTree(otherIssues.getResponse().getContentAsString())
                .path("issues").get(0).path("fingerprint").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/crashes/events/" + eventId)
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/crashes/issues/" + fingerprint + "/events")
                        .header("Authorization", "Bearer " + secret).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    /** 相同静态卡顿数据中，页大小只影响列表，网页与 Agent 统计逐字段一致。 */
    @Test void jankStatisticsAndCursorPagesMatchWithRealData() throws Exception {
        MockHttpSession owner=login();
        String secret=token(owner);
        var fixture=mapper.readValue(getClass().getResourceAsStream("/fixtures/jank-dataset.json"),com.shanshui.apmserver.ingest.api.EventBatchRequest.class);
        JankTestSupport.appendFixture(appId(),jankEvents,fixture);
        String range="from=2026-08-15T09:59:00Z&to=2026-08-16T00:02:00Z";
        for(int limit:List.of(1,50)) {
            var web=mvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/"+appId()+"/janks/overview?"+range+"&limit="+limit).session(owner)).andExpect(status().isOk()).andReturn();
            var agent=mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/janks/overview?"+range+"&limit="+limit).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.stats.jankEvents").value(3)).andReturn();
            assertEquals(mapper.readTree(web.getResponse().getContentAsString()),mapper.readTree(agent.getResponse().getContentAsString()));
        }
        var page=mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/janks/issues?"+range+"&limit=1").header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andReturn();
        String cursor=mapper.readTree(page.getResponse().getContentAsString()).path("nextCursor").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/janks/issues?"+range+"&limit=1&cursor="+cursor).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.issues.length()").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/janks/issues?limit=101").header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    /** 直接装载固定事实，不改变客户端 ZIP 入口。 */
    @Autowired private com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository jankEvents;

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private String token(MockHttpSession owner) throws Exception {
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/v1/apps/" + appId() + "/query-tokens")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"agent-api-test\"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("token").asText();
    }
}
