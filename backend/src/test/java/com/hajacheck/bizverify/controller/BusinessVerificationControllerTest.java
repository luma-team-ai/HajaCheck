package com.hajacheck.bizverify.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /api/auth/business-verification MVC·시큐리티 통합 테스트(#648).
 *
 * <p>BusinessLicenseOcrControllerTest와 동일 패턴 — 외부 국세청 호출은 NtsBusinessVerifyClient 를
 * @MockBean 으로 스텁해 네트워크 의존을 제거한다(그 client의 status+validate 조합 판정 자체는
 * NtsBusinessVerifyClientTest 가 담당). 이 엔드포인트는 <b>비로그인 공개 API</b>라 인증 관련 테스트가
 * 없는 대신, SecurityConfig permitAll 실동작(회귀 시 401)과 rate-limit(429) 을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessVerificationControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private InMemoryRateLimiter rateLimiter;
    @Autowired
    private BizVerifyProperties bizVerifyProperties;

    @MockBean
    private NtsBusinessVerifyClient ntsBusinessVerifyClient;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
    }

    private String requestBody(String brn, String rep, LocalDate startDate) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "businessRegistrationNumber", brn,
                "representativeName", rep,
                "businessStartDate", startDate.toString()));
    }

    @Test
    void 비로그인_진위확인_성공_200과_VERIFIED반환() throws Exception {
        // permitAll 회귀 방지(리뷰 P2 관례) — 이 요청은 인증 헤더/세션 없이 그대로 통과해야 한다.
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("123-45-67890", "김대표", LocalDate.of(2020, 1, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("VERIFIED"));
    }

    @Test
    void 국세청_미등록_NOT_REGISTERED반환() throws Exception {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.NOT_REGISTERED);

        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("123-45-67890", "김대표", LocalDate.of(2020, 1, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NOT_REGISTERED"));
    }

    @Test
    void 국세청장애_UNAVAILABLE반환_200이지_에러가아니다() throws Exception {
        // fail-open: 외부 장애가 이 API 자체 실패(4xx/5xx)로 전파되지 않는다 — 결과 코드로만 안내.
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SKIPPED);

        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("123-45-67890", "김대표", LocalDate.of(2020, 1, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("UNAVAILABLE"));
    }

    @Test
    void 사업자등록번호_형식불량_400_INVALID_INPUT() throws Exception {
        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("not-a-brn", "김대표", LocalDate.of(2020, 1, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 대표자명_누락_400_INVALID_INPUT() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "businessRegistrationNumber", "123-45-67890",
                "businessStartDate", "2020-01-01"));

        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 전역상한초과시_429() throws Exception {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        int limit = bizVerifyProperties.getRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("123-45-67890", "김대표", LocalDate.of(2020, 1, 1))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/auth/business-verification").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("123-45-67890", "김대표", LocalDate.of(2020, 1, 1))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOO_MANY_REQUESTS"));
    }
}
