package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.support.SessionTerminator;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자체(기업 email/password) 로그인·로그아웃.
 * loginId 를 email 로 사용. 실패는 GlobalExceptionHandler 에서 AUTH_INVALID_CREDENTIALS 로 통일.
 */
@Slf4j
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthService authService;
    private final SessionTerminator sessionTerminator;

    @Operation(summary = "자체 로그인", description = "email/password 로 인증 후 세션 발급")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // 인증 실패(BadCredentials/미존재/잠금) → AuthenticationException → 401 AUTH_INVALID_CREDENTIALS.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.loginId(), request.password()));

        // 세션 고정(Session Fixation) 방어: 인증 성공 직후 세션 ID 를 회전.
        // (수동 authenticate 라 Spring 의 SessionAuthenticationStrategy 가 안 걸리므로 직접 처리.)
        // changeSessionId() 로 익명 세션 ID 를 무효화 → Redis 세션 키 재발급. saveContext 이전에 수행.
        httpRequest.getSession(true);      // CSRF 로 대개 이미 존재하나 안전하게 보장
        httpRequest.changeSessionId();

        // Spring Security 6: SecurityContext 를 명시적으로 세션에 저장해야 이후 요청에서 인증 유지.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        LoginUser principal = (LoginUser) authentication.getPrincipal();
        Long userId = principal.getUserId();

        // 응답은 조회로 구성하고, lastLoginAt 갱신은 best-effort — 갱신 실패가 로그인 성공(세션 발급)을
        // 무효화하지 않도록 분리(이미 인증된 세션과 500 응답의 불일치 방지).
        UserResponse response = authService.getMe(userId);
        try {
            authService.updateLastLogin(userId);
        } catch (Exception e) {
            log.warn("lastLoginAt 갱신 실패(로그인 자체는 성공) userId={}", userId, e);
        }
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "로그아웃", description = "세션 무효화 + SecurityContext clear + 세션 쿠키 만료 + CSRF 토큰 회전")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        // 세션 무효화·SecurityContext clear·세션 쿠키 만료·CSRF 토큰 회전(#1200)은 SessionTerminator 로
        // 추출했다(#1315) — 비밀번호 변경(UserController)도 성공 후 같은 정리를 하므로, 복붙하면 네 단계
        // 중 하나가 한쪽에서만 갱신되는 조용한 불일치가 생긴다. 상세 근거는 SessionTerminator javadoc 참조.
        sessionTerminator.terminate(httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
