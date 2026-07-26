package com.hajacheck.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 사업자등록증 보호 파일 엔드포인트가 시큐리티 필터체인에서 인증을 요구하는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessLicenseFileControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 사업자등록증조회_비로그인_401() throws Exception {
        mockMvc.perform(get("/api/companies/{companyId}/business-license/{storageKey}",
                        10L, "business-registration/license.png"))
                .andExpect(status().isUnauthorized());
    }
}
