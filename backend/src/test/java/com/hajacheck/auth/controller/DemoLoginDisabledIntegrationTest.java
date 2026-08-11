package com.hajacheck.auth.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 로그인 비활성(기본값) 계약(#1626) — 프로퍼티 오버라이드 없는 기본 컨텍스트에서
 * {@code app.demo.enabled=false} 가 실제로 404 로 fail-closed 하는지 고정한다(스위치 기본값 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoLoginDisabledIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 기본_설정에서는_404_AUTH_DEMO_DISABLED다() throws Exception {
        mockMvc.perform(post("/api/auth/demo-login").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_DEMO_DISABLED"));
    }
}
