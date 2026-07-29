package com.hajacheck.counsel.websocket;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.security.LoginUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * WebSocket 2단계 인증의 공용 세션 검증기 — Spring Session(Redis) 저장소에서 세션ID로 세션을 재조회해
 * {@code SPRING_SECURITY_CONTEXT} 속성의 인증 주체가 살아 있는지 확인하고 {@code userId}를 돌려준다.
 * 핸드셰이크(HTTP 쿠키)와 STOMP CONNECT(재검증)에서 동일 로직을 공유한다.
 *
 * <p>{@link SessionRepository} 는 {@link ObjectProvider} 로 느슨하게 주입한다 — 운영(store-type=redis)에는
 * 빈이 존재하지만, Spring Session 을 끄는 프로파일(예: 통합 테스트 store-type=none)에서는 빈이 없어도
 * 컨텍스트가 깨지지 않아야 하기 때문이다. 저장소가 없으면 인증 불가(fail-closed)로 처리한다.
 */
@Component
public class CounselWsSessionAuthenticator {

    // Spring Security 가 세션에 SecurityContext 를 저장하는 표준 속성 키
    // (HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY).
    static final String SPRING_SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

    private final ObjectProvider<SessionRepository<? extends Session>> sessionRepositoryProvider;

    public CounselWsSessionAuthenticator(
            ObjectProvider<SessionRepository<? extends Session>> sessionRepositoryProvider) {
        this.sessionRepositoryProvider = sessionRepositoryProvider;
    }

    /**
     * 주어진 Spring Session ID 의 세션이 존재하고 인증된 {@link LoginUser} 를 담고 있으면 그 userId 를,
     * 아니면 null 을 반환한다(fail-closed).
     */
    public Long resolveUserId(String springSessionId) {
        if (springSessionId == null) {
            return null;
        }
        SessionRepository<? extends Session> repository = sessionRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return null;
        }
        Session session = repository.findById(springSessionId);
        if (session == null) {
            return null;
        }
        Object context = session.getAttribute(SPRING_SECURITY_CONTEXT);
        if (!(context instanceof SecurityContext securityContext)) {
            return null;
        }
        Authentication authentication = securityContext.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getUserId();
        }
        return null;
    }

    /**
     * {@link #resolveUserId}와 동일한 세션을 재조회해 role을 돌려준다(상담원 대기열 실시간 갱신,
     * #1001 후속 — {@code /topic/counsel-queue} 구독을 COUNSELOR/PLATFORM_ADMIN으로만 제한하려면
     * SUBSCRIBE 시점에 role이 필요하다). 별도 캐시 없이 그때그때 재조회해 세션 무효화/역할 변경을
     * 즉시 반영한다(resolveUserId와 동일한 fail-closed 원칙).
     */
    public Role resolveRole(String springSessionId) {
        if (springSessionId == null) {
            return null;
        }
        SessionRepository<? extends Session> repository = sessionRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return null;
        }
        Session session = repository.findById(springSessionId);
        if (session == null) {
            return null;
        }
        Object context = session.getAttribute(SPRING_SECURITY_CONTEXT);
        if (!(context instanceof SecurityContext securityContext)) {
            return null;
        }
        Authentication authentication = securityContext.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getRole();
        }
        return null;
    }
}
