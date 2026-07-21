package com.hajacheck.auth.security;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리자 콘솔(#405)의 정지/역할변경이 "즉시" 반영되도록, 매 인증 요청마다 세션에 실린 principal
 * (LoginUser 스냅샷)을 DB 최신 상태와 대조한다. Spring Security 는 principal 을 로그인 시점에
 * 한 번 만들어 세션에 저장할 뿐 요청마다 재조회하지 않으므로, 관리자가 대상을 SUSPENDED 로 바꾸거나
 * 강등해도 대상의 기존 세션은 세션 TTL 까지 예전 권한(ROLE_ADMIN 포함)을 그대로 유지한다 — 정지 계정은
 * "모든 서비스 이용 제한"(PRD FR-8) 계약을 어기고, 강등된 관리자는 스테일 세션으로
 * AdminUserService 의 자기/마지막 ADMIN 보호 가드를 우회할 수 있다(PR #506 리뷰 P1).
 *
 * <p>정지 계정은 {@link RestAuthenticationEntryPoint#commence}를 직접 호출해 401 JSON으로 즉시 응답한다
 * (예외를 던져 ExceptionTranslationFilter가 잡아주길 기다리지 않는다). 권한(role) 변경은 예외 없이
 * SecurityContext의 Authentication을 최신 권한으로 교체만 해서, 뒤이어 실행되는
 * AuthorizationFilter(hasRole("ADMIN") 등)가 최신 role 기준으로 허용/차단을 판단하게 한다 — 이 필터는
 * 반드시 AuthorizationFilter보다 앞에 위치해야 한다(SecurityConfig 참고).
 */
@Component
@RequiredArgsConstructor
public class SessionUserRevalidationFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof LoginUser sessionUser) {
            Optional<User> current = userRepository.findById(sessionUser.getUserId());
            // 계정이 물리 삭제되는 경로는 이 시스템에 없지만(soft 상태 전이만 존재), 방어적으로 정지와
            // 동일하게 처리한다 — 존재하지 않는 사용자로 인증을 계속 허용할 이유가 없다.
            if (current.isEmpty() || current.get().isSuspended()) {
                SecurityContextHolder.clearContext();
                restAuthenticationEntryPoint.commence(request, response, new LockedException("계정이 정지되었습니다."));
                return;
            }

            User user = current.get();
            if (user.getRole() != sessionUser.getRole() || !Objects.equals(user.getCompanyId(), sessionUser.getCompanyId())) {
                LoginUser refreshed = new LoginUser(user);
                // LoginUser 생성자는 passwordHash 를 다시 채운다 — ProviderManager 를 거치지 않는 이
                // 경로에서 eraseCredentials 를 직접 호출하지 않으면 이번 요청 종료 시 Redis 세션에
                // 비밀번호 해시가 재기록된다(LoginUser 클래스 주석의 CredentialsContainer 설계 의도 위반).
                refreshed.eraseCredentials();
                // 원래 토큰이 OAuth2AuthenticationToken이었어도 UsernamePasswordAuthenticationToken으로
                // 교체한다 — 이 코드베이스는 어디서도 구체 Authentication 타입을 분기하지 않고
                // principal(LoginUser)만 사용하므로 안전하다.
                UsernamePasswordAuthenticationToken updated = new UsernamePasswordAuthenticationToken(
                        refreshed, authentication.getCredentials(), refreshed.getAuthorities());
                updated.setDetails(authentication.getDetails());
                SecurityContextHolder.getContext().setAuthentication(updated);
            }
        }

        filterChain.doFilter(request, response);
    }
}
