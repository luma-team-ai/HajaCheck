package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면(포털)별 로그인 엔드포인트의 서버 role 강제(#1514) 통합 테스트.
 *
 * <p>이 이슈 이전에는 세 화면이 같은 {@code POST /api/auth/login} 을 쓰고, role 판정은 프론트 훅이
 * "이미 발급된 세션을 logout 으로 되돌리는" 사후 처리였다 → curl 로 직접 치면 그대로 뚫렸다.
 *
 * <p><b>이 클래스가 고정하는 핵심 계약은 "403 이 나온다"가 아니라 "세션이 남지 않는다"</b> 다.
 * role 게이트를 {@code changeSessionId()} 뒤에 두면 403 은 그대로 나오면서도 회전된 세션이 남아
 * 같은 구멍이 형태만 바꿔 재현된다. 그래서 차단 케이스마다 아래 넷을 함께 검증한다.
 *
 * <p><b>⚠️ 어느 단언이 무엇을 잡는지 — 하나도 "중복"이 아니다(지우지 말 것).</b>
 * <ul>
 *   <li><b>① 세션 미생성</b> (세션을 <b>주입하지 않은</b> 요청의 {@code getSession(false)} == null):
 *       <b>이 컨트롤러가</b> {@code getSession(true)} 를 게이트 <b>앞</b>에서 호출하는 회귀를 잡는
 *       <b>유일한</b> 단언이다(아래 ②③은 세션을 미리 주입하므로 "새 세션이 안 생긴다"를 증명하지 못한다).
 *       다만 openapi 가 계약한 "세션 생성 자체가 일어나지 않는다" 전체를 커버하지는 <b>않는다</b> —
 *       MockMvc 의 {@code with(csrf())} 가 CSRF 저장소를 테스트용으로 교체하므로,
 *       <b>필터 단계</b>에서 세션이 생기는 회귀(예: SecurityConfig 를 세션 기반 CSRF 저장소로 변경)는
 *       여기서 잡히지 않는다. 자세한 인과와 사각지대는 해당 단언 옆 주석 참조.</li>
 *   <li><b>② 세션 ID 무회전</b> + SecurityContext 미저장: 게이트가 {@code changeSessionId()} <b>뒤</b>로
 *       밀리는 회귀를 잡는 <b>유일한</b> 단언이다. 즉 실행 순서 계약을 실질적으로 고정하는 건 ②다.</li>
 *   <li><b>③ 그 세션으로 {@code GET /api/users/me} → 401</b>: {@code saveContext} 미호출을 잡는다.
 *       <b>순서 회귀는 잡지 못한다</b> — 게이트를 {@code changeSessionId()} 뒤로 옮겨도 saveContext 를
 *       건너뛰므로 ③은 그대로 통과한다(실측). 대신 "차단된 사용자가 손에 쥔 쿠키로는 아무것도 못 한다"는
 *       최종 사용자 관점의 계약을 고정한다.</li>
 *   <li><b>④ lastLoginAt 미갱신</b>: 로그인 성공 부수효과가 실패 경로로 새지 않음을 잡는다.</li>
 * </ul>
 *
 * <p>실 시큐리티 필터체인·세션 저장·EntryPoint 를 그대로 태워야 하므로 @SpringBootTest + MockMvc
 * (AuthControllerTest 와 동일한 이유·구성 — 슬라이스는 oauth2Login 필터 때문에 취약).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginRoleGateIntegrationTest extends PostgresTestSupport {

    private static final String COMPANY_LOGIN = "/api/auth/login";
    private static final String PLATFORM_ADMIN_LOGIN = "/api/auth/platform-admin/login";
    private static final String COUNSELOR_LOGIN = "/api/auth/counselor/login";
    private static final String PASSWORD = "pw123456";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User saveUser(Role role, String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name(role.name() + "계정")
                .role(role)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .build());
    }

    private String loginBody(String email) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD));
    }

    /**
     * 차단 케이스 공통 검증 — 클래스 javadoc 의 단언 ①~④ 를 모두 수행한다.
     * 세션 유무 두 상황을 각각 태워야 ①(미생성)과 ②(무회전)를 동시에 고정할 수 있다.
     */
    private void assertBlockedWithoutSession(String loginPath, User user) throws Exception {
        // ── 상황 A: 세션이 없는 상태로 들어온 요청 → 세션이 "생성조차" 되지 않아야 한다 ──
        // .session(...) 을 주지 않는 것이 이 블록의 전부다. 주는 순간 ① 은 증명 불가가 된다.
        MvcResult noSessionResult = mockMvc.perform(post(loginPath).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_ROLE_NOT_ALLOWED"))
                .andReturn();

        // ① 게이트가 getSession(true) 보다 앞이라는 증거.
        // 여기서 세션이 안 생기는 이유는 MockMvc 의 with(csrf()) 가 WebTestUtils.setCsrfTokenRepository() 로
        // 필터체인의 저장소를 TestCsrfTokenRepository 로 "교체"해 토큰을 request attribute 에 담기 때문이다
        // (프로덕션 CookieCsrfTokenRepository 는 이 테스트에서 아예 실행되지 않는다).
        // ⚠️ 사각지대: 그래서 SecurityConfig 를 HttpSessionCsrfTokenRepository 로 바꾸는 회귀는 이 단언이
        //    잡지 못한다 — 프로덕션에선 CsrfFilter 가 컨트롤러 도달 전에 세션을 만들어 openapi 계약이
        //    깨지는데 여기는 통과한다. 실제 CSRF 저장소가 무엇인지에 의존하는 검증은
        //    AuthCsrfRotationIntegrationTest(RANDOM_PORT, 후처리기가 손대지 않는 컨텍스트) 쪽 몫이다.
        assertThat(noSessionResult.getRequest().getSession(false))
                .as("차단 경로는 세션을 생성조차 하지 않는다")
                .isNull();

        // ── 상황 B: 이미 익명 세션이 있던 요청 → 그 세션이 인증 세션으로 승격되지 않아야 한다 ──
        MockHttpSession session = new MockHttpSession();
        String sessionIdBeforeLogin = session.getId();

        mockMvc.perform(post(loginPath).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ROLE_NOT_ALLOWED"));

        // ② 세션 고정 방어(changeSessionId)에 도달조차 하지 않았다 = 게이트가 그 앞에 있다.
        assertThat(session.getId())
                .as("차단 경로는 세션 ID 를 회전시키지 않는다(게이트가 changeSessionId 앞)")
                .isEqualTo(sessionIdBeforeLogin);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("차단 경로는 SecurityContext 를 세션에 저장하지 않는다")
                .isNull();

        // ③ 403 응답에서 받은 세션을 그대로 다시 써도 인증된 세션이 아니다.
        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        // ④ 로그인 성공이 아니므로 lastLoginAt 은 갱신되지 않는다(두 번 시도한 뒤에도).
        assertThat(user.getLastLoginAt()).isNull();
    }

    /**
     * 성공 케이스 공통 검증 — 200 + 세션 발급(같은 세션으로 /api/users/me 200) + 세션 ID 회전 + lastLoginAt 갱신.
     */
    private void assertLoggedInWithSession(String loginPath, User user) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String sessionIdBeforeLogin = session.getId();

        mockMvc.perform(post(loginPath).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(user.getEmail()))
                .andExpect(jsonPath("$.data.role").value(user.getRole().name()));

        // 세션 고정 방어 회귀 방지 — 성공 경로에서는 세션 ID 가 반드시 회전한다.
        assertThat(session.getId()).isNotEqualTo(sessionIdBeforeLogin);

        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(user.getEmail()));

        assertThat(user.getLastLoginAt()).isNotNull();
    }

    // ── 기업 엔드포인트(/api/auth/login) — ADMIN/INSPECTOR/USER 만 ──

    @Test
    void 기업로그인_PLATFORM_ADMIN_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COMPANY_LOGIN, saveUser(Role.PLATFORM_ADMIN, "pa-company@haja.com"));
    }

    @Test
    void 기업로그인_COUNSELOR_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COMPANY_LOGIN, saveUser(Role.COUNSELOR, "co-company@haja.com"));
    }

    @Test
    void 기업로그인_ADMIN_200과_세션발급() throws Exception {
        assertLoggedInWithSession(COMPANY_LOGIN, saveUser(Role.ADMIN, "admin-ok@haja.com"));
    }

    @Test
    void 기업로그인_INSPECTOR_200과_세션발급() throws Exception {
        assertLoggedInWithSession(COMPANY_LOGIN, saveUser(Role.INSPECTOR, "inspector-ok@haja.com"));
    }

    @Test
    void 기업로그인_USER_200과_세션발급() throws Exception {
        assertLoggedInWithSession(COMPANY_LOGIN, saveUser(Role.USER, "user-ok@haja.com"));
    }

    // ── 플랫폼 관리자 엔드포인트(/api/auth/platform-admin/login) — PLATFORM_ADMIN 만 ──

    @Test
    void 플랫폼관리자로그인_ADMIN_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(PLATFORM_ADMIN_LOGIN, saveUser(Role.ADMIN, "admin-pa@haja.com"));
    }

    @Test
    void 플랫폼관리자로그인_INSPECTOR_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(PLATFORM_ADMIN_LOGIN, saveUser(Role.INSPECTOR, "inspector-pa@haja.com"));
    }

    @Test
    void 플랫폼관리자로그인_USER_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(PLATFORM_ADMIN_LOGIN, saveUser(Role.USER, "user-pa@haja.com"));
    }

    @Test
    void 플랫폼관리자로그인_COUNSELOR_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(PLATFORM_ADMIN_LOGIN, saveUser(Role.COUNSELOR, "counselor-pa@haja.com"));
    }

    @Test
    void 플랫폼관리자로그인_PLATFORM_ADMIN_200과_세션발급() throws Exception {
        assertLoggedInWithSession(PLATFORM_ADMIN_LOGIN, saveUser(Role.PLATFORM_ADMIN, "pa-ok@haja.com"));
    }

    // ── 상담원 엔드포인트(/api/auth/counselor/login) — COUNSELOR 만 ──

    @Test
    void 상담원로그인_ADMIN_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COUNSELOR_LOGIN, saveUser(Role.ADMIN, "admin-co@haja.com"));
    }

    @Test
    void 상담원로그인_INSPECTOR_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COUNSELOR_LOGIN, saveUser(Role.INSPECTOR, "inspector-co@haja.com"));
    }

    @Test
    void 상담원로그인_USER_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COUNSELOR_LOGIN, saveUser(Role.USER, "user-co@haja.com"));
    }

    @Test
    void 상담원로그인_PLATFORM_ADMIN_403이고_세션이_남지않는다() throws Exception {
        assertBlockedWithoutSession(COUNSELOR_LOGIN, saveUser(Role.PLATFORM_ADMIN, "pa-co@haja.com"));
    }

    @Test
    void 상담원로그인_COUNSELOR_200과_세션발급() throws Exception {
        assertLoggedInWithSession(COUNSELOR_LOGIN, saveUser(Role.COUNSELOR, "counselor-ok@haja.com"));
    }

    // ── 자격증명 실패는 role 게이트보다 먼저 걸린다(계정 열거 방지 유지) ──

    @Test
    void 신규엔드포인트_틀린비밀번호_401_AUTH_INVALID_CREDENTIALS() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, "pa-wrongpw@haja.com");
        String body = objectMapper.writeValueAsString(
                new LoginRequest(platformAdmin.getEmail(), "wrongpw"));

        // role 은 맞지만 비밀번호가 틀리면 403 이 아니라 기존 401 통일 응답이어야 한다.
        mockMvc.perform(post(PLATFORM_ADMIN_LOGIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 신규엔드포인트_없는계정_401_AUTH_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(post(COUNSELOR_LOGIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("nobody@haja.com")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 신규엔드포인트_정지계정_401_AUTH_INVALID_CREDENTIALS() throws Exception {
        User suspended = userRepository.save(User.builder()
                .email("suspended-co@haja.com")
                .name("정지상담원")
                .role(Role.COUNSELOR)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(UserStatus.SUSPENDED)
                .build());

        // LockedException → AuthenticationException → 기존 401 통일(정지 사실을 role 게이트가 노출하지 않는다).
        mockMvc.perform(post(COUNSELOR_LOGIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(suspended.getEmail())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 신규엔드포인트_검증실패_400_INVALID_INPUT() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("", ""));

        mockMvc.perform(post(PLATFORM_ADMIN_LOGIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
