package com.hajacheck.core.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.ai.dto.BriefingResponse;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * /api/ai/defect-explain MVC·시큐리티 통합 테스트(#228). AuthControllerTest 와 동일하게
 * oauth2Login 필터가 ClientRegistrationRepository 를 요구해 @SpringBootTest+MockMvc 로 검증.
 * 외부 FastAPI 호출은 AiProxyService 를 @MockBean 으로 스텁해 네트워크 의존을 제거한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiProxyControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AiProxyService aiProxyService;

    private LoginUser loginUser;

    private static final String REQUEST_BODY = """
            {"defect_type":"균열","severity_grade":"C","location":"1층 기둥","facility_type":"공동주택"}
            """;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("inspector@haja.com")
                .name("점검자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        loginUser = new LoginUser(user);
    }

    @Test
    void 하자설명_미인증_401() throws Exception {
        mockMvc.perform(post("/api/ai/defect-explain").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 하자설명_인증됨_AI서버성공_200과데이터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        when(aiProxyService.explainDefect(anyLong(), any()))
                .thenReturn(ApiResponse.ok(new DefectExplainResponse("철근 부식", "구조 내력 저하", "단면 보수 후 재도장")));

        mockMvc.perform(post("/api/ai/defect-explain").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cause").value("철근 부식"))
                .andExpect(jsonPath("$.data.risk").value("구조 내력 저하"))
                .andExpect(jsonPath("$.data.action").value("단면 보수 후 재도장"));
    }

    @Test
    void 하자설명_필수필드누락_400_INVALID_INPUT() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        String invalidBody = """
                {"defect_type":"","severity_grade":"C","location":"1층 기둥","facility_type":"공동주택"}
                """;

        mockMvc.perform(post("/api/ai/defect-explain").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 브리핑_미인증_401() throws Exception {
        mockMvc.perform(post("/api/ai/briefing").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 브리핑_인증됨_AI서버성공_200과데이터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        BriefingResponse response = new BriefingResponse(
                "이번 주 하자 발생이 감소했습니다.",
                "균열 유형 점검을 우선하세요.",
                new BriefingResponse.BriefingFacts(8L, 10L, -20, "감소", "균열", 1L));
        when(aiProxyService.briefing(loginUser.getUserId())).thenReturn(ApiResponse.ok(response));

        mockMvc.perform(post("/api/ai/briefing").with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.briefing").value("이번 주 하자 발생이 감소했습니다."))
                .andExpect(jsonPath("$.data.facts.thisWeekDefects").value(8))
                .andExpect(jsonPath("$.data.facts.trend").value("감소"));
    }
}
