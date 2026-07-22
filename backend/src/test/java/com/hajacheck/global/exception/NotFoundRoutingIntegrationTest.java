package com.hajacheck.global.exception;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미존재 경로·미지원 메서드 라우팅 통합 테스트(#330).
 *
 * <p>GlobalExceptionHandlerTest 는 핸들러 메서드 계약만 보므로, 실제 디스패처 체인에서
 * NoResourceFoundException/HttpRequestMethodNotSupportedException 이 포괄
 * {@code @ExceptionHandler(Exception.class)} 가 아니라 전용 핸들러로 도달하는지는 여기서 검증한다(= 500 회귀 차단).
 *
 * <p>⚠️ 반드시 <b>인증된</b> 요청이어야 한다: SecurityConfig 가 {@code anyRequest().authenticated()} 라
 * 미인증 요청은 AuthorizationFilter 단계에서 401(UNAUTHORIZED)로 끊겨 DispatcherServlet 에 닿지 않는다.
 * 즉 이 핸들러가 실제로 타는 시나리오는 "인증된 사용자가 오타/삭제된 경로를 호출"하는 경우다.
 *
 * <p>AuthControllerTest 와 동일하게 슬라이스(@WebMvcTest) 대신 @SpringBootTest 를 쓴다 —
 * oauth2Login 필터가 ClientRegistrationRepository 를 요구하기 때문.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotFoundRoutingIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User seededUser;

    @BeforeEach
    void setUp() {
        seededUser = userRepository.save(User.builder()
                .email("notfound-test@haja.com")
                .name("테스트사용자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    @DisplayName("인증된 사용자의 미존재 경로 요청은 500이 아니라 404 + RESOURCE_NOT_FOUND 로 응답한다")
    void authenticatedRequestToUnmappedPath_returns404() throws Exception {
        LoginUser principal = new LoginUser(seededUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/this-path-does-not-exist").with(authentication(auth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("미인증 요청은 기존대로 401 — 시큐리티 필터가 먼저 끊어 404 핸들러까지 가지 않는다")
    void unauthenticatedRequestToUnmappedPath_returns401() throws Exception {
        mockMvc.perform(get("/api/this-path-does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("인증된 사용자가 지원하지 않는 메서드로 호출하면 500이 아니라 405 + METHOD_NOT_ALLOWED 로 응답한다")
    void authenticatedRequestWithUnsupportedMethod_returns405() throws Exception {
        // GET /api/inspections — 실사용자가 브라우저 주소창에 쳐서 겪은 회귀(POST만 존재, 목록 조회 미구현).
        LoginUser principal = new LoginUser(seededUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/inspections").with(authentication(auth)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
}
