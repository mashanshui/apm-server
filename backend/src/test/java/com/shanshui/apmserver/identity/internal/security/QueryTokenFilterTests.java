package com.shanshui.apmserver.identity.internal.security;

import com.shanshui.apmserver.identity.api.QueryAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.QueryTokenAuthentication;
import com.shanshui.apmserver.platform.api.JsonResponseWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 鉴权数据库故障与无效凭据均不能泄漏请求秘密。 */
class QueryTokenFilterTests {

    /** 同一来访秘密每次都查询，数据库故障统一返回可重试的 503。 */
    @Test
    void returnsUnavailableWithoutExposingSecret() throws Exception {
        QueryTokenAuthentication authentication = mock(QueryTokenAuthentication.class);
        String secret = "apm_qt_test-secret-never-print";
        when(authentication.authenticate(secret)).thenThrow(
                new QueryAuthenticationUnavailableException(new IllegalStateException("test database down")));
        QueryTokenFilter filter = new QueryTokenFilter(authentication, new JsonResponseWriter(new ObjectMapper()));
        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/v1/application");
            request.addHeader("Authorization", "Bearer " + secret);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, mock(FilterChain.class));
            assertEquals(503, response.getStatus());
            assertEquals("30", response.getHeader("Retry-After"));
            assertNotNull(response.getHeader("X-Request-Id"));
            assertFalse(response.getContentAsString().contains(secret));
            assertFalse(response.getContentAsString().contains("test database down"));
        }
        verify(authentication, times(2)).authenticate(secret);
    }
}
