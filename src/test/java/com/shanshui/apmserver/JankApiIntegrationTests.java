package com.shanshui.apmserver;

import com.bytedance.rheatrace.stack.StackMappingResolver;
import com.bytedance.rheatrace.stack.StackParser;
import com.shanshui.apmserver.repository.ApmAppRepository;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.repository.AppMemberRepository;
import com.shanshui.apmserver.web.StackArtifactController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.io.File;
import java.io.InputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(JankApiIntegrationTests.ParserTestConfiguration.class)
class JankApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEventRepository repository;

    @Autowired
    private ApmAppRepository appRepository;

    @Autowired
    private AppMemberRepository memberRepository;

    @BeforeEach
    void clearRepository() {
        repository.clear();
        memberRepository.deleteAll();
        appRepository.deleteAll();
        resetAppCredential("rhea.sample.android");
    }

    @Test
    void authorizedMemberCanQueryOverviewIssueAndDetail() throws Exception {
        String eventId = "fake-1";
        mockMvc.perform(artifactRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("accepted"));
        mockMvc.perform(artifactRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("duplicate"));

        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/janks/overview")
                        .session(session)
                        .param("from", "2026-08-29T00:00:00Z")
                        .param("to", "2026-08-30T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.jankEvents").value(1))
                .andExpect(jsonPath("$.stats.status").value("ok"))
                .andExpect(jsonPath("$.stats.exactMessageDuration.p50Ms").value(20.0));

        MvcResult issueResult = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/janks/issues")
                        .session(session)
                        .param("from", "2026-08-29T00:00:00Z")
                        .param("to", "2026-08-30T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andReturn();
        String fingerprint = new tools.jackson.databind.ObjectMapper().readTree(
                issueResult.getResponse().getContentAsString()).path("issues").get(0).path("fingerprint").asText();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/janks/issues/" + fingerprint + "/events")
                        .session(session)
                        .param("from", "2026-08-29T00:00:00Z")
                        .param("to", "2026-08-30T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventId").value(eventId))
                .andExpect(jsonPath("$.events[0].estimatedDurationMs").isNumber());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/janks/events/" + eventId)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.exactMessageDurationNs").value(20_000_000L))
                .andExpect(jsonPath("$.analysis.expectedSampleCount").value(2))
                .andExpect(jsonPath("$.analysis.parsedSampleCount").value(1))
                .andExpect(jsonPath("$.analysis.missingSampleCount").value(1))
                .andExpect(jsonPath("$.analysis.attemptedSampleCount").doesNotExist())
                .andExpect(jsonPath("$.jank.scene").value("checkout"));
    }

    @Test
    void nonMemberCannotUseJankQueryAndHeadersDoNotOverrideSession() throws Exception {
        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/janks/overview")
                        .session(session)
                        .header("X-App-Id", "other-app")
                        .header("X-User-App-Ids", "other-app"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    @Test
    void crossAppEventIdCannotBeUsedToReadJankDetail() throws Exception {
        String eventId = "demo-jank-2070003140242350";
        mockMvc.perform(artifactRequest())
                .andExpect(status().isOk());
        MockHttpSession session = loginAndCreateDemoApp();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + java.util.UUID.randomUUID() + "/janks/events/" + eventId)
                        .session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    private MockHttpSession loginAndCreateDemoApp() throws Exception {
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder artifactRequest() throws Exception {
        return MockMvcRequestBuilders.post("/ingest/v1/stack-artifacts:parse")
                .header("X-App-Key", appKey())
                .contentType(StackArtifactController.ARTIFACT_MEDIA_TYPE)
                .content(new byte[]{1});
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ParserTestConfiguration {

        @Bean
        @Primary
        StackParser testStackParser() {
            return new StackParser() {
                @Override
                public String parse(InputStream artifactInput, File proguardMapping) {
                    return report();
                }

                @Override
                public String parseWithMappingResolver(InputStream artifactInput,
                                                       StackMappingResolver mappingResolver) {
                    return report();
                }

                private String report() {
                    return """
                            {
                              "schemaVersion":1,"artifactType":"RHEA_STACK_REPORT",
                              "actualStartNs":1000,"actualEndNs":20001000,
                              "sourceManifest":{
                                "schemaVersion":3,"artifactType":"RHEA_JANK","eventId":"fake-1",
                                "occurredAt":1788006588468,"sessionId":"session","anonymousDeviceId":"device",
                                "packageName":"rhea.sample.android","appVersion":"1.0","versionCode":1,
                                "buildId":"build-1","environment":"test","channel":"official",
                                "osVersion":"16","deviceModel":"Pixel","scene":"checkout",
                                "messageStartNs":1000,"messageEndNs":20001000,"thresholdNs":10000000,
                                "minSampleIntervalNs":10000000,"attemptedSampleCount":99,"processId":42
                              },
                              "warnings":[],"threads":[{"tid":42,"estimatedCoveredDurationNs":10000000,
                                "segments":[{"startOffsetNs":0,"estimatedEndOffsetNs":10000000,
                                  "eventType":"kCustom","stack":[{"method":"rhea.sample.android.Main.run(Main.java:10)",
                                  "sourceFile":"Main.java","lineNumber":10}]}],
                                "callTree":[{"method":"rhea.sample.android.Main.run(Main.java:10)",
                                  "sourceFile":"Main.java","lineNumber":10,"estimatedDurationNs":10000000,
                                  "estimatedSelfDurationNs":10000000,"children":[]}]}]
                            }
                            """;
                }
            };
        }
    }
}
