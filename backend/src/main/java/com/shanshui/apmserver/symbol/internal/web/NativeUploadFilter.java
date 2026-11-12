package com.shanshui.apmserver.symbol.internal.web;

import com.shanshui.apmserver.identity.api.AppAccessControl;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.internal.archive.NativeUploadLimiter;
import com.shanshui.apmserver.symbol.internal.storage.SymbolFileStore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import java.io.IOException;
import java.util.UUID;

/** 安全过滤链之后、DispatcherServlet multipart 解析之前取得 SO 上传许可。 */
@Component
@Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 20)
public class NativeUploadFilter extends OncePerRequestFilter {
    /** 单实例不排队许可，覆盖接收和 Servlet multipart 清理。 */
    private final NativeUploadLimiter limiter = new NativeUploadLimiter();
    /** 权限验证不读取请求参数或正文。 */
    private final AppAccessControl access;
    /** 与正式符号卷相同的暂存磁盘。 */
    private final SymbolFileStore store;
    /** 过滤器错误沿用正式安全 API 响应。 */
    private final HandlerExceptionResolver errors;
    /** 注入公开权限边界及统一错误解析器。 */
    public NativeUploadFilter(AppAccessControl access, SymbolFileStore store,
                              @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.access = access; this.store = store; this.errors = errors;
    }
    /** 仅 SO 批次走大文件接收预算。 */
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().matches("/api/v1/apps/[0-9a-fA-F-]{36}/symbols/native-batches");
    }
    /** 先授权及磁盘检查，再交由 Servlet 解析，结束后自动释放。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            UUID app = UUID.fromString(request.getRequestURI().split("/")[4]);
            access.requireEdit(app, SecurityContextHolder.getContext().getAuthentication());
            try (var permit = limiter.tryAcquire(store.root())) {
                request.setAttribute("nativeUploadDeadline", permit.deadlineNanos());
                if (org.springframework.web.util.WebUtils.getNativeRequest(request, org.springframework.web.multipart.MultipartHttpServletRequest.class) != null) {
                    chain.doFilter(request, response);
                } else try (var multipart = new NativeMultipartBody(store)) {
                    chain.doFilter(multipart.parse(request, permit.deadlineNanos()), response);
                }
            }
        } catch (NativeUploadLimiter.BusyException busy) {
            response.setHeader("Retry-After", "30");
            respond(request, response, new SymbolStoreUnavailableException("SO 上传繁忙"));
        } catch (IOException disk) {
            respond(request, response, new SymbolStoreUnavailableException("SO 暂存空间或接收不可用"));
        } catch (RuntimeException failure) { respond(request, response, failure); }
    }
    /** 提前拒绝大正文时明确响应长度，避免客户端将连接中止误解为截断 JSON。 */
    private void respond(HttpServletRequest request, HttpServletResponse response, RuntimeException failure) throws IOException {
        org.slf4j.LoggerFactory.getLogger(NativeUploadFilter.class).warn("SO 请求已拒绝或中止：类别={}", failure.getClass().getSimpleName());
        var buffered = new org.springframework.web.util.ContentCachingResponseWrapper(response);
        if (errors.resolveException(request, buffered, null, failure) == null) throw failure;
        buffered.setHeader("Connection", "close");
        buffered.copyBodyToResponse();
    }
}
