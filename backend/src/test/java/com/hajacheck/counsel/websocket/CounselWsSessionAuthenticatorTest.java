package com.hajacheck.counsel.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.security.LoginUser;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CounselWsSessionAuthenticator 단위테스트 — Spring Session 저장소에서 SPRING_SECURITY_CONTEXT 추출/거부
 * (WebSocket 2단계 인증의 핵심 검증기, #20/HAJA-33).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounselWsSessionAuthenticatorTest {

    private static final String SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";
    private static final Long USER_ID = 42L;

    @SuppressWarnings("unchecked")
    @Mock
    private ObjectProvider<SessionRepository<? extends Session>> sessionRepositoryProvider;

    private MapSessionRepository mapSessionRepository;
    private CounselWsSessionAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        mapSessionRepository = new MapSessionRepository(new ConcurrentHashMap<>());
        authenticator = new CounselWsSessionAuthenticator(sessionRepositoryProvider);
        // doReturn 으로 와일드카드 캡처(SessionRepository<? extends Session>) 제네릭 충돌을 우회한다.
        org.mockito.Mockito.doReturn(mapSessionRepository).when(sessionRepositoryProvider).getIfAvailable();
    }

    @Test
    void 유효세션_로그인주체_userId반환() {
        String sessionId = saveSessionWithAuthenticatedUser();

        assertThat(authenticator.resolveUserId(sessionId)).isEqualTo(USER_ID);
    }

    @Test
    void 세션없음_null() {
        assertThat(authenticator.resolveUserId("no-such-session")).isNull();
    }

    @Test
    void nullSessionId_null() {
        assertThat(authenticator.resolveUserId(null)).isNull();
    }

    @Test
    void 보안컨텍스트없는세션_null() {
        MapSession session = mapSessionRepository.createSession();
        mapSessionRepository.save(session);

        assertThat(authenticator.resolveUserId(session.getId())).isNull();
    }

    @Test
    void 저장소빈없음_null_failClosed() {
        when(sessionRepositoryProvider.getIfAvailable()).thenReturn(null);

        assertThat(authenticator.resolveUserId("any")).isNull();
    }

    @Test
    void 로그아웃_세션삭제후_null() {
        String sessionId = saveSessionWithAuthenticatedUser();
        mapSessionRepository.deleteById(sessionId);

        assertThat(authenticator.resolveUserId(sessionId)).isNull();
    }

    private String saveSessionWithAuthenticatedUser() {
        MapSession session = mapSessionRepository.createSession();
        User user = User.builder()
                .email("ws@haja.com").name("사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        LoginUser principal = new LoginUser(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        session.setAttribute(SECURITY_CONTEXT_KEY, context);
        mapSessionRepository.save(session);
        return session.getId();
    }
}
