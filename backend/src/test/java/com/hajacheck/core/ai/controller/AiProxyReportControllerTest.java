package com.hajacheck.core.ai.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
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
 * /api/ai/report MVC·시큐리티 통합 테스트(#239 / HAJA-192).
 * AiProxyControllerTest(defect-explain) 와 동일한 패턴 — 외부 FastAPI 호출은 AiProxyService 를
 * @MockBean 으로 스텁해 네트워크 의존을 제거한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiProxyReportControllerTest extends PostgresTestSupport {

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
            {
              "facility_info": {"name": "Haja APT", "location": "서울시"},
              "confirmed_defects": [
                {"defect_type": "균열", "location": "1동 1층 기둥", "severity_grade": "B", "description": "기둥 표면 수평 균열"}
              ],
              "on_mismatch": "regenerate"
            }
            """;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("inspector-report@haja.com")
                .name("점검자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        loginUser = new LoginUser(user);
    }

    @Test
    void 보고서생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/ai/report").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 보고서생성_인증됨_AI서버성공_200과데이터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());

        ReportResponse response = new ReportResponse(
                new ReportResponse.Overview("목적", "요약", "범위"),
                new ReportResponse.Summary("양호", 1, Map.of("A", 0, "B", 1, "C", 0, "D", 0, "E", 0),
                        List.of("1동 기둥 균열 발생")),
                new ReportResponse.Detail(List.of(new ReportResponse.DetailItem(
                        "균열", "1동 1층 기둥", "B", "기둥 표면 수평 균열", "건조 수축"))),
                new ReportResponse.Recommendation(
                        List.of(new ReportResponse.RecommendationItem("균열", "에폭시 수지 주입", "중", "제X조")),
                        List.of("지하주차장")),
                true);

        when(aiProxyService.generateReport(any())).thenReturn(ApiResponse.ok(response));

        mockMvc.perform(post("/api/ai/report").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.overview.purpose").value("목적"))
                .andExpect(jsonPath("$.data.summary.total_count").value(1))
                .andExpect(jsonPath("$.data.detail.items[0].defect_type").value("균열"))
                .andExpect(jsonPath("$.data.recommendation.items[0].legal_basis").value("제X조"))
                .andExpect(jsonPath("$.data.grounding_ok").value(true));
    }

    @Test
    void 보고서생성_확정하자목록비어있음_400_INVALID_INPUT() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        String invalidBody = """
                {
                  "facility_info": {"name": "Haja APT", "location": "서울시"},
                  "confirmed_defects": []
                }
                """;

        mockMvc.perform(post("/api/ai/report").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
