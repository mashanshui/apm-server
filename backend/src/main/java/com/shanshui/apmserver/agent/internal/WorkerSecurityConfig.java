package com.shanshui.apmserver.agent.internal;

import com.shanshui.apmserver.platform.api.ApiErrorResponse;
import com.shanshui.apmserver.platform.api.JsonResponseWriter;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Worker 专属无状态安全链，不读取网页 Session，也不复用查询 Token 过滤器。 */
@Configuration
public class WorkerSecurityConfig {
    /** 比网页与 Agent 查询链优先匹配，只拦截独立路径。 */
    @Bean
    @Order(0)
    public SecurityFilterChain workerSecurity(HttpSecurity http, WorkerCredentialService credentials, JsonResponseWriter writer) throws Exception {
        http.securityMatcher("/api/worker/v1/**")
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("ANALYSIS_WORKER"))
                .addFilterBefore(new WorkerFilter(credentials, writer), UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> unauthorized(writer, response)));
        return http.build();
    }

    /** 稳定鉴权响应不包含原始凭据或异常内容。 */
    private static void unauthorized(JsonResponseWriter writer, HttpServletResponse response) throws IOException {
        writer.write(response, HttpStatus.UNAUTHORIZED, ApiErrorResponse.of("WORKER_CREDENTIAL_INVALID", "Worker 凭据无效", false, null));
    }

    /** 仅挂到当前 Spring Security 链，不注册全局 Servlet 过滤器。 */
    private static final class WorkerFilter extends OncePerRequestFilter {
        /** 数据库有效性校验器。 */
        private final WorkerCredentialService credentials;
        /** 平台安全 JSON 输出器。 */
        private final JsonResponseWriter writer;

        /** 保存当前链的依赖。 */
        private WorkerFilter(WorkerCredentialService credentials, JsonResponseWriter writer) {
            this.credentials = credentials;
            this.writer = writer;
        }

        /** 每次请求忽略 Cookie 身份，并在认证结束后清除线程上下文。 */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            SecurityContextHolder.clearContext();
            // 只接受单个标准 Authorization 请求头，不接受 URL 或 Cookie 传秘密。
            var headers = request.getHeaders("Authorization");
            // 未提供认证头与错误格式使用相同错误。
            String header = headers.hasMoreElements() ? headers.nextElement() : null;
            if (header == null || headers.hasMoreElements() || !header.startsWith("Bearer ")) {
                unauthorized(writer, response);
                return;
            }
            // 认证异常只在当前步骤捕获，不把业务冲突变成鉴权失败。
            WorkerCredentialService.Identity identity;
            try {
                identity = credentials.authenticate(header.substring(7));
            } catch (QueryValidationException failure) {
                unauthorized(writer, response);
                return;
            }
            // 在 JSON 反序列化前限制总请求，覆盖 chunked 与伪造 Content-Length。
            byte[] body = request.getInputStream().readNBytes(2097153);
            if (body.length>2097152) {
                writer.write(response, HttpStatus.PAYLOAD_TOO_LARGE, ApiErrorResponse.of("ANALYSIS_REQUEST_TOO_LARGE", "Worker 请求超过 2 MiB", false, null));
                return;
            }
            // MVC 只能读取已检查的有限请求正文，不再从底层流加载任意数据。
            HttpServletRequest bounded = new HttpServletRequestWrapper(request) {
                @Override
                public ServletInputStream getInputStream() {
                    // 为同步 MVC 请求提供已冻结内存流。
                    ByteArrayInputStream input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available()==0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("只接受同步 Worker 请求"); }
                    };
                }
            };
            try {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        identity, null, List.of(new SimpleGrantedAuthority("ANALYSIS_WORKER"))));
                chain.doFilter(bounded, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
