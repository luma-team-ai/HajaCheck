package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.LoggingPasswordResetMailSender;
import com.hajacheck.auth.support.PasswordResetMailSender;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PostgresTestSupport;
import com.hajacheck.support.RecordingPasswordResetMailSender;
import com.hajacheck.support.RecordingPasswordResetMailSender.Sent;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정 1·2단계 MVC 통합 테스트(실 PG Testcontainers + 시큐리티 필터체인 + @Async 실행기).
 *
 * <p>여기서만 검증 가능한 것들: 실제 HTTP 상태코드(401 금지 규약), CSRF double-submit, 요청 Host 가
 * 링크에 영향을 주지 않음(poisoning), 그리고 SMTP 미설정 컨텍스트가 정상 기동한다는 사실.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordResetIntegrationTest extends PostgresTestSupport {

    private static final String EMAIL = "reset-owner@haja.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RecordingPasswordResetMailSender mailSender;
    @Autowired
    private InMemoryRateLimiter rateLimiter;
    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        mailSender.reset();
        rateLimiter.reset();
        userRepository.save(User.createCompanyOwner(EMAIL, "김대표", passwordEncoder.encode("oldpass1")));
    }

    private String requestBody(Map<String, String> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private String requestLinkFor(String email) throws Exception {
        mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", email))))
                .andExpect(status().isOk());
        Sent sent = mailSender.awaitSent(Duration.ofSeconds(3));
        assertThat(sent).isNotNull();
        return sent.resetLink().substring(sent.resetLink().indexOf("token=") + "token=".length());
    }

    // ---------- 1단계 ----------

    @Test
    void 존재하는_이메일과_미존재_이메일의_응답이_바이트까지_동일하다() throws Exception {
        String existing = mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String missing = mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", "ghost@haja.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 계정 열거 방지 — 응답 본문이 완전히 같아야 한다.
        assertThat(existing).isEqualTo(missing);
        assertThat(existing).contains("\"requested\":true");
    }

    @Test
    void 응답_본문에_토큰이_없다() throws Exception {
        String token = requestLinkFor(EMAIL);

        String body = mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", EMAIL))))
                .andReturn().getResponse().getContentAsString();

        // 최초 P1 재발 방지 — 토큰은 메일로만 간다.
        assertThat(body).doesNotContain(token).doesNotContain("token");
    }

    @Test
    void 메일_링크는_요청_Host에_영향받지_않는다() throws Exception {
        // password-reset poisoning: nginx 가 Host 를 그대로 통과시키므로, 링크를 요청에서 유도하면
        // 피해자 메일에 공격자 도메인 링크가 심어진다.
        mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .header("Host", "evil.com")
                        .header("X-Forwarded-Host", "evil.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", EMAIL))))
                .andExpect(status().isOk());

        Sent sent = mailSender.awaitSent(Duration.ofSeconds(3));
        assertThat(sent.resetLink())
                .doesNotContain("evil.com")
                .startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void 이메일_형식이_아니면_400이고_401이_아니다() throws Exception {
        // 프론트 axios 가 401 을 로그인 강제 리다이렉트로 처리 → 재설정 화면에서 401 이 나가면 폼째로 튕긴다.
        mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 이메일_한도_초과_시_429다() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("email", EMAIL))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/auth/password-reset-request").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("email", EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOO_MANY_REQUESTS"));
    }

    // ---------- 2단계 ----------

    @Test
    void 정상_토큰으로_비밀번호가_변경된다() throws Exception {
        String token = requestLinkFor(EMAIL);

        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", token, "newPassword", "newpass1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset").value(true));

        User updated = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(passwordEncoder.matches("newpass1", updated.getPasswordHash())).isTrue();
    }

    @Test
    void 같은_토큰을_두_번_쓰면_400이다() throws Exception {
        String token = requestLinkFor(EMAIL);
        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", token, "newPassword", "newpass1"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", token, "newPassword", "newpass2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
    }

    @Test
    void 무효_토큰과_사용된_토큰의_응답이_동일하다() throws Exception {
        String usedToken = requestLinkFor(EMAIL);
        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", usedToken, "newPassword", "newpass1"))))
                .andExpect(status().isOk());

        String usedResponse = mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", usedToken, "newPassword", "newpass2"))))
                .andReturn().getResponse().getContentAsString();
        String invalidResponse = mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", "완전히-무효한-토큰", "newPassword", "newpass2"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(usedResponse).isEqualTo(invalidResponse);
    }

    @Test
    void 새_비밀번호가_가입과_동일한_정책으로_검증된다() throws Exception {
        String token = requestLinkFor(EMAIL);

        // 8자 미만 / 숫자 없음 — 가입(CompanySignupRequest)과 같은 규칙. 재설정이 느슨하면 정책 우회로가 된다.
        for (String weak : new String[]{"short1", "onlyletters"}) {
            mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("token", token, "newPassword", weak))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }

        // 정책 위반은 DTO 검증에서 걸러지므로 토큰이 소비되지 않았어야 한다(오타 한 번에 링크가 죽지 않음).
        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", token, "newPassword", "newpass1"))))
                .andExpect(status().isOk());
    }

    @Test
    void 재발급하면_이전_링크가_죽는다() throws Exception {
        String firstToken = requestLinkFor(EMAIL);
        String secondToken = requestLinkFor(EMAIL);

        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", firstToken, "newPassword", "newpass1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));

        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("token", secondToken, "newPassword", "newpass1"))))
                .andExpect(status().isOk());
    }

    // ---------- SMTP 미설정 기동 ----------

    @Test
    void SMTP_미설정_컨텍스트에서_로그_폴백이_선택된다() {
        // 이 테스트 컨텍스트 자체가 SMTP 미설정(spring.mail.host=빈값) 상태의 앱 기동이다 —
        // 즉 "SMTP 없이도 앱이 뜬다"가 이 클래스 전체의 전제로 이미 검증된다.
        assertThat(applicationContext.getBean(LoggingPasswordResetMailSender.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(PasswordResetMailSender.class).values())
                .anyMatch(LoggingPasswordResetMailSender.class::isInstance);
    }
}
