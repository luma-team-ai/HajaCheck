package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.support.SessionTerminator;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.EnumSet;
import java.util.Set;
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
 * 자체(email/password) 로그인·로그아웃.
 * loginId 를 email 로 사용. 인증 실패는 GlobalExceptionHandler 에서 AUTH_INVALID_CREDENTIALS 로 통일.
 *
 * <p><b>화면(포털)별 로그인 분리(#1514)</b> — 기업(/login)·플랫폼 관리자(/platform-admin/login)·
 * 상담원(/counsel-console/login) 세 화면이 같은 {@code POST /api/auth/login} 을 쓰고 있어서,
 * 서버는 어느 화면의 요청인지 알 수 없었고 role 과 무관하게 세션을 발급했다. role 판정은 프론트 훅이
 * "이미 발급된 세션을 logout 으로 되돌리는" 사후 처리였으므로, devtools 로 그 logout 만 막거나 curl 로
 * 직접 치면 그대로 뚫렸다. 엔드포인트를 셋으로 나누고 각 엔드포인트가 허용 role 화이트리스트를
 * 강제한다 — 실질 차단은 여기(서버)가 담당하고 프론트 가드는 UX 레벨로 격하된다.
 */
@Slf4j
@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    // 화면(포털)별 허용 role 화이트리스트 — AdminUserService.ASSIGNABLE_ROLES 와 같은 EnumSet 상수 패턴.
    // 세 집합은 서로 겹치지 않는다(한 계정은 정확히 한 포털에만 로그인할 수 있다).
    private static final Set<Role> COMPANY_PORTAL_ROLES = EnumSet.of(Role.ADMIN, Role.INSPECTOR, Role.USER);
    private static final Set<Role> PLATFORM_ADMIN_PORTAL_ROLES = EnumSet.of(Role.PLATFORM_ADMIN);
    private static final Set<Role> COUNSELOR_PORTAL_ROLES = EnumSet.of(Role.COUNSELOR);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthService authService;
    private final SessionTerminator sessionTerminator;

    @Operation(summary = "기업 로그인",
            description = "기업 화면(/login) 전용. ADMIN/INSPECTOR/USER 만 허용하며 그 외 role 은 "
                    + "403 AUTH_ROLE_NOT_ALLOWED(세션 미발급). 성공 시 세션 발급.")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authenticateForPortal(request, COMPANY_PORTAL_ROLES, "company", httpRequest, httpResponse);
    }

    @Operation(summary = "플랫폼 관리자 로그인",
            description = "플랫폼 관리자 화면(/platform-admin/login) 전용. PLATFORM_ADMIN 만 허용하며 "
                    + "그 외 role 은 403 AUTH_ROLE_NOT_ALLOWED(세션 미발급). 성공 시 세션 발급.")
    @PostMapping("/platform-admin/login")
    public ResponseEntity<ApiResponse<UserResponse>> platformAdminLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authenticateForPortal(request, PLATFORM_ADMIN_PORTAL_ROLES, "platform-admin",
                httpRequest, httpResponse);
    }

    @Operation(summary = "상담원 로그인",
            description = "상담원 콘솔(/counsel-console/login) 전용. COUNSELOR 만 허용하며 그 외 role 은 "
                    + "403 AUTH_ROLE_NOT_ALLOWED(세션 미발급). 성공 시 세션 발급.")
    @PostMapping("/counselor/login")
    public ResponseEntity<ApiResponse<UserResponse>> counselorLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return authenticateForPortal(request, COUNSELOR_PORTAL_ROLES, "counselor", httpRequest, httpResponse);
    }

    /**
     * 세 로그인 엔드포인트의 공통 본문 — 인증 → 포털 role 게이트 → 세션 고정 방어 → 세션 저장.
     *
     * <p><b>⚠️ 실행 순서가 이 기능의 전부다.</b> role 게이트는 {@code getSession(true)} /
     * {@code changeSessionId()} <b>보다 앞</b>에 있어야 한다. 사이(회전 후 saveContext 전)에 두면
     * 자격증명만 통과한 차단 대상에게 <b>회전된 세션이 그대로 남는다</b> — saveContext 를 건너뛰어
     * SecurityContext 가 안 실려도, 세션 ID 회전과 SESSION 쿠키 발급은 이미 일어난 뒤라
     * "인증 안 된 새 세션을 쥔 사용자"라는 이 이슈가 막으려던 구멍이 형태만 바꿔 재현된다.
     * 게이트를 앞에 두면 실패 경로에서 세션을 <b>만들지도 회전시키지도 않으므로</b> 뒤늦은
     * {@code invalidate()} 보완(=이미 존재하던 익명 세션까지 날려 CSRF 토큰이 꼬일 수 있는 처리)이
     * 애초에 필요 없다. 이 계약은 {@code LoginRoleGateIntegrationTest} 가 고정한다
     * (403 응답의 세션으로 {@code GET /api/users/me} 를 다시 쳐서 401 인지, 세션 ID 가 안 바뀌었는지).
     */
    private ResponseEntity<ApiResponse<UserResponse>> authenticateForPortal(
            LoginRequest request,
            Set<Role> allowedRoles,
            String portal,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // 인증 실패(BadCredentials/미존재/잠금) → AuthenticationException → 401 AUTH_INVALID_CREDENTIALS.
        // role 게이트보다 먼저 걸리므로, 비밀번호를 모르는 쪽은 role 정보를 얻지 못한다.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.loginId(), request.password()));

        LoginUser principal = (LoginUser) authentication.getPrincipal();
        Long userId = principal.getUserId();

        // ── 포털 role 게이트 (세션을 만들기 전에) ──
        if (!allowedRoles.contains(principal.getRole())) {
            // authenticate() 자체는 SecurityContextHolder 를 건드리지 않지만(설정이 바뀌어도 새지 않도록)
            // 명시적으로 비운다. saveContext·updateLastLogin 은 호출하지 않는다 — 로그인 성공이 아니다.
            SecurityContextHolder.clearContext();
            log.warn("포털 role 불일치로 로그인 차단 portal={} userId={} role={}", portal, userId, principal.getRole());
            throw new BusinessException(ErrorCode.AUTH_ROLE_NOT_ALLOWED);
        }

        // 세션 고정(Session Fixation) 방어: 인증·인가 통과 직후 세션 ID 를 회전.
        // (수동 authenticate 라 Spring 의 SessionAuthenticationStrategy 가 안 걸리므로 직접 처리.)
        // changeSessionId() 로 익명 세션 ID 를 무효화 → Redis 세션 키 재발급. saveContext 이전에 수행.
        httpRequest.getSession(true);      // CSRF 로 대개 이미 존재하나 안전하게 보장
        httpRequest.changeSessionId();

        // Spring Security 6: SecurityContext 를 명시적으로 세션에 저장해야 이후 요청에서 인증 유지.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

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
