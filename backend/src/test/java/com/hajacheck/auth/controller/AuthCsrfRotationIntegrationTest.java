package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    // ⚠️ 프로덕션 세션 쿠키명은 "SESSION"(Spring Session Redis)이지만, test 프로파일은
    // RedisAutoConfiguration 을 제외해 Spring Session 이 뜨지 않는다 → 톰캣 자체 세션 쿠키명이 쓰인다.
    // 여기서 고정하려는 것은 CSRF 쿠키 회전이지 세션 쿠키명이 아니므로 실행 환경의 이름을 그대로 따른다.
    private static final String SESSION_COOKIE = "JSESSIONID";
    // 이 클래스는 트랜잭션 롤백이 없다(실 HTTP) → 다른 테스트와 겹치지 않을 고유 이메일을 쓰고 직접 지운다.
    private static final String PW_EMAIL = "csrf-rotation-pw@haja.com";
    private static final String PW_CURRENT = "oldpass1";
    private static final String PW_NEW = "newpass1";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // TestRestTemplate 기본 팩토리(SimpleClientHttpRequestFactory)는 HttpURLConnection 기반이라
        // PATCH 를 보내지 못한다("Invalid HTTP method: PATCH"). 비밀번호 변경이 PATCH 라 JDK HttpClient
        // 팩토리로 교체한다(httpclient5 의존성을 추가하지 않기 위한 선택).
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail(PW_EMAIL).ifPresent(userRepository::delete);
    }

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

    /**
     * 같은 3불변식을 <b>비밀번호 변경(PATCH)</b>에 대해서도 고정한다(#1315). 회전 로직 자체는 로그아웃과
     * 공유(SessionTerminator)라 중복처럼 보이지만, 이 엔드포인트만 CSRF 예외 목록에 들어가거나 별도
     * 경로로 갈라지는 순간 회전 전제("여기 도달 = 쿠키가 이미 있었다")가 조용히 깨진다 — 그때
     * 필터×컨트롤러 이중 Set-Cookie 가 되고, 비밀번호를 바꾼 직후 재로그인 첫 POST 가 403 이 된다(#1200 재발).
     */
    @Test
    void 비밀번호변경_CSRF쿠키도_삭제가아니라_새값으로회전() {
        userRepository.save(User.createCompanyOwner(PW_EMAIL, "김대표", passwordEncoder.encode(PW_CURRENT)));

        // 1단계: CSRF 프라이밍.
        ResponseEntity<String> primed = restTemplate.getForEntity("/api/users/me", String.class);
        String csrfToken = extractCookieValue(primed, CSRF_COOKIE);
        assertThat(csrfToken).isNotBlank();

        // 2단계: 실제 로그인 → SESSION 쿠키 확보(인증 없이는 401 이라 컨트롤러에 도달하지 못한다).
        ResponseEntity<String> login = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"loginId\":\"" + PW_EMAIL + "\",\"password\":\"" + PW_CURRENT + "\"}",
                        jsonHeaders(csrfToken, null)),
                String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = extractCookieValue(login, SESSION_COOKIE);
        assertThat(sessionId).as("로그인 응답에 세션 쿠키가 있어야 한다").isNotBlank();

        // 3단계: 받은 토큰을 쿠키+헤더로 double-submit 해 비밀번호 변경(= 실제 브라우저 요청과 동일 경로).
        ResponseEntity<String> changed = restTemplate.exchange("/api/users/me/password", HttpMethod.PATCH,
                new HttpEntity<>("{\"currentPassword\":\"" + PW_CURRENT + "\",\"newPassword\":\"" + PW_NEW + "\"}",
                        jsonHeaders(csrfToken, sessionId)),
                String.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> csrfSetCookies = csrfSetCookies(changed);
        assertThat(csrfSetCookies).as("필터×컨트롤러 이중 발급 금지").hasSize(1);
        assertThat(csrfSetCookies.get(0)).as("삭제 지시(Max-Age=0)가 아니어야 한다").doesNotContain("Max-Age=0");
        assertThat(extractCookieValue(changed, CSRF_COOKIE))
                .as("삭제도 재사용도 아닌 '회전'")
                .isNotBlank()
                .isNotEqualTo(csrfToken);
    }

    private static HttpHeaders jsonHeaders(String csrfToken, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String cookie = CSRF_COOKIE + "=" + csrfToken;
        if (sessionId != null) {
            cookie += "; " + SESSION_COOKIE + "=" + sessionId;
        }
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add(CSRF_HEADER, csrfToken);
        return headers;
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
