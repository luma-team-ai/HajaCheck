package com.hajacheck.auth.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * 현재 세션 종료 공용 처리 — 세션 무효화 + SecurityContext clear + 세션 쿠키 만료 + CSRF 토큰 회전.
 *
 * <p>원래 {@code AuthController.logout} 안에 인라인으로 있던 로직을 추출했다(#1315). 비밀번호 변경도
 * 성공 후 같은 정리를 해야 하는데, 복붙하면 네 단계 중 하나가 한쪽에서만 갱신되는 조용한 불일치가 생긴다
 * (특히 CSRF 회전은 #1200 회귀 이력이 있다). <b>추출 전후로 로그아웃 동작은 동일해야 하며</b>
 * {@code AuthControllerTest.로그아웃_세션무효화_세션쿠키만료} 와 {@code AuthCsrfRotationIntegrationTest}
 * 가 그 계약을 고정한다.
 *
 * <p>⚠️ <b>주입되는 {@link CsrfTokenRepository} 는 반드시 {@code SecurityConfig.csrfTokenRepository()}
 * 와 동일 인스턴스</b>여야 한다(빈 주입이므로 자동 보장). 인라인 생성하면 쿠키명·httpOnly·path 가
 * 이원화돼 여기서 심은 쿠키를 필터가 못 읽는다.
 *
 * <p>⚠️ <b>전제: 호출하는 엔드포인트가 CSRF 보호 대상</b>이라는 것. 유효한 XSRF-TOKEN 쿠키 없이는
 * CsrfFilter 가 403 으로 끊어 이 지점에 도달하지 못하므로, "여기 도달 = 쿠키가 이미 있었다 = 필터는
 * 쿠키를 새로 심지 않았다" 가 성립하고 응답의 XSRF-TOKEN Set-Cookie 는 아래 saveToken 하나뿐이다.
 * 훗날 {@code csrf().ignoringRequestMatchers(...)} 로 호출부가 예외 처리되면 이 전제가 깨져 필터×컨트롤러
 * 이중 Set-Cookie 가 되므로, 그때는 이 로직도 함께 재검토할 것.
 *
 * <p>⚠️ <b>무효화 범위는 "현재 세션"뿐</b>이다. 다른 기기에 남은 세션은 그대로 살아 있다 — 현 설정은
 * non-indexed Redis 세션이라 {@code FindByIndexNameSessionRepository} 빈이 없고 주입하면 기동이
 * 실패한다(PasswordResetService javadoc 에 기록된 기존 한계). <b>전 기기 무효화는 후속 이슈 #1318</b>.
 */
@Component
@RequiredArgsConstructor
public class SessionTerminator {

    // Spring Session 기본 쿠키명 — 종료 시 만료 처리 대상.
    private static final String SESSION_COOKIE = "SESSION";

    // SecurityConfig.csrfTokenRepository() 와 동일 인스턴스 — 응답에 새 CSRF 토큰을 심는다.
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * 현재 세션을 끝낸다. 호출부는 <b>DB 변경이 커밋된 뒤에</b> 호출해야 한다(쓰기 트랜잭션이 롤백됐는데
     * 세션만 날아가는 상태 불일치 방지).
     */
    public void terminate(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        // 브라우저에 남은 세션 쿠키를 즉시 만료(Max-Age=0)시켜 stale 세션 재사용 방지.
        expireSessionCookie(response);
        // CSRF 쿠키는 "삭제"가 아니라 "회전"한다(#1200). 삭제하면 로그아웃 화면(/login)에서 곧바로
        // 재로그인할 때 double-submit 쿠키가 없어 첫 POST 가 403 으로 실패했다.
        // CookieCsrfTokenRepository 는 세션 바인딩 없는 double-submit 이라 서버가 토큰을 저장하지
        // 않는다(쿠키 값 == 헤더 값 일치만 검증) → 세션이 무효화된 뒤 유효한 CSRF 토큰이 쿠키에
        // 남아 있어도 그것만으로는 어떤 권한도 얻지 못한다. 값을 새로 발급하므로 "stale 토큰
        // 재사용 방지" 라는 원래 의도도 그대로 유지된다.
        // CsrfCookieFilter 는 컨트롤러보다 먼저 실행되므로, 여기서 심는 새 값이 응답의 최종값이다.
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    private void expireSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
