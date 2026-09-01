package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.AppMember;
import com.shanshui.apmserver.domain.AppMemberId;
import com.shanshui.apmserver.domain.AppRole;
import com.shanshui.apmserver.domain.AppUser;
import com.shanshui.apmserver.domain.UserStatus;
import com.shanshui.apmserver.repository.AppIngestCredentialRepository;
import com.shanshui.apmserver.repository.AppMemberRepository;
import com.shanshui.apmserver.repository.AppUserRepository;
import com.shanshui.apmserver.repository.ApmAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApmAppRepository appRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AppMemberRepository memberRepository;

    @Autowired
    private AppIngestCredentialRepository credentialRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearApps() {
        memberRepository.deleteAll();
        credentialRepository.deleteAll();
        appRepository.deleteAll();
    }

    @Test
    void createsListsAndUpdatesAppUsingOnlyPackageName() throws Exception {
        MockHttpSession session = login();
        UUID appId = createApp(session, "com.example.mobile");

        mockMvc.perform(get("/api/v1/apps").param("query", "mobile").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].appId").value(appId.toString()))
                .andExpect(jsonPath("$[0].name").value("com.example.mobile"))
                .andExpect(jsonPath("$[0].packageName").value("com.example.mobile"))
                .andExpect(jsonPath("$[0].appKey").doesNotExist())
                .andExpect(jsonPath("$[0].keyDigest").doesNotExist());

        mockMvc.perform(patch("/api/v1/apps/{appId}", appId)
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"移动 APM 生产\",\"description\":\"已更新\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(appId.toString()))
                .andExpect(jsonPath("$.name").value("移动 APM 生产"))
                .andExpect(jsonPath("$.packageName").value("com.example.mobile"));
    }

    @Test
    void createsAppWithNameAndDescriptionWhilePackageNameRemainsOnlyRequiredField() throws Exception {
        MockHttpSession session = login();

        MvcResult result = postApp(session,
                "{\"name\":\"移动 APM\",\"description\":\"用于创建接口测试\","
                        + "\"packageName\":\"com.example.named\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("移动 APM"))
                .andExpect(jsonPath("$.description").value("用于创建接口测试"))
                .andExpect(jsonPath("$.packageName").value("com.example.named"))
                .andExpect(jsonPath("$.appKey").doesNotExist())
                .andReturn();

        UUID appId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("appId").asText());
        mockMvc.perform(get("/api/v1/apps/{appId}", appId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("移动 APM"))
                .andExpect(jsonPath("$.description").value("用于创建接口测试"));
    }

    @Test
    void defaultsOptionalCreateFieldsAndRejectsInvalidOptionalValues() throws Exception {
        MockHttpSession session = login();
        postApp(session, "{\"name\":\"  \",\"description\":\"  \","
                        + "\"packageName\":\"com.example.defaults\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("com.example.defaults"))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()));

        postApp(session, "{\"name\":\"" + "n".repeat(101) + "\","
                        + "\"packageName\":\"com.example.longname\"}")
                .andExpect(status().isBadRequest());
        postApp(session, "{\"description\":\"" + "d".repeat(501) + "\","
                        + "\"packageName\":\"com.example.longdescription\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatesUuidV4AndRejectsDuplicatePackageNameAndInvisibleApp() throws Exception {
        MockHttpSession session = login();
        UUID appId = createApp(session, "com.example.mobile");
        assertEquals(4, appId.version());

        mockMvc.perform(post("/api/v1/apps")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageName\":\"com.example.mobile\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_NAME_CONFLICT"));

        UUID missingAppId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/apps/{appId}", missingAppId).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidPackageNameAndLegacyCreateFields() throws Exception {
        MockHttpSession session = login();
        postApp(session, "{\"packageName\":\"com.Example.mobile\"}")
                .andExpect(status().isBadRequest());
        postApp(session, "{\"packageName\":\"example\"}")
                .andExpect(status().isBadRequest());
        postApp(session, "{\"appId\":\"legacy\",\"packageName\":\"com.example.legacy\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAppEditFromDeveloperRole() throws Exception {
        MockHttpSession ownerSession = login();
        UUID appId = createApp(ownerSession, "com.example.mobile");

        MockHttpSession developerSession = createMemberAndLogin(appId, AppRole.DEVELOPER, "developer");
        mockMvc.perform(patch("/api/v1/apps/{appId}", appId)
                        .session(developerSession)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权修改\",\"description\":\"\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void ownerCanReadCredentialButDeveloperCannotAndResponseIsNotCached() throws Exception {
        MockHttpSession ownerSession = login();
        UUID appId = createApp(ownerSession, "com.example.mobile");

        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.appId").value(appId.toString()))
                .andExpect(jsonPath("$.packageName").value("com.example.mobile"))
                .andExpect(jsonPath("$.appKey").value(matchesPattern("apm_ak_[A-Za-z0-9_-]{43}")));

        MockHttpSession developerSession = createMemberAndLogin(appId, AppRole.DEVELOPER, "credential-developer");
        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(developerSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.appKey").doesNotExist());
    }

    @Test
    void credentialPermissionsCoverAdminViewerNonMemberAndAnonymous() throws Exception {
        MockHttpSession ownerSession = login();
        UUID appId = createApp(ownerSession, "com.example.mobile");

        MockHttpSession adminSession = createMemberAndLogin(appId, AppRole.ADMIN, "admin");
        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appKey").exists());

        MockHttpSession viewerSession = createMemberAndLogin(appId, AppRole.VIEWER, "viewer");
        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(viewerSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MockHttpSession nonMemberSession = createMemberAndLogin(null, null, "nonmember");
        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(nonMemberSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void credentialRemainsStableAndDifferentAppsReceiveDifferentKeys() throws Exception {
        MockHttpSession session = login();
        UUID firstAppId = createApp(session, "com.example.mobile");
        UUID secondAppId = createApp(session, "com.example.mobile.two");

        String firstKey = credential(session, firstAppId);
        String secondRead = credential(session, firstAppId);
        String otherKey = credential(session, secondAppId);
        assertEquals(firstKey, secondRead);
        assertNotEquals(firstKey, otherKey);
        assertEquals(2, credentialRepository.count());
    }

    @Test
    void rejectsImmutableAndUnknownAppUpdateFieldsAndOldRoutes() throws Exception {
        MockHttpSession session = login();
        UUID appId = createApp(session, "com.example.mobile");

        mockMvc.perform(patch("/api/v1/apps/{appId}", appId)
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"应用\",\"description\":null,\"packageName\":\"com.other.app\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/apps/{appId}", appId)
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"应用\",\"unknown\":\"value\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/apps/{appId}", appId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageName").value("com.example.mobile"));

        mockMvc.perform(get("/api/v1/projects/{appId}", appId).session(session))
                .andExpect(status().isNotFound());
    }

    private ResultActions postApp(MockHttpSession session, String content) throws Exception {
        return mockMvc.perform(post("/api/v1/apps")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content));
    }

    private UUID createApp(MockHttpSession session, String packageName) throws Exception {
        MvcResult result = postApp(session, "{\"packageName\":\"" + packageName + "\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/apps/")))
                .andExpect(jsonPath("$.packageName").value(packageName))
                .andExpect(jsonPath("$.name").value(packageName))
                .andExpect(jsonPath("$.appKey").doesNotExist())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("appId").asText());
    }

    private String credential(MockHttpSession session, UUID appId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/apps/{appId}/ingest-credential", appId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("appKey").asText();
    }

    private MockHttpSession login() throws Exception {
        return login("test@example.com", "Test-password-123!");
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession createMemberAndLogin(UUID appId, AppRole role, String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        String password = "Member-password-123!";
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        userRepository.save(new AppUser(userId, email, prefix,
                passwordEncoder.encode(password), UserStatus.ACTIVE, now, now));
        if (appId != null && role != null) {
            memberRepository.save(new AppMember(new AppMemberId(appId, userId), role, now));
        }
        return login(email, password);
    }
}
