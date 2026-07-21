package com.hajacheck.auth.security;

import com.hajacheck.auth.config.OAuth2Properties;
import com.hajacheck.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 성공 → lastLoginAt 갱신(자체 로그인과 정합) 후 프론트로 리다이렉트.
 * 세션은 Spring Security 가 이미 생성·저장(Spring Session Redis) 하므로 별도 처리 불필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuth2Properties oAuth2Properties;
    private final AuthService authService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // AuthController.login() 과 동일하게 lastLoginAt 갱신은 best-effort — 갱신 실패가
        // 이미 발급된 세션의 리다이렉트를 막지 않도록 분리.
        try {
            Object principal = authentication.getPrincipal();
            if (principal instanceof LoginUser loginUser) {
                authService.updateLastLogin(loginUser.getUserId());
            } else {
                log.warn("소셜 로그인 principal 타입이 LoginUser 가 아님 — lastLoginAt 갱신 스킵: {}",
                        principal == null ? "null" : principal.getClass());
            }
        } catch (Exception e) {
            log.warn("소셜 로그인 lastLoginAt 갱신 실패(로그인 자체는 성공)", e);
        }
        getRedirectStrategy().sendRedirect(request, response, oAuth2Properties.getSuccessRedirect());
    }
}
