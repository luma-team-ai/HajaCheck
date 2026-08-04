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
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면(포털)별 로그인 엔드포인트의 서버 role 강제(#1514) 통합 테스트.
 *
 * <p>이 이슈 이전에는 세 화면이 같은 {@code POST /api/auth/login} 을 쓰고, role 판정은 프론트 훅이
 * "이미 발급된 세션을 logout 으로 되돌리는" 사후 처리였다 → curl 로 직접 치면 그대로 뚫렸다.
 *
 * <p><b>이 클래스가 고정하는 핵심 계약은 "403 이 나온다"가 아니라 "세션이 남지 않는다"</b> 다.
 * role 게이트를 {@code changeSessionId()} 뒤에 두면 403 은 그대로 나오면서도 회전된 세션이 남아
 * 같은 구멍이 형태만 바꿔 재현되므로, 차단 케이스마다
 * ① 403 + AUTH_ROLE_NOT_ALLOWED,
 * ② 그 세션으로 {@code GET /api/users/me} 재호출 시 401,
 * ③ 세션 ID 무회전(=세션 고정 방어에 도달하지 않았음) + SecurityContext 미저장,
 * ④ lastLoginAt 미갱신(로그인 성공이 아님)
 * 을 함께 검증한다.
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
     * 차단 케이스 공통 검증 — 403 + 세션 미발급(후속 /api/users/me 401) + 세션 ID 무회전 + lastLoginAt 미갱신.
     */
    private void assertBlockedWithoutSession(String loginPath, User user) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String sessionIdBeforeLogin = session.getId();

        mockMvc.perform(post(loginPath).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_ROLE_NOT_ALLOWED"));

        // ③ 세션 고정 방어(changeSessionId)에 도달조차 하지 않았다 = 게이트가 그 앞에 있다.
        assertThat(session.getId()).isEqualTo(sessionIdBeforeLogin);
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();

        // ② 403 응답에서 받은 세션을 그대로 다시 써도 인증된 세션이 아니다(핵심 증명).
        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        // ④ 로그인 성공이 아니므로 lastLoginAt 은 갱신되지 않는다.
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
