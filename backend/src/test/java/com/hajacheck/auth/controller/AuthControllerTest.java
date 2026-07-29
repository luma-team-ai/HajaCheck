package com.hajacheck.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.dto.LoginRequest;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자체 로그인 + /api/users/me MVC·시큐리티 통합 테스트.
 * oauth2Login 필터가 ClientRegistrationRepository 를 요구해 슬라이스(@WebMvcTest)로는 취약 →
 * 실제 시큐리티 필터체인·세션 저장·EntryPoint 를 그대로 태우는 @SpringBootTest+MockMvc 로 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User seededUser;

    @BeforeEach
    void setUp() {
        seededUser = userRepository.save(User.builder()
                .email("company@haja.com")
                .name("기업사용자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    void 로그인_올바른자격_200과사용자반환() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("company@haja.com", "pw123456"));

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("company@haja.com"))
                .andExpect(jsonPath("$.data.role").value("INSPECTOR"));
    }

    @Test
    void 로그인_성공시_세션ID회전_세션고정방어() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String beforeId = session.getId();
        String body = objectMapper.writeValueAsString(new LoginRequest("company@haja.com", "pw123456"));

        mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // changeSessionId() 로 로그인 전 세션 ID 가 무효화되고 새 ID 가 발급돼야 한다.
        assertThat(session.getId()).isNotEqualTo(beforeId);
    }

    @Test
    void 로그인_틀린비밀번호_401_AUTH_INVALID_CREDENTIALS() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("company@haja.com", "wrongpw"));

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 로그인_없는계정_401_AUTH_INVALID_CREDENTIALS() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("none@haja.com", "pw123456"));

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void 로그아웃_세션무효화_세션쿠키만료() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // SESSION 쿠키는 Max-Age=0 으로 만료돼야 한다.
                .andExpect(cookie().maxAge("SESSION", 0));

        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * #1200 회귀 — 이전 정책은 로그아웃 시 XSRF-TOKEN 도 Max-Age=0 으로 삭제했다. 그러면 로그아웃
     * 직후 /login 화면에서 곧바로 재로그인할 때 double-submit 쿠키가 없어 첫 POST 가 403 으로
     * 실패했다(전 계정 공통). 이제는 삭제 대신 새 값으로 회전한다 — 삭제 지시(Max-Age=0) 없이
     * 새 토큰 Set-Cookie 가 정확히 1개만 나가야 한다.
     */
    @Test
    void 로그아웃_CSRF쿠키는_삭제가아니라_새값으로회전() throws Exception {
        MockHttpSession session = new MockHttpSession();

        MvcResult result = mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> csrfSetCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith("XSRF-TOKEN="))
                .toList();

        assertThat(csrfSetCookies).hasSize(1);
        String setCookie = csrfSetCookies.get(0);
        // 삭제 지시가 아니어야 한다(Max-Age=0 / Expires 로 지우지 않음).
        assertThat(setCookie).doesNotContain("Max-Age=0");
        // 값이 비어 있지 않은 새 토큰이어야 한다.
        String value = setCookie.substring("XSRF-TOKEN=".length()).split(";", 2)[0];
        assertThat(value).isNotBlank();
    }

    @Test
    void 내정보_미인증_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 내정보_인증_200과내정보반환() throws Exception {
        LoginUser principal = new LoginUser(seededUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/users/me").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("company@haja.com"))
                .andExpect(jsonPath("$.data.name").value("기업사용자"));
    }
}
