package com.hajacheck.counsel.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.security.LoginUser;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CounselHandshakeInterceptor 단위테스트 — SESSION 쿠키(Base64) 파싱 + 실제 세션 저장소 검증 + 미인증
 * 핸드셰이크 거부(WebSocket 1단계 인증, #20/HAJA-33 Critical). 실제 {@link MapSessionRepository} 를 써서
 * 쿠키→세션ID→인증주체 경로를 통합 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounselHandshakeInterceptorTest {

    private static final String SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";
    private static final Long USER_ID = 7L;

    @SuppressWarnings("unchecked")
    @Mock
    private ObjectProvider<SessionRepository<? extends Session>> sessionRepositoryProvider;

    private MapSessionRepository mapSessionRepository;
    private CounselHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        mapSessionRepository = new MapSessionRepository(new ConcurrentHashMap<>());
        Mockito.doReturn(mapSessionRepository).when(sessionRepositoryProvider).getIfAvailable();
        interceptor = new CounselHandshakeInterceptor(
                new CounselWsSessionAuthenticator(sessionRepositoryProvider));
    }

    @Test
    void 유효세션쿠키_핸드셰이크통과_속성저장() {
        String sessionId = saveAuthenticatedSession();
        String cookie = "SESSION=" + base64(sessionId);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                requestWithCookie(cookie), mock(ServerHttpResponse.class), null, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get(CounselHandshakeInterceptor.ATTR_USER_ID)).isEqualTo(USER_ID);
        assertThat(attributes.get(CounselHandshakeInterceptor.ATTR_SESSION_ID)).isEqualTo(sessionId);
    }

    @Test
    void 쿠키없음_핸드셰이크거부_401() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                requestWithCookie(null), response, null, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    void SESSION쿠키없는다른쿠키만_거부() {
        boolean result = interceptor.beforeHandshake(
                requestWithCookie("XSRF-TOKEN=abc; foo=bar"), mock(ServerHttpResponse.class), null,
                new HashMap<>());

        assertThat(result).isFalse();
    }

    @Test
    void 세션쿠키있으나_저장소에세션없음_거부() {
        String cookie = "SESSION=" + base64("no-such-session");

        boolean result = interceptor.beforeHandshake(
                requestWithCookie(cookie), mock(ServerHttpResponse.class), null, new HashMap<>());

        assertThat(result).isFalse();
    }

    @Test
    void 다중쿠키중_SESSION추출_통과() {
        String sessionId = saveAuthenticatedSession();
        String cookie = "XSRF-TOKEN=abc; SESSION=" + base64(sessionId) + "; other=1";
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                requestWithCookie(cookie), mock(ServerHttpResponse.class), null, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get(CounselHandshakeInterceptor.ATTR_USER_ID)).isEqualTo(USER_ID);
    }

    private String saveAuthenticatedSession() {
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

    private ServerHttpRequest requestWithCookie(String cookieHeader) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (cookieHeader != null) {
            headers.add(HttpHeaders.COOKIE, cookieHeader);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }
}
