package com.shanshui.apmserver.identity.internal.web;

import com.shanshui.apmserver.platform.api.ApiErrorResponse;
import com.shanshui.apmserver.identity.internal.domain.CurrentUserResponse;
import com.shanshui.apmserver.identity.internal.security.AppUserPrincipal;
import com.shanshui.apmserver.identity.api.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthenticationController(AuthenticationManager authenticationManager,
                                    SecurityContextRepository securityContextRepository,
                                    CsrfTokenRepository csrfTokenRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/session")
    public ResponseEntity<?> session(Authentication authentication,
                                     CsrfToken csrfToken,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        // CookieCsrfTokenRepository 使用延迟 Token；显式读取以确保 SPA 首次访问即可获得 XSRF-TOKEN。
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
        } else {
            csrfToken.getToken();
        }
        csrfTokenRepository.saveToken(csrfToken, request, response);
        if (!(authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiErrorResponse.of("AUTH_REQUIRED", "请先登录", false, null));
        }
        return ResponseEntity.ok(CurrentUserResponse.of(
                principal.getUserId(), principal.getUsername(), principal.getDisplayName()));
    }

    @PostMapping("/auth/login")
    public CurrentUserResponse login(@RequestBody LoginRequest request,
                                     HttpServletRequest servletRequest,
                                     HttpServletResponse servletResponse) {
        try {
            if (request == null || request.email() == null || request.password() == null) {
                throw new InvalidCredentialsException();
            }
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, servletRequest, servletResponse);
            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            return CurrentUserResponse.of(principal.getUserId(), principal.getUsername(), principal.getDisplayName());
        } catch (InvalidCredentialsException ex) {
            throw ex;
        } catch (AuthenticationException ex) {
            throw new InvalidCredentialsException();
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String email, String password) {
    }
}
