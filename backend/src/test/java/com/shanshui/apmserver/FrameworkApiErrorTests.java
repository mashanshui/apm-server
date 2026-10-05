package com.shanshui.apmserver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC 输入失败、受控字段及框架头回归；真实容器行为另由 HTTP 专项验证。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FrameworkApiErrorTests.FixtureConfiguration.class)
class FrameworkApiErrorTests {
    /** 真实安全链和 MVC 调度。 */
    @Autowired private MockMvc mvc;
    /** 解码统一字段 errors。 */
    @Autowired private ObjectMapper mapper;
    /** 业务入口计数，用于确认失败不会执行写入。 */
    @Autowired private Fixture endpoint;

    /** 真实应用及 Worker 管理入口覆盖解码、类型和必填约束。 */
    @Test
    void mapsRealRouteInputFailuresWithoutBusinessEffects() throws Exception {
        // 独立登录会话只用于本测试请求。
        MockHttpSession session = login();
        String app = UUID.randomUUID().toString();
        expect(mvc.perform(get("/api/v1/apps/not-a-uuid").session(session)), 400, "INVALID_PARAMETER");
        expect(mvc.perform(get("/api/v1/apps/" + app + "/janks/issues?limit=abc").session(session)), 400, "INVALID_PARAMETER");
        expect(write(session, "/api/v1/apps/" + app + "/analysis-workers", "{}"), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        expect(write(session, "/api/v1/apps", "{bad-json"), 400, "INVALID_REQUEST_BODY");
        expect(write(session, "/api/v1/apps", ""), 400, "INVALID_REQUEST_BODY");
        expect(write(session, "/api/v1/apps/" + app + "/analysis-workers", "{\"name\":null}"), 400, "VALIDATION_FAILED");
    }

    /** JSON/parser 和 Bean errors 均不能回显密码、Token、租约、未知字段或提交值。 */
    @Test
    void capsErrorsAndHidesSensitiveSubmittedValues() throws Exception {
        // 输入及声明字段分别受验证，原值只保留在请求内。
        MockHttpSession session = login();
        String secret = "SENSITIVE-password-token-lease";
        String invalid = "{\"values\":[" + String.join(",", java.util.Collections.nCopies(60, "\" \"")) + "]}";
        String body = expect(write(session, "/api/v1/framework-test/validate", invalid), 400, "VALIDATION_FAILED")
                .andReturn().getResponse().getContentAsString();
        assertEquals(50, mapper.readTree(body).path("errors").size());
        for (String payload : List.of("{\"password\":\"" + secret + "\",", "{\"" + secret + "\":\"" + secret + "\"}")) {
            String response = expect(write(session, "/api/v1/apps", payload), 400, "INVALID_REQUEST_BODY")
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains(secret));
        }
        assertEquals(0, endpoint.writes.get());
    }

    /** ModelAttribute 类型失败不是 Bean 约束失败；缺失 query/header/part 都有正文。 */
    @Test
    void distinguishesBindingConstraintsAndMissingItems() throws Exception {
        // 测试入口具有框架声明的必填项，不依赖业务服务。
        MockHttpSession session = login();
        expect(mvc.perform(get("/api/v1/framework-test/bind?limit=abc").session(session)), 400, "INVALID_PARAMETER");
        expect(mvc.perform(get("/api/v1/framework-test/bind?limit=0").session(session)), 400, "VALIDATION_FAILED");
        expect(mvc.perform(get("/api/v1/framework-test/required").session(session)), 400, "INVALID_PARAMETER");
        expect(mvc.perform(get("/api/v1/framework-test/header").session(session)), 400, "INVALID_PARAMETER");
        expect(mvc.perform(multipart("/api/v1/framework-test/upload").session(session).with(csrf())), 400, "INVALID_PARAMETER");
        expect(mvc.perform(get("/api/v1/framework-test/method?limit=0").session(session)), 400, "VALIDATION_FAILED");
        assertEquals(0, endpoint.writes.get());
    }

    /** 框架头与安全错误必须保持，合法 500 不被统统转换成 400。 */
    @Test
    void preservesMethodMediaAndSecuritySemantics() throws Exception {
        // 已登录仍需 CSRF，使用不支持的方法时框架提供 Allow。
        MockHttpSession session = login();
        expect(mvc.perform(delete("/api/v1/session").session(session).with(csrf())), 405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
        expect(mvc.perform(post("/api/v1/framework-test/validate").session(session).with(csrf())
                .contentType(MediaType.TEXT_PLAIN).content("bad")), 415, "UNSUPPORTED_MEDIA_TYPE");
        expect(mvc.perform(post("/api/v1/apps").session(session).contentType(MediaType.APPLICATION_JSON).content("{}")), 403, "FORBIDDEN");
        expect(mvc.perform(get("/api/v1/apps")), 401, "AUTH_REQUIRED");
        expect(mvc.perform(get("/api/v1/framework-test/return-value").session(session)), 500, "INTERNAL_ERROR");
    }

    /** 校验完整稳定 JSON 形状。 */
    private ResultActions expect(ResultActions action, int status, String code) throws Exception {
        return action.andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.errors").isArray()).andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
    /** 构造通过认证与 CSRF 的 JSON 输入。 */
    private ResultActions write(MockHttpSession session, String path, String body) throws Exception {
        // 匿名登录没有 Session；仅已登录请求附带当前会话。
        var request = post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
        if (session != null) request.session(session);
        return mvc.perform(request);
    }
    /** 使用正式登录入口建立 Servlet 会话。 */
    private MockHttpSession login() throws Exception {
        return (MockHttpSession) write(null, "/api/v1/auth/login",
                "{\"email\":\"test@example.com\",\"password\":\"Test-password-123!\"}")
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    /** 只在本测试上下文注册框架边界，不增加生产 API。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        /** 业务计数器属于当前上下文。 */
        @Bean Fixture fixture() { return new Fixture(); }
    }
    /** 固定声明覆盖生产暂时没有的框架绑定类别。 */
    @RestController
    @RequestMapping("/api/v1/framework-test")
    static class Fixture {
        /** 不合法输入不应进入处理器。 */
        final AtomicInteger writes = new AtomicInteger();
        /** 每个列表元素独立校验，用于 errors 上限回归。 */
        record Input(List<@NotBlank String> values) { }
        /** 已解码后执行 Bean 校验。 */
        @PostMapping(value="/validate", consumes="application/json")
        void validate(@Valid @RequestBody Input input) { writes.incrementAndGet(); }
        /** 最小绑定 DTO。 */
        record Bound(@Min(1) int limit) { }
        /** 类型绑定和约束分别触发。 */
        @GetMapping("/bind") void bind(@Valid @ModelAttribute Bound bound) { writes.incrementAndGet(); }
        /** 缺失必填查询参数。 */
        @GetMapping("/required") void required(@RequestParam String name) { writes.incrementAndGet(); }
        /** 缺失必填请求头。 */
        @GetMapping("/header") void header(@RequestHeader("X-Test") String header) { writes.incrementAndGet(); }
        /** 缺失 multipart part。 */
        @PostMapping("/upload") void upload(@RequestPart("file") MultipartFile file) { writes.incrementAndGet(); }
        /** 方法参数约束。 */
        @GetMapping("/method") void method(@RequestParam @Min(1) int limit) { writes.incrementAndGet(); }
        /** 服务返回值约束失败保持 500。 */
        @NotBlank @GetMapping("/return-value") String returnValue() { return ""; }
    }
}
