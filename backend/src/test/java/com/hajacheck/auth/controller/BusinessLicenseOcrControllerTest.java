package com.hajacheck.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PngTestFixtures;
import com.hajacheck.support.PostgresTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /api/auth/business-license/ocr MVC·시큐리티 통합 테스트(#557 / HAJA-324).
 *
 * <p>AiProxyControllerTest(defect-explain)와 동일 패턴 — 외부 FastAPI 호출은 AiProxyService 를
 * @MockBean 으로 스텁해 네트워크 의존을 제거한다. 이 엔드포인트는 <b>비로그인 공개 API</b>라 인증
 * 관련 테스트가 없는 대신, SecurityConfig permitAll 실동작(회귀 시 401)과 rate-limit(429) 을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessLicenseOcrControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InMemoryRateLimiter rateLimiter;
    @Autowired
    private AuthProperties authProperties;

    @MockBean
    private AiProxyService aiProxyService;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
    }

    private static byte[] realPngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private MockMultipartFile pngPart() throws IOException {
        return new MockMultipartFile(
                "businessRegistrationFile", "license.png", "image/png", realPngBytes());
    }

    @Test
    void OCR_비로그인_성공_200과데이터반환() throws Exception {
        // permitAll 회귀 방지(리뷰 P2 관례) — 이 요청은 인증 헤더/세션 없이 그대로 통과해야 한다.
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(
                        new BusinessLicenseOcrResponse("123-45-67890", "하자체크", "김대표", "2020-01-15")));

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(pngPart())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.businessRegistrationNumber").value("123-45-67890"))
                .andExpect(jsonPath("$.data.companyName").value("하자체크"))
                .andExpect(jsonPath("$.data.representativeName").value("김대표"))
                .andExpect(jsonPath("$.data.businessStartDate").value("2020-01-15"));
    }

    @Test
    void OCR_파일파트누락_400_FILE_REQUIRED() throws Exception {
        mockMvc.perform(multipart("/api/auth/business-license/ocr").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FILE_REQUIRED"));
    }

    @Test
    void OCR_허용되지않는MIME_400_FILE_INVALID_TYPE() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "businessRegistrationFile", "a.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(textFile)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_INVALID_TYPE"));
    }

    @Test
    void OCR_매직바이트불일치_위조파일_400_FILE_INVALID_TYPE() throws Exception {
        MockMultipartFile fakePng = new MockMultipartFile(
                "businessRegistrationFile", "fake.png", "image/png", "NOT-A-REAL-PNG".getBytes());

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(fakePng)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_INVALID_TYPE"));
    }

    @Test
    void OCR_AI서버가_인식실패를_반환하면_success_false로_그대로_전달된다() throws Exception {
        // Fail-safe — 예외를 삼키지 않고 프론트가 수동입력 폴백을 판단할 수 있도록 그대로 노출.
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.fail("LLM_INVALID_OUTPUT", "사업자등록증 인식 중 오류가 발생했습니다"));

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(pngPart())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("LLM_INVALID_OUTPUT"));
    }

    @Test
    void OCR_픽셀폭탄_바이트는작지만거대한선언크기_400_FILE_TOO_LARGE_AI서버미호출() throws Exception {
        // PR머신 2차 검수 P1(#557/HAJA-324) — 바이트 크기 상한(10MB)·매직바이트만으로는 헤더에 거대한
        // 가로×세로를 선언한 작은 파일을 막지 못한다. 실제 픽셀 디코딩 없이 헤더 단계에서 거부되어야 한다.
        MockMultipartFile bomb = new MockMultipartFile(
                "businessRegistrationFile", "bomb.png", "image/png",
                PngTestFixtures.craftPngWithDeclaredDimensions(60_000, 60_000));

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(bomb)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));

        org.mockito.Mockito.verifyNoInteractions(aiProxyService);
    }

    @Test
    void OCR_전역상한초과시_429() throws Exception {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "2020-01-15")));
        int limit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            mockMvc.perform(multipart("/api/auth/business-license/ocr")
                            .file(pngPart())
                            .with(csrf()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(multipart("/api/auth/business-license/ocr")
                        .file(pngPart())
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOO_MANY_REQUESTS"));
    }
}
