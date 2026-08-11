package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 원클릭 로그인(#1626) 통합 테스트 — 활성 환경에서의 실제 세션 발급 계약을 고정한다.
 *
 * <p>여기서만 검증 가능한 것: permitAll 배선(/api/auth/** 포괄), 바디 없는 POST 수락, 기존 로그인과
 * 동일한 응답 envelope + 세션 쿠키로 후속 요청 인증, {@code isDemo=true} 서버 계산, 전역 rate-limit
 * 의 실제 429 응답. (게이트 분기 자체는 {@code DemoLoginServiceTest} 가 단위로 고정.)
 *
 * <p>⚠️ <b>새 컨텍스트를 만들지 않는다</b> — {@code @SpringBootTest(properties=...)} 로 데모 스위치를
 * 켜면 이 클래스만의 컨텍스트(+Hikari 풀 10커넥션)가 캐시에 추가되는데, 이 스위트는 캐시된 컨텍스트가
 * 이미 많아 PG 테스트컨테이너 {@code max_connections}(100) 를 넘겨 무관한 테스트가
 * "too many clients already" 로 무너진다(실측). 대신 <b>기본 컨텍스트의 {@link DemoProperties} 빈을
 * 테스트 중에만 변경하고 반드시 원복</b>한다 — 이 빈은 매 요청 시점에 읽히므로 런타임 변경이 즉시 반영된다.
 *
 * <p>비밀번호 값은 테스트 더미다(실크레덴셜 아님 — 운영은 env {@code DEMO_ADMIN_PASSWORD}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoLoginIntegrationTest extends PostgresTestSupport {

    private static final String DEMO_LOGIN = "/api/auth/demo-login";
    private static final String LOGIN_ID = "demo-it@hajacheck.demo";
    private static final String PASSWORD = "demo-it-dummy1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private InMemoryRateLimiter rateLimiter;
    @Autowired
    private DemoProperties demoProperties;

    private boolean originalEnabled;
    private String originalLoginId;
    private String originalPassword;
    private int originalGlobalLimit;

    @BeforeEach
    void setUp() {
        // 전역 축 카운터라 테스트 간 누적되면 순서에 따라 무고한 테스트가 429 를 맞는다 — 매번 비운다.
        rateLimiter.reset();
        originalEnabled = demoProperties.isEnabled();
        originalLoginId = demoProperties.getLoginId();
        originalPassword = demoProperties.getAdminPassword();
        originalGlobalLimit = demoProperties.getLoginRateLimit().getGlobalLimit();
        demoProperties.setEnabled(true);
        demoProperties.setLoginId(LOGIN_ID);
        demoProperties.setAdminPassword(PASSWORD);
        // 429 검증이 bcrypt 로그인을 limit 번 반복하므로 한도를 줄인다.
        demoProperties.getLoginRateLimit().setGlobalLimit(3);
    }

    @AfterEach
    void tearDown() {
        // 공유(기본) 컨텍스트의 싱글톤 빈이다 — 원복하지 않으면 이후 다른 클래스의 테스트에 새어 나간다.
        demoProperties.setEnabled(originalEnabled);
        demoProperties.setLoginId(originalLoginId);
        demoProperties.setAdminPassword(originalPassword);
        demoProperties.getLoginRateLimit().setGlobalLimit(originalGlobalLimit);
    }

    private void seedDemoAdmin() {
        userRepository.save(User.builder()
                .email(LOGIN_ID)
                .name("데모관리자")
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    void 바디_없는_POST_한번으로_세션이_발급되고_isDemo가_true다() throws Exception {
        seedDemoAdmin();
        MockHttpSession session = new MockHttpSession();

        // 기존 POST /api/auth/login 과 동일한 UserResponse envelope — 프론트 #1627 이 이 계약으로 작업 중.
        MvcResult result = mockMvc.perform(post(DEMO_LOGIN).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(LOGIN_ID))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.isDemo").value(true))
                .andReturn();
        // 크레덴셜 비노출 — 응답 본문 어디에도 서버 보관 비밀번호가 실리지 않는다.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD);

        // 발급된 세션으로 보호 리소스 접근 — saveContext 까지 끝난 진짜 로그인 세션임을 증명.
        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDemo").value(true));
    }

    @Test
    void 데모_기능이_꺼져_있으면_404_AUTH_DEMO_DISABLED다() throws Exception {
        seedDemoAdmin();
        demoProperties.setEnabled(false);

        mockMvc.perform(post(DEMO_LOGIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_DEMO_DISABLED"));
    }

    @Test
    void 크레덴셜이_비어_있으면_켜져_있어도_404로_fail_closed_한다() throws Exception {
        seedDemoAdmin();
        demoProperties.setAdminPassword("");

        mockMvc.perform(post(DEMO_LOGIN).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_DEMO_DISABLED"));
    }

    @Test
    void 데모_계정이_시드되지_않았으면_401이다() throws Exception {
        // 스위치는 켜졌지만 시드가 안 된 환경 — 서버 크레덴셜 인증이 실패하므로 기존 통일 401.
        mockMvc.perform(post(DEMO_LOGIN).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 전역_한도_초과면_429다() throws Exception {
        seedDemoAdmin();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(DEMO_LOGIN).with(csrf())).andExpect(status().isOk());
        }

        mockMvc.perform(post(DEMO_LOGIN).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOO_MANY_REQUESTS"));
    }
}
