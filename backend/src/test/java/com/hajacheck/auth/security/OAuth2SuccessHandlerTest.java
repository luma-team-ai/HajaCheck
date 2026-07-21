package com.hajacheck.auth.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.OAuth2Properties;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 소셜 로그인 성공 시 lastLoginAt 갱신(자체 로그인 AuthController.login() 과 정합)을 검증.
 * HAJA-310 / GitHub #520: 기존엔 OAuth2SuccessHandler 에 갱신 호출이 없어 소셜 로그인 유저의
 * lastLoginAt 이 영구히 갱신되지 않는 버그가 있었다.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2SuccessHandlerTest {

    @Mock
    private OAuth2Properties oAuth2Properties;
    @Mock
    private AuthService authService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private RedirectStrategy redirectStrategy;

    @InjectMocks
    private OAuth2SuccessHandler oAuth2SuccessHandler;

    private User user(Long id) {
        User user = User.builder()
                .email("social@haja.com")
                .name("소셜사용자")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void 소셜로그인_성공시_lastLoginAt_갱신후_리다이렉트() throws Exception {
        LoginUser principal = new LoginUser(user(42L), Map.of());
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        oAuth2SuccessHandler.setRedirectStrategy(redirectStrategy);
        when(oAuth2Properties.getSuccessRedirect()).thenReturn("http://localhost:5173/dashboard");

        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication);

        verify(authService).updateLastLogin(42L);
        verify(redirectStrategy).sendRedirect(request, response, "http://localhost:5173/dashboard");
    }

    @Test
    void lastLoginAt_갱신실패해도_best_effort로_리다이렉트는_수행() throws Exception {
        LoginUser principal = new LoginUser(user(7L), Map.of());
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        oAuth2SuccessHandler.setRedirectStrategy(redirectStrategy);
        doThrow(new RuntimeException("DB 오류")).when(authService).updateLastLogin(7L);
        when(oAuth2Properties.getSuccessRedirect()).thenReturn("http://localhost:5173/dashboard");

        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication);

        verify(redirectStrategy).sendRedirect(request, response, "http://localhost:5173/dashboard");
    }

    @Test
    void principal이_LoginUser가_아니면_갱신스킵하고_리다이렉트는_수행() throws Exception {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("someOtherPrincipal", null);
        oAuth2SuccessHandler.setRedirectStrategy(redirectStrategy);
        when(oAuth2Properties.getSuccessRedirect()).thenReturn("http://localhost:5173/dashboard");

        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication);

        verify(authService, never()).updateLastLogin(any());
        verify(redirectStrategy).sendRedirect(request, response, "http://localhost:5173/dashboard");
    }
}
