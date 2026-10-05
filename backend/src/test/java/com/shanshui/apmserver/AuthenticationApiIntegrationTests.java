package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.domain.UserStatus;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void rejectsInvalidCredentialsWithoutAccountEnumeration() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    @Test
    void requiresCsrfForLogin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publishesCsrfCookieForAnonymousSessionBootstrap() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void rejectsDisabledAccountWithTheSameCredentialError() throws Exception {
        String email = "disabled-" + UUID.randomUUID() + "@example.com";
        Instant now = Instant.now();
        userRepository.save(new AppUser(UUID.randomUUID(), email, "停用用户",
                passwordEncoder.encode("Test-password-123!"), UserStatus.DISABLED, now, now));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    /** 已有匿名会话成功登录后必须换 ID，并立即提供新的 CSRF Cookie。 */
    @Test
    void rotatesExistingSessionAndRefreshesCsrf() throws Exception {
        // 保存登录前标识，避免只检查同一个可变 Session 对象。
        MockHttpSession anonymous = new MockHttpSession();
        String oldId = anonymous.getId();
        MvcResult bootstrap = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/session"))
                .andReturn();
        var oldCsrf = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .session(anonymous).cookie(oldCsrf)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk()).andExpect(cookie().exists("XSRF-TOKEN")).andReturn();
        assertNotEquals(oldId, login.getRequest().getSession(false).getId());
        assertNotEquals(oldCsrf.getValue(), login.getResponse().getCookie("XSRF-TOKEN").getValue());
    }

    /** 失败登录不得向既有匿名会话保存身份。 */
    @Test
    void leavesFailedLoginSessionAnonymous() throws Exception {
        // 匿名会话用于检查认证上下文没有副作用。
        MockHttpSession anonymous = new MockHttpSession();
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").session(anonymous)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        assertNull(anonymous.getAttribute("SPRING_SECURITY_CONTEXT"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/session").session(anonymous))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoresAndInvalidatesSession() throws Exception {
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").isNotEmpty());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/session").session(session))
                .andExpect(status().isUnauthorized());
    }
}
