package com.hajacheck.membership.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 요금제 API (GET /api/plans) 통합 테스트 — permitAll 비인증 접근 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicPlanControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("비인증 사용자도 GET /api/plans 로 공개 요금제 목록을 조회할 수 있다")
    void getPublicPlans_unauthenticated_success() throws Exception {
        mockMvc.perform(get("/api/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plans").isArray())
                .andExpect(jsonPath("$.data.plans[0].name").value("FREE"))
                .andExpect(jsonPath("$.data.plans[0].priceMonthly").value(0))
                .andExpect(jsonPath("$.data.plans[1].name").value("STANDARD"))
                .andExpect(jsonPath("$.data.plans[1].priceMonthly").value(29000))
                .andExpect(jsonPath("$.data.plans[2].name").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.plans[2].priceMonthly").value(59000));
    }
}
