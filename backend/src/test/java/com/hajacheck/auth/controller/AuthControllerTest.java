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
    void 로그아웃_세션무효화_쿠키만료() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // SESSION·XSRF-TOKEN 쿠키가 Max-Age=0 으로 만료돼야 한다.
                .andExpect(cookie().maxAge("SESSION", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        assertThat(session.isInvalid()).isTrue();
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
