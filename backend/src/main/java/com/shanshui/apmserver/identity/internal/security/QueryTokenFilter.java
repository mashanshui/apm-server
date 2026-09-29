package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.api.AuthenticatedQueryToken;
import com.shanshui.apmserver.identity.api.InvalidQueryTokenException;
import com.shanshui.apmserver.identity.api.QueryAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.QueryTokenAuthentication;
import com.shanshui.apmserver.platform.api.ApiErrorResponse;
import com.shanshui.apmserver.platform.api.JsonResponseWriter;
import com.shanshui.apmserver.identity.internal.config.AgentQueryLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Agent 独立安全链的请求级 Bearer 认证，不读取网页 Session 或 App Key。 */
@Component
public class QueryTokenFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryTokenFilter.class);
    private final QueryTokenAuthentication tokens;
    private final JsonResponseWriter writer;
    private final AgentQueryRateLimiter limiter;
    /** 允许单独关闭外部只读入口；Token 管理与撤销仍可访问。 */
    @Value("${apm.agent.query.enabled:false}")
    private boolean enabled = true;

    /** 仅依赖 identity 公共查询身份接口。 */
    @Autowired
    public QueryTokenFilter(QueryTokenAuthentication tokens, JsonResponseWriter writer,
                            AgentQueryRateLimiter limiter) {
        this.tokens = tokens;
        this.writer = writer;
        this.limiter = limiter;
    }

    /** 纯过滤器单元测试的构造入口。 */
    public QueryTokenFilter(QueryTokenAuthentication tokens, JsonResponseWriter writer) {
        this(tokens, writer, new AgentQueryRateLimiter(new AgentQueryLimitProperties()));
    }

    /** 每次请求重新校验 PostgreSQL；请求完成后清理线程认证状态。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        AuthenticatedQueryToken identity = null;
        AgentQueryRateLimiter.Permit permit = null;
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");
        if (!enabled) {
            writer.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorResponse.of("AGENT_QUERY_DISABLED", "Agent 查询入口暂不可用", true, requestId));
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            writer.write(response, HttpStatus.METHOD_NOT_ALLOWED,
                    ApiErrorResponse.of("AGENT_READ_ONLY", "Agent 查询入口只接受 GET", false, requestId));
            return;
        }
        try {
            limiter.checkInvalidSource(request.getRemoteAddr());
            String bearer = bearer(request);
            identity = tokens.authenticate(bearer);
            permit = limiter.acquire(identity);
            var authentication = new UsernamePasswordAuthenticationToken(identity, null,
                    List.of(new SimpleGrantedAuthority("apm:read")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (InvalidQueryTokenException ex) {
            limiter.recordInvalid(request.getRemoteAddr());
            writer.write(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.of("QUERY_TOKEN_INVALID", ex.getMessage(), false, requestId));
        } catch (AgentRateLimitException ex) {
            response.setHeader("Retry-After", String.valueOf(ex.retryAfterSeconds()));
            writer.write(response, HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorResponse.of("AGENT_RATE_LIMITED", ex.getMessage(), true, requestId));
        } catch (QueryAuthenticationUnavailableException ex) {
            response.setHeader("Retry-After", "30");
            writer.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorResponse.of("QUERY_AUTH_UNAVAILABLE", ex.getMessage(), true, requestId));
        } finally {
            if (permit != null) permit.close();
            SecurityContextHolder.clearContext();
            LOGGER.info("Agent 查询 requestId={} appId={} tokenId={} status={} durationMs={}", requestId,
                    identity == null ? "-" : identity.appId(), identity == null ? "-" : identity.tokenId(),
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 拒绝缺失、重复或不符合单个 Bearer 凭据格式的认证头。 */
    private String bearer(HttpServletRequest request) {
        if (request.getHeader("X-App-Key") != null) throw new InvalidQueryTokenException();
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) throw new InvalidQueryTokenException();
        String header = headers.getFirst();
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7
                || header.substring(7).contains(" ")) throw new InvalidQueryTokenException();
        return header.substring(7);
    }
}
