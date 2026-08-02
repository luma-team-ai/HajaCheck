package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PostgresTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 후 비밀번호 변경 MVC 통합 테스트(#1315) — 실 PG Testcontainers + 실제 시큐리티 필터체인.
 *
 * <p>여기서만 검증 가능한 것들: 실제 HTTP 상태코드, <b>세션 principal 기준 소유자 식별</b>(요청으로
 * 타인을 지정할 수단이 없음), 성공 후 <b>현재 세션 종료</b>, CSRF 보호 대상 여부, 그리고 변경 후
 * 실제 로그인 가능 여부(옛 비밀번호 실패 / 새 비밀번호 성공).
 *
 * <p>인증은 대부분 <b>실제 로그인 요청</b>으로 만든 세션을 재사용한다(post-processor 로 principal 을
 * 꽂는 것보다 실사용 경로에 가깝고, 세션 무효화 검증에 실제 세션이 필요하다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordChangeIntegrationTest extends PostgresTestSupport {

    private static final String PATH = "/api/users/me/password";
    private static final String EMAIL = "pw-owner@haja.com";
    private static final String OTHER_EMAIL = "pw-other@haja.com";
    private static final String CURRENT_PASSWORD = "oldpass1";
    private static final String NEW_PASSWORD = "newpass1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private InMemoryRateLimiter rateLimiter;
    @Autowired
    private AuthProperties authProperties;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        owner = userRepository.save(
                User.createCompanyOwner(EMAIL, "김대표", passwordEncoder.encode(CURRENT_PASSWORD)));
        other = userRepository.save(
                User.createCompanyOwner(OTHER_EMAIL, "이대표", passwordEncoder.encode(CURRENT_PASSWORD)));
    }

    private String body(String currentPassword, String newPassword) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("currentPassword", currentPassword, "newPassword", newPassword));
    }

    /** 실제 로그인 요청으로 세션을 만든다(HttpSessionSecurityContextRepository 에 SecurityContext 저장). */
    private MockHttpSession login(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk());
        return session;
    }

    private String hashOf(String email) {
        return userRepository.findByEmail(email).orElseThrow().getPasswordHash();
    }

    // ---------- 성공 ----------

    @Test
    void 정상_변경시_200이고_새_비밀번호로만_로그인된다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(passwordEncoder.matches(NEW_PASSWORD, hashOf(EMAIL))).isTrue();

        // 옛 비밀번호로는 더 이상 로그인되지 않는다.
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, CURRENT_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));

        // 새 비밀번호로는 로그인된다.
        login(EMAIL, NEW_PASSWORD);
    }

    @Test
    void 응답_본문에_비밀번호가_드러나지_않는다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        String response = mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 평문·해시 어느 쪽도 응답에 실려선 안 된다.
        assertThat(response)
                .doesNotContain(CURRENT_PASSWORD)
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain(hashOf(EMAIL));
    }

    // ---------- 세션 종료(A안 — 현재 세션만) ----------

    @Test
    void 성공하면_현재_세션이_무효화되고_세션쿠키가_만료된다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                // 로그아웃과 동일 처리 — 브라우저에 남은 세션 쿠키를 즉시 만료시킨다.
                .andExpect(cookie().maxAge("SESSION", 0));

        assertThat(session.isInvalid())
                .as("비밀번호를 바꿨는데 예전 자격증명으로 발급된 세션이 살아있으면 안 된다")
                .isTrue();
    }

    @Test
    void 실패하면_세션은_유지된다() throws Exception {
        // 실패까지 세션을 끊으면 오타 한 번에 로그아웃되는 UX 사고가 난다.
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrongpw1", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(session.isInvalid()).isFalse();
    }

    // ---------- 소유자 식별(principal 기준) ----------

    @Test
    void 요청으로_타인을_지정할_수_없어_남의_비밀번호는_바뀌지_않는다() throws Exception {
        String otherHashBefore = hashOf(OTHER_EMAIL);
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        // 바디에 userId/email 을 실어 보내도 무시된다 — 대상은 세션 principal 로만 결정된다(IDOR 차단).
        String spoofed = objectMapper.writeValueAsString(Map.of(
                "currentPassword", CURRENT_PASSWORD,
                "newPassword", NEW_PASSWORD,
                "userId", other.getId(),
                "email", OTHER_EMAIL));

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofed))
                .andExpect(status().isOk());

        // 바뀐 것은 요청자 본인뿐.
        assertThat(passwordEncoder.matches(NEW_PASSWORD, hashOf(EMAIL))).isTrue();
        assertThat(hashOf(OTHER_EMAIL)).isEqualTo(otherHashBefore);
        // 타인은 여전히 원래 비밀번호로 로그인된다.
        login(OTHER_EMAIL, CURRENT_PASSWORD);
    }

    // ---------- 인증·CSRF 경계 ----------

    @Test
    void 미인증이면_401이다() throws Exception {
        mockMvc.perform(patch(PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashOf(EMAIL))).isTrue();
    }

    @Test
    void CSRF_토큰이_없으면_403이다() throws Exception {
        // 이 엔드포인트가 CSRF 예외 목록에 들어가면 안 된다 — 세션 쿠키만으로 타 사이트가 비밀번호를
        // 바꿔버릴 수 있고, SessionTerminator 의 "CSRF 회전" 전제도 함께 깨진다.
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isForbidden());

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashOf(EMAIL))).isTrue();
    }

    // ---------- 에러 계약 ----------

    @Test
    void 현재_비밀번호가_틀리면_401이다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrongpw1", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashOf(EMAIL))).isTrue();
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400이다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, CURRENT_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_UNCHANGED"));
    }

    @Test
    void 소셜_전용_계정은_400이다() throws Exception {
        // passwordHash=null 이지만 회사에 배선된 ACTIVE 소셜 계정(WAITING 이면 필터가 먼저 403 으로 끊는다).
        User socialUser = userRepository.save(User.builder()
                .email("pw-social@haja.com")
                .name("소셜사용자")
                .role(Role.USER)
                .socialProvider(SocialProvider.KAKAO)
                .socialId("pw-social-id")
                .companyId(owner.getCompanyId())
                .status(UserStatus.ACTIVE)
                .build());
        LoginUser principal = new LoginUser(socialUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        mockMvc.perform(patch(PATH).with(authentication(auth)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("anything1", NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_NOT_SET"));

        assertThat(userRepository.findByEmail("pw-social@haja.com").orElseThrow().hasPassword()).isFalse();
    }

    @Test
    void 새_비밀번호가_가입과_동일한_정책으로_검증된다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        // 8자 미만 / 숫자 없음 — 가입(CompanySignupRequest)·재설정(PasswordResetRequest)과 같은 규칙.
        // 이 경로가 느슨하면 여기가 정책 우회로가 된다.
        for (String weak : new String[]{"short1", "onlyletters"}) {
            mockMvc.perform(patch(PATH).session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(CURRENT_PASSWORD, weak)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashOf(EMAIL))).isTrue();
    }

    @Test
    void 현재_비밀번호가_비어있으면_400이다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);

        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 한도_초과_시_429다() throws Exception {
        MockHttpSession session = login(EMAIL, CURRENT_PASSWORD);
        // 한도는 설정값에서 읽는다(하드코딩하면 설정이 바뀔 때 테스트가 조용히 무의미해진다).
        int limit = authProperties.getPasswordChangeRateLimit().getUserLimit();

        for (int i = 0; i < limit; i++) {
            mockMvc.perform(patch(PATH).session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("wrongpw1", NEW_PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }

        // 현재 비밀번호가 맞아도 한도에 걸린다 — 무차별 대입 방어라 시도 자체를 센다.
        mockMvc.perform(patch(PATH).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOO_MANY_REQUESTS"));

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashOf(EMAIL))).isTrue();
    }
}
