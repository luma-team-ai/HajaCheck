package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * #1200 회귀 — 로그아웃 시 CSRF 토큰이 "삭제"가 아니라 "회전"되는지 실 HTTP 로 고정한다.
 *
 * <p><b>왜 MockMvc(AuthControllerTest) 가 아니라 RANDOM_PORT e2e 인가</b><br>
 * {@code SecurityMockMvcRequestPostProcessors.csrf()} 는 필터체인의 CsrfTokenRepository 를 리플렉션으로
 * 교체하는데, 교체본은 앱의 CookieCsrfTokenRepository 를 감싸는 것이 아니라
 * {@code TestCsrfTokenRepository(new HttpSessionCsrfTokenRepository())} 다(spring-security-test 6.3.4
 * 바이트코드 실측). 이 교체는 <b>캐시된 스프링 컨텍스트에 그대로 남으므로</b>, 한 번이라도
 * {@code with(csrf())} 를 쓴 MockMvc 컨텍스트에서는 그 뒤 어떤 요청도 필터 경로로 XSRF-TOKEN
 * <b>쿠키</b>를 심지 못한다(세션 저장소로 바뀜). 그래서 MockMvc 로는
 * ① "필터×컨트롤러 이중 발급 없음(Set-Cookie 1개)" 이 실행 순서에 따라 공허하게 참이 되고,
 * ② 필터가 심어준 실제 토큰으로 double-submit 하는 흐름 자체를 재현할 수 없다.
 * (실측: 이 검증을 AuthControllerTest 에 두면 단독 실행은 통과하지만 클래스 전체 실행에서는
 * 프라이밍 GET 이 쿠키를 못 받아 실패했다.)
 *
 * <p>RANDOM_PORT 컨텍스트는 MockMvc 후처리기가 손대지 않는 별도 캐시 컨텍스트라 앱의 실제
 * CookieCsrfTokenRepository 가 그대로 살아 있다.
 *
 * <p>{@code maximum-pool-size=2}: 기존 RANDOM_PORT 테스트(CounselWebSocketIntegrationTest)는 중첩
 * {@code @TestConfiguration} 때문에 컨텍스트 캐시 키가 달라 이 클래스는 별도 컨텍스트를 띄운다.
 * 컨텍스트마다 Hikari 기본 10 커넥션을 잡아 테스트 컨테이너 PG 의 max_connections 를 넘겨
 * 무관한 테스트가 "too many clients already" 로 깨졌다(실측). 이 테스트는 DB 를 쓰지 않으므로
 * (기동 시 validate 만) 풀을 최소로 줄여 전체 스위트의 커넥션 예산을 침범하지 않는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.maximum-pool-size=2")
@ActiveProfiles("test")
class AuthCsrfRotationIntegrationTest extends PostgresTestSupport {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 고정하는 불변식 3가지:
     * <ol>
     *   <li>삭제 지시(Max-Age=0)가 아니다 — 회전이 삭제로 되돌아가면 #1200 재발(재로그인 첫 시도 403).</li>
     *   <li>값이 <b>직전 토큰과 다르다</b> — {@code generateToken} 을 {@code loadToken}(기존 값 재저장)으로
     *       "최적화"하면 이 변경의 보안 정당성인 "이전 토큰 폐기"가 조용히 사라진다.</li>
     *   <li>XSRF-TOKEN Set-Cookie 가 정확히 1개 — 필터(CsrfCookieFilter)와 컨트롤러가 이중 발급하면
     *       브라우저가 어느 값을 쓸지 응답 순서에 의존하게 된다.</li>
     * </ol>
     */
    @Test
    void 로그아웃_CSRF쿠키는_삭제가아니라_새값으로회전() {
        // 1단계: 브라우저의 CSRF 프라이밍과 동일 — 쿠키 없는 GET 에 필터가 XSRF-TOKEN 을 심어준다.
        //        (이 단계가 통과한다는 것 자체가 "필터 경로가 실제로 쿠키를 쓴다" 는 증거라,
        //         2단계의 Set-Cookie 1개 단언이 공허하지 않음을 보장한다.)
        ResponseEntity<String> primed = restTemplate.getForEntity("/api/users/me", String.class);
        assertThat(primed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String oldToken = extractCookieValue(primed, CSRF_COOKIE);
        assertThat(oldToken).as("필터가 쿠키 없는 요청에 XSRF-TOKEN 을 심어야 한다").isNotBlank();

        // 2단계: 받은 토큰을 쿠키+헤더로 double-submit 해 로그아웃(= 실제 브라우저 요청과 동일 경로).
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, CSRF_COOKIE + "=" + oldToken);
        headers.add(CSRF_HEADER, oldToken);
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> csrfSetCookies = csrfSetCookies(logout);
        assertThat(csrfSetCookies).as("필터×컨트롤러 이중 발급 금지").hasSize(1);
        assertThat(csrfSetCookies.get(0)).as("삭제 지시(Max-Age=0)가 아니어야 한다").doesNotContain("Max-Age=0");

        String newToken = extractCookieValue(logout, CSRF_COOKIE);
        assertThat(newToken)
                .as("삭제도 재사용도 아닌 '회전' — 이전 토큰은 폐기되고 새 값이 내려가야 한다")
                .isNotBlank()
                .isNotEqualTo(oldToken);
    }

    private static List<String> csrfSetCookies(ResponseEntity<String> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(CSRF_COOKIE + "="))
                .toList();
    }

    private static String extractCookieValue(ResponseEntity<String> response, String name) {
        String prefix = name + "=";
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(prefix))
                .map(header -> header.substring(prefix.length()).split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }
}
