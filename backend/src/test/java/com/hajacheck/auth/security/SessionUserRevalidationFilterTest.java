package com.hajacheck.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SessionUserRevalidationFilter 단위 테스트(#794, PR머신 리뷰 P2 대응) — WAITING 계정이 GET /api/users/me·
 * POST /api/users/me/invite-code·POST /api/auth/logout 외 요청에서 차단되는지, 그리고 그 세 예외 경로는
 * 통과하는지를 고정한다(로그아웃 누락은 WAITING 사용자가 세션에 갇히는 실사용 버그였다).
 */
@ExtendWith(MockitoExtension.class)
class SessionUserRevalidationFilterTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private SessionUserRevalidationFilter filter;

    @BeforeEach
    void setUp() {
        // @InjectMocks는 @Mock/@Spy 필드만 생성자 인자로 인식한다 — ObjectMapper는 실제 JSON 직렬화가
        // 필요해(응답 본문 검증) mock이 아닌 실 인스턴스를 직접 넘겨 생성한다.
        filter = new SessionUserRevalidationFilter(userRepository, restAuthenticationEntryPoint, new ObjectMapper());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void givenWaitingSession(Long userId) {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-" + userId,
                "u" + userId + "@haja.com", "홍길동");
        ReflectionTestUtils.setField(waitingUser, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(waitingUser));

        LoginUser sessionUser = new LoginUser(waitingUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(sessionUser, null, sessionUser.getAuthorities()));
    }

    @Test
    void WAITING_사용자의_내정보조회는_통과한다() throws Exception {
        givenWaitingSession(1L);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/users/me");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(403);
    }

    @Test
    void WAITING_사용자의_초대코드_redeem은_통과한다() throws Exception {
        givenWaitingSession(2L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/users/me/invite-code");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // PR머신 리뷰 P2 회귀 고정 — 로그아웃이 막히면 WAITING 사용자가 코드 입력 전까지 세션에서
    // 빠져나갈 방법이 없다(쿠키 수동 삭제 외 탈출 불가).
    @Test
    void WAITING_사용자의_로그아웃은_통과한다() throws Exception {
        givenWaitingSession(3L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/logout");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void WAITING_사용자의_그밖의_요청은_403으로_차단된다() throws Exception {
        givenWaitingSession(4L);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/admin/users");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(403);
        assertThat(body.toString()).contains("AUTH_ACCOUNT_WAITING");
    }
}
