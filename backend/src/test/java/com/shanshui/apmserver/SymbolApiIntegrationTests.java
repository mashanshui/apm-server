package com.shanshui.apmserver;

import com.shanshui.apmserver.identity.internal.domain.AppMember;
import com.shanshui.apmserver.identity.internal.domain.AppMemberId;
import com.shanshui.apmserver.identity.internal.domain.AppRole;
import com.shanshui.apmserver.identity.internal.domain.AppUser;
import com.shanshui.apmserver.identity.internal.domain.UserStatus;
import com.shanshui.apmserver.identity.internal.persistence.AppIngestCredentialRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppMemberRepository;
import com.shanshui.apmserver.identity.internal.persistence.AppUserRepository;
import com.shanshui.apmserver.identity.internal.persistence.ApmAppRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolAuditRepository;
import com.shanshui.apmserver.symbol.internal.persistence.SymbolFileRepository;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证符号表网页接口的权限、幂等上传、冲突确认和版本条件更新。 */
@SpringBootTest(properties = "apm.symbol.max-file-bytes=32")
@AutoConfigureMockMvc
class SymbolApiIntegrationTests extends AppIngestApiTestSupport {

    /** 供接口测试使用的最小合法 mapping。 */
    private static final byte[] FIRST_MAPPING = "com.example.App -> a:\n"
            .getBytes(StandardCharsets.UTF_8);
    /** 替换接口使用的第二份合法 mapping。 */
    private static final byte[] SECOND_MAPPING = "com.example.App -> b:\n"
            .getBytes(StandardCharsets.UTF_8);

    /** 网页接口测试客户端。 */
    @Autowired
    private MockMvc mockMvc;
    /** 应用用户仓库。 */
    @Autowired
    private AppUserRepository userRepository;
    /** 应用成员仓库。 */
    @Autowired
    private AppMemberRepository memberRepository;
    /** 应用凭据仓库。 */
    @Autowired
    private AppIngestCredentialRepository credentialRepository;
    /** 应用仓库。 */
    @Autowired
    private ApmAppRepository appRepository;
    /** 用户密码编码器。 */
    @Autowired
    private PasswordEncoder passwordEncoder;
    /** 符号表当前版本仓库。 */
    @Autowired
    private SymbolFileRepository symbolFileRepository;
    /** 符号表审计仓库。 */
    @Autowired
    private SymbolAuditRepository symbolAuditRepository;
    /** 符号文件卷清理器。 */
    @Autowired
    private SymbolFileStore symbolFileStore;
    /** 响应 JSON 读取器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 每个测试使用全新的应用并清理数据库和受控文件。 */
    @BeforeEach
    void resetManagementData() {
        symbolAuditRepository.deleteAll();
        symbolFileRepository.deleteAll();
        memberRepository.deleteAll();
        credentialRepository.deleteAll();
        appRepository.deleteAll();
        symbolFileStore.collectOrphans(Set.of());
        resetAppCredential("com.example.symbol");
    }

    /** 首次上传、相同字节幂等、不同字节冲突和确认替换应保持版本语义。 */
    @Test
    void uploadsIdempotentlyAndReplacesWithExpectedRevision() throws Exception {
        MockHttpSession session = login("test@example.com", "Test-password-123!");

        MvcResult created = mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-1", "mapping.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.buildId").value("release-1"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn();
        String symbolId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("symbolId").asText();

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-1", "retry.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbolId").value(symbolId))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(uploadRequest(appId(), SECOND_MAPPING, "release-1", "new.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYMBOL_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].symbolId").value(symbolId))
                .andExpect(jsonPath("$.errors[0].sha256").isString());

        mockMvc.perform(replaceRequest(appId(), symbolId, SECOND_MAPPING, 1, "new.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        mockMvc.perform(replaceRequest(appId(), symbolId, FIRST_MAPPING, 1, "stale.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYMBOL_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].revision").value(2));

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "release-2", "second.txt")
                        .session(session).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated());

        MvcResult firstPage = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .param("limit", "1")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String nextCursor = objectMapper.readTree(firstPage.getResponse().getContentAsString())
                .get("nextCursor").asText();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .param("cursor", nextCursor).param("limit", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    /** 普通成员只能查询，App Key 和缺少 CSRF 均不得写入符号表。 */
    @Test
    void enforcesViewOnlyRoleCsrfAndSessionBoundaries() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");
        MockHttpSession viewer = createMemberAndLogin(appId(), AppRole.VIEWER, "symbol-viewer");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + appId() + "/symbols")
                        .session(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "viewer-build", "mapping.txt")
                        .session(viewer).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "missing-csrf", "mapping.txt")
                        .session(owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "app-key", "mapping.txt")
                        .header("X-App-Key", appKey())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/apps/" + UUID.randomUUID() + "/symbols")
                        .session(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
    }

    /** 非法 buildId、空 mapping 和超限文件在写入数据库前被拒绝。 */
    @Test
    void rejectsInvalidBuildIdEmptyMappingAndOversizedFile() throws Exception {
        MockHttpSession owner = login("test@example.com", "Test-password-123!");

        mockMvc.perform(uploadRequest(appId(), FIRST_MAPPING, "../escape", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BUILD_ID"));
        mockMvc.perform(uploadRequest(appId(), new byte[0], "empty", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MAPPING"));
        mockMvc.perform(uploadRequest(appId(), new byte[40], "too-large", "mapping.txt")
                        .session(owner).with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("MAPPING_TOO_LARGE"));
    }

    /** 创建一个 multipart 首次上传请求。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadRequest(
            UUID appId, byte[] content, String buildId, String filename) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/{appId}/symbols", appId)
                .file(new MockMultipartFile("buildId", "", MediaType.TEXT_PLAIN_VALUE,
                        buildId.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE, content));
    }

    /** 创建一个 multipart 确认替换请求。 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder replaceRequest(
            UUID appId, String symbolId, byte[] content, int expectedRevision, String filename) {
        return MockMvcRequestBuilders.multipart("/api/v1/apps/{appId}/symbols/{symbolId}", appId, symbolId)
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                })
                .file(new MockMultipartFile("expectedRevision", "", MediaType.TEXT_PLAIN_VALUE,
                        Integer.toString(expectedRevision).getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE, content));
    }

    /** 使用测试管理员登录网页 Session。 */
    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    /** 创建指定角色的应用成员并登录。 */
    private MockHttpSession createMemberAndLogin(UUID appId, AppRole role, String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        String password = "Member-password-123!";
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        userRepository.save(new AppUser(userId, email, prefix, passwordEncoder.encode(password),
                UserStatus.ACTIVE, now, now));
        memberRepository.save(new AppMember(new AppMemberId(appId, userId), role, now));
        return login(email, password);
    }
}
