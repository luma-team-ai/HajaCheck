package com.hajacheck.auth.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기업 회원가입·아이디/비번 찾기 MVC 통합 테스트(실 PG Testcontainers + 시큐리티 필터체인).
 * FileStorage = 실제 LocalFileStorage(임시경로), TokenStore = in-memory fake(TestTokenStoreConfig).
 * CSRF double-submit → .with(csrf()).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CompanyAuthIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMultipartFile brnFile() {
        return new MockMultipartFile(
                "businessRegistrationFile", "brn.png", MediaType.IMAGE_PNG_VALUE, "PNGDATA".getBytes());
    }

    private MvcResult signup(String email, String brn, String companyName, String repName) throws Exception {
        return mockMvc.perform(multipart("/api/auth/companies")
                        .file(brnFile())
                        .param("email", email)
                        .param("password", "pass1234")
                        .param("companyName", companyName)
                        .param("businessRegistrationNumber", brn)
                        .param("representativeName", repName)
                        .param("address", "서울시 강남구 테헤란로 1")
                        .param("addressDetail", "10층")
                        .param("agreeTermsOfService", "true")
                        .param("agreePrivacyPolicy", "true")
                        .with(csrf()))
                .andReturn();
    }

    @Test
    void 회원가입_정상_201_및_상태조회() throws Exception {
        MvcResult result = signup("owner@haja.com", "111-22-33333", "(주)하자체크", "김민수");

        // 201 + 응답 필드 검증
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        org.assertj.core.api.Assertions.assertThat(data.get("companyId").asLong()).isPositive();
        org.assertj.core.api.Assertions.assertThat(data.get("status").asText()).isEqualTo("PENDING_REVIEW");
        org.assertj.core.api.Assertions.assertThat(data.get("maskedEmail").asText()).isEqualTo("owne***@haja.com");
        String signupToken = data.get("signupToken").asText();
        org.assertj.core.api.Assertions.assertThat(signupToken).isNotBlank();

        // 가입 상태 조회(승인 대기 새로고침)
        mockMvc.perform(get("/api/auth/companies/status").param("token", signupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.companyName").value("(주)하자체크"));
    }

    @Test
    void 회원가입_이메일중복_409() throws Exception {
        signup("dup@haja.com", "222-33-44444", "(주)회사A", "박대표");

        // 같은 이메일 + 다른 사업자번호 → 이메일 중복 409
        mockMvc.perform(multipart("/api/auth/companies")
                        .file(brnFile())
                        .param("email", "dup@haja.com")
                        .param("password", "pass1234")
                        .param("companyName", "(주)회사B")
                        .param("businessRegistrationNumber", "555-66-77777")
                        .param("representativeName", "다른대표")
                        .param("address", "부산시")
                        .param("agreeTermsOfService", "true")
                        .param("agreePrivacyPolicy", "true")
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_DUPLICATED"));
    }

    @Test
    void 회원가입_약관미동의_400_INVALID_INPUT() throws Exception {
        // @ModelAttribute 검증 실패는 BindException → INVALID_INPUT(400), 401 아님.
        mockMvc.perform(multipart("/api/auth/companies")
                        .file(brnFile())
                        .param("email", "noterm@haja.com")
                        .param("password", "pass1234")
                        .param("companyName", "(주)회사")
                        .param("businessRegistrationNumber", "123-45-67890")
                        .param("representativeName", "김대표")
                        .param("address", "서울")
                        .param("agreeTermsOfService", "false")
                        .param("agreePrivacyPolicy", "true")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 아이디찾기_매칭_200() throws Exception {
        signup("findme@haja.com", "333-44-55555", "(주)찾기회사", "최찾기");

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "businessRegistrationNumber", "333-44-55555",
                "representativeName", "최찾기"));

        mockMvc.perform(post("/api/auth/id-inquiry").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskedEmail").value("find***@haja.com"));
    }

    @Test
    void 아이디찾기_무매칭_404() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "businessRegistrationNumber", "999-99-99999",
                "representativeName", "없는사람"));

        mockMvc.perform(post("/api/auth/id-inquiry").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void 비밀번호찾기_1단계_2단계_정상() throws Exception {
        signup("pw@haja.com", "444-55-66666", "(주)비번회사", "정비번");

        // 1단계: 이메일+사업자번호 인증 → 재설정 토큰
        String inquiryBody = objectMapper.writeValueAsString(java.util.Map.of(
                "email", "pw@haja.com",
                "businessRegistrationNumber", "444-55-66666"));
        MvcResult inquiry = mockMvc.perform(post("/api/auth/password-inquiry").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(inquiryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                .andReturn();
        String resetToken = objectMapper.readTree(inquiry.getResponse().getContentAsString())
                .get("data").get("resetToken").asText();

        // 2단계: 토큰으로 비밀번호 재설정
        String resetBody = objectMapper.writeValueAsString(java.util.Map.of(
                "resetToken", resetToken,
                "newPassword", "newpass99"));
        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 토큰 단일 사용 — 재사용 시 400
        mockMvc.perform(post("/api/auth/password-reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
    }

    @Test
    void 비밀번호찾기_1단계_불일치_400_VERIFICATION_FAILED() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "email", "nobody@haja.com",
                "businessRegistrationNumber", "000-00-00000"));

        mockMvc.perform(post("/api/auth/password-inquiry").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_VERIFICATION_FAILED"));
    }

    @Test
    void 이메일중복확인_사용가능_200() throws Exception {
        mockMvc.perform(get("/api/auth/email-availability").param("email", "fresh@haja.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }
}
