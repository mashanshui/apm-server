package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.domain.UserStatus;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppQueryTokenRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 网页管理 API 保持 Session、CSRF、应用成员与一次展示边界。 */
@SpringBootTest(properties = "apm.agent.query.enabled=true")
@AutoConfigureMockMvc
class QueryTokenApiIntegrationTests extends AppIngestApiTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private AppQueryTokenRepository tokens;
    @Autowired private AppUserRepository users;
    @Autowired private AppMemberRepository members;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper mapper;

    /** 每个场景创建独立应用，先清理旧 Token 元数据。 */
    @BeforeEach
    void reset() {
        tokens.deleteAll();
        resetAppCredential("com.example.querytoken");
    }

    /** 创建值只在 201 响应出现，响应丢失后列表仍可识别并撤销。 */
    @Test
    void createsListsAndRevokesWithoutRecoveringSecret() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        String path = path(appId());
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post(path).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.metadata.status").value("ACTIVE"))
                .andReturn();
        String secret = mapper.readTree(created.getResponse().getContentAsString()).get("token").asText();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("metadata").get("id").asText();
        assertTrue(secret.startsWith("apm_qt_"));

        // 假设客户端丢失创建响应，GET 只能看到元数据而无法恢复完整值。
        MvcResult listed = mvc.perform(MockMvcRequestBuilders.get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].token").doesNotExist())
                .andReturn();
        assertTrue(!listed.getResponse().getContentAsString().contains(secret));

        mvc.perform(MockMvcRequestBuilders.delete(path + "/" + id).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.delete(path + "/" + id).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.get(path).session(owner))
                .andExpect(jsonPath("$.items[0].status").value("REVOKED"));
    }

    /** 普通成员、跨应用、错误期限、缺少 CSRF 与纯 Bearer 都不可创建。 */
    @Test
    void enforcesRolesCsrfExpiryAndSessionOnlyManagement() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MockHttpSession viewer = memberLogin(appId(), AppRole.VIEWER);
        MockHttpSession developer = memberLogin(appId(), AppRole.DEVELOPER);
        MockHttpSession admin = memberLogin(appId(), AppRole.ADMIN);
        String path = path(appId());
        String body = "{\"name\":\"CI\",\"expiresInDays\":30}";

        for (MockHttpSession forbidden : new MockHttpSession[]{viewer, developer}) {
            mvc.perform(MockMvcRequestBuilders.post(path).session(forbidden)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(MockMvcRequestBuilders.get(path).session(forbidden))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(MockMvcRequestBuilders.post(path).session(admin)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(MockMvcRequestBuilders.post(path).session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", "Bearer apm_qt_placeholder")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (int days : new int[]{0, 1, 31, 366}) {
            mvc.perform(MockMvcRequestBuilders.post(path).session(owner)
                            .with(SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"CI\",\"expiresInDays\":" + days + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_QUERY_TOKEN_EXPIRY"));
        }
        mvc.perform(MockMvcRequestBuilders.get(path(UUID.randomUUID())).session(owner))
                .andExpect(status().isNotFound());
        assertTrue(tokens.count() == 1);
    }

    /** 跨应用撤销 ID 和不存在 ID 返回同一 404。 */
    @Test
    void rejectsCrossApplicationTokenId() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post(path(appId())).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("metadata").get("id").asText();
        mvc.perform(MockMvcRequestBuilders.delete(path(appId()) + "/" + UUID.randomUUID())
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUERY_TOKEN_NOT_FOUND"));
        mvc.perform(MockMvcRequestBuilders.delete(path(UUID.randomUUID()) + "/" + id)
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
    }

    /** 直接 Agent HTTP 每次验证 Token；撤销后旧连接和 Cookie 均不能复用身份。 */
    @Test
    void authenticatesEveryAgentRequestAndRejectsRevocation() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post(path(appId())).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated()).andReturn();
        String secret = mapper.readTree(created.getResponse().getContentAsString()).get("token").asText();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("metadata").get("id").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(appId().toString()))
                .andExpect(jsonPath("$.packageName").value("com.example.querytoken"));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application").session(owner))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("QUERY_TOKEN_INVALID"));
        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application").header("X-App-Key", appKey()))
                .andExpect(status().isUnauthorized());

        mvc.perform(MockMvcRequestBuilders.delete(path(appId()) + "/" + id).session(owner)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        MvcResult invalid = mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .session(owner).header("Authorization", "Bearer " + secret))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("QUERY_TOKEN_INVALID"))
                .andReturn();
        assertTrue(!invalid.getResponse().getContentAsString().contains(secret));
    }

    /** 创建者之后失去应用成员资格时，已有应用凭据仍按其自身到期/撤销状态鉴权。 */
    @Test
    void keepsCredentialIndependentOfCreatorsLaterRole() throws Exception {
        MockHttpSession admin = memberLogin(appId(), AppRole.ADMIN);
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post(path(appId())).session(admin)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"delegated\"}"))
                .andExpect(status().isCreated()).andReturn();
        String secret = mapper.readTree(created.getResponse().getContentAsString()).get("token").asText();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("metadata").get("id").asText();
        UUID creatorId = tokens.findById(UUID.fromString(id)).orElseThrow().getCreatedBy();
        members.deleteById(new AppMemberId(appId(), creatorId));

        mvc.perform(MockMvcRequestBuilders.get("/api/agent/v1/application")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(appId().toString()));
        mvc.perform(MockMvcRequestBuilders.get(path(appId())).session(admin))
                .andExpect(status().isNotFound());
    }

    /** 使用现有登录入口取得真实网页 Session。 */
    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    /** 将指定角色加入当前应用并以该成员登录。 */
    private MockHttpSession memberLogin(UUID appId, AppRole role) throws Exception {
        String email = "query-" + UUID.randomUUID() + "@example.com";
        String password = "Member-password-123!";
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        users.save(new AppUser(userId, email, "Query member", passwords.encode(password),
                UserStatus.ACTIVE, now, now));
        members.save(new AppMember(new AppMemberId(appId, userId), role, now));
        return login(email, password);
    }

    /** 构造不携带查询 Token 的 Session 管理路径。 */
    private String path(UUID appId) { return "/api/v1/apps/" + appId + "/query-tokens"; }
}
