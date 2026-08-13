package com.hajacheck.auth.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.config.DemoProperties;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 로그인 비활성(기본값) 계약(#1626) — 프로퍼티 오버라이드 없는 기본 컨텍스트에서
 * {@code app.demo.enabled=false} 가 실제로 404 로 fail-closed 하는지, 그리고 <b>킬스위치가 완전한지</b>
 * (#1626 P2-2a: 비활성이면 데모 계정의 일반 로그인도 차단)를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoLoginDisabledIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DemoProperties demoProperties;

    @Test
    void 기본_설정에서는_404_AUTH_DEMO_DISABLED다() throws Exception {
        mockMvc.perform(post("/api/auth/demo-login").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_DEMO_DISABLED"));
    }

    @Test
    void 킬스위치_비활성이면_데모계정은_일반_로그인도_401로_차단된다() throws Exception {
        // #1626 P2-2a — 스위치 off 면 /demo-login 은 404 지만, 시드된 데모 계정은 ACTIVE 라
        // 크레덴셜을 아는 사람이 /api/auth/login 으로 들어올 수 있었다(로그인 축엔 rate-limit 도 없음).
        // 킬스위치가 완전하려면 이 경로도 막아야 한다. 기본 컨텍스트는 enabled=false 라 login-available=false.
        String demoLoginId = demoProperties.getLoginId();
        userRepository.save(User.builder()
                .email(demoLoginId)
                .name("데모관리자")
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode("demo-known-pw1"))
                .status(UserStatus.ACTIVE)
                .build());

        // 자격증명은 맞지만(비밀번호 일치) 데모 비활성 상태라 계정 열거를 피해 일반 인증 실패와 동일한 401.
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(demoLoginId, "demo-known-pw1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }
}
