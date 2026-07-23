package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PngTestFixtures;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * BusinessLicenseOcrService 단위테스트(#557 / HAJA-324) — 파일 검증·rate-limit·AiProxyService 위임을
 * ImageSignatureValidatorTest/PasswordResetServiceTest(rate-limit 부분)와 동일한 방식으로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class BusinessLicenseOcrServiceTest {

    private Instant now;
    private InMemoryRateLimiter rateLimiter;
    private AuthProperties authProperties;
    private FileStorageProperties fileStorageProperties;

    @Mock
    private AiProxyService aiProxyService;

    private BusinessLicenseOcrService service;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-22T00:00:00Z");
        rateLimiter = new InMemoryRateLimiter(() -> now);
        authProperties = new AuthProperties();
        fileStorageProperties = new FileStorageProperties();

        service = new BusinessLicenseOcrService(aiProxyService, rateLimiter, authProperties, fileStorageProperties);
    }

    private static byte[] realPngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static MultipartFile pngFile() throws IOException {
        return new MockMultipartFile(
                "businessRegistrationFile", "license.png", "image/png", realPngBytes());
    }

    // ---------- 정상 프록시 ----------

    @Test
    void ocr_정상파일_base64인코딩후AiProxyService위임() throws IOException {
        byte[] bytes = realPngBytes();
        MultipartFile file = new MockMultipartFile("businessRegistrationFile", "license.png", "image/png", bytes);
        BusinessLicenseOcrResponse expected =
                new BusinessLicenseOcrResponse("123-45-67890", "하자체크", "김대표", "2020-01-15");
        when(aiProxyService.ocrBusinessLicense(any())).thenReturn(ApiResponse.ok(expected));

        ApiResponse<BusinessLicenseOcrResponse> response = service.ocr(file);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
        String expectedBase64 = Base64.getEncoder().encodeToString(bytes);
        verify(aiProxyService).ocrBusinessLicense(expectedBase64);
    }

    @Test
    void ocr_AI서버가_실패ApiResponse를_그대로_전파한다() throws IOException {
        // fail-safe: 예외를 삼키지 않고 프론트가 수동입력으로 폴백할 수 있도록 그대로 전달.
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.fail("LLM_INVALID_OUTPUT", "사업자등록증 인식 중 오류가 발생했습니다"));

        ApiResponse<BusinessLicenseOcrResponse> response = service.ocr(pngFile());

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void ocr_AI서버_연결실패_BusinessException_그대로_전파된다() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        assertThatThrownBy(() -> service.ocr(pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_UNREACHABLE);
    }

    // ---------- 파일 검증(신뢰 금지) ----------

    @Test
    void ocr_파일없음_FILE_REQUIRED() {
        assertThatThrownBy(() -> service.ocr(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_REQUIRED);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void ocr_빈파일_FILE_REQUIRED() {
        MultipartFile empty = new MockMultipartFile("businessRegistrationFile", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.ocr(empty))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_REQUIRED);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void ocr_허용되지않는MIME_PDF는_OCR스코프에서_거부된다() throws IOException {
        // FileStorageProperties 전체 허용목록엔 application/pdf 가 있지만, OCR은 래스터 이미지만
        // 지원한다(ImageSignatureValidator/RapidOCR 한계) — 회원가입 파일 저장과 다른 화이트리스트.
        MultipartFile pdf = new MockMultipartFile(
                "businessRegistrationFile", "license.pdf", "application/pdf", "PDFDATA".getBytes());

        assertThatThrownBy(() -> service.ocr(pdf))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_INVALID_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void ocr_텍스트파일_FILE_INVALID_TYPE() {
        MultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "a.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.ocr(file))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_INVALID_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void ocr_매직바이트불일치_확장자와_Content_Type만_PNG로_위조된_파일은_거부된다() {
        // 신뢰 금지 원칙 — Content-Type 헤더는 조작 가능하므로 실제 바이트 시그니처로 재검증(#557 요구사항).
        MultipartFile fakePng = new MockMultipartFile(
                "businessRegistrationFile", "fake.png", "image/png", "NOT-A-REAL-PNG".getBytes());

        assertThatThrownBy(() -> service.ocr(fakePng))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_INVALID_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void ocr_용량초과_FILE_TOO_LARGE() throws IOException {
        fileStorageProperties.setMaxSizeBytes(10); // 실제 PNG 바이트보다 작게 강제

        assertThatThrownBy(() -> service.ocr(pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_TOO_LARGE);
        verifyNoInteractions(aiProxyService);
    }

    /**
     * 디컴프레션 폭탄(픽셀 폭탄) 방어(PR머신 2차 검수 P1, #557/HAJA-324) — 바이트 크기는 수십 바이트로
     * 작지만 IHDR에 60000×60000(36억 픽셀, OCR 상한 50MP를 훌쩍 초과)을 선언한 PNG는 실제 픽셀 디코딩
     * 없이 헤더 단계에서 거부되어야 하고, 그 이후 단계(rate-limit·base64·AI 호출)로 전혀 진행되면 안 된다.
     */
    @Test
    void ocr_픽셀폭탄_바이트는작지만거대한선언크기_FILE_TOO_LARGE_AI서버미호출_카운터미소모() {
        byte[] bombPng = PngTestFixtures.craftPngWithDeclaredDimensions(60_000, 60_000);
        MultipartFile bomb = new MockMultipartFile("businessRegistrationFile", "bomb.png", "image/png", bombPng);
        int globalLimit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        int dailyLimit = authProperties.getBusinessLicenseOcrRateLimit().getDailyLimit();

        assertThatThrownBy(() -> service.ocr(bomb))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FILE_TOO_LARGE);

        verifyNoInteractions(aiProxyService);
        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:global", globalLimit, java.time.Duration.ofMinutes(1)))
                .as("픽셀폭탄은 헤더 단계에서 거부되어 분당 카운터를 소모하지 않았어야 한다")
                .isTrue();
        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:daily", dailyLimit, java.time.Duration.ofDays(1)))
                .as("픽셀폭탄은 헤더 단계에서 거부되어 일일 캡 카운터도 소모하지 않았어야 한다")
                .isTrue();
    }

    // ---------- rate-limit(전역, 비로그인이라 이메일 축 없음) ----------

    @Test
    void ocr_전역상한_초과시_429_AUTH_TOO_MANY_REQUESTS() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        int limit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.ocr(pngFile());
        }

        assertThatThrownBy(() -> service.ocr(pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void ocr_전역상한초과시_AI서버는_호출되지_않는다() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        int limit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.ocr(pngFile());
        }

        try {
            service.ocr(pngFile());
        } catch (BusinessException ignored) {
            // 기대된 429 — 아래 호출 횟수 검증이 핵심.
        }

        verify(aiProxyService, org.mockito.Mockito.times(limit)).ocrBusinessLicense(any());
    }

    @Test
    void ocr_창이_지나면_전역상한이_리셋된다() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        int limit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.ocr(pngFile());
        }

        now = now.plus(authProperties.getBusinessLicenseOcrRateLimit().getGlobalWindow()).plusSeconds(1);

        assertThat(service.ocr(pngFile()).success()).isTrue();
    }

    @Test
    void ocr_유효하지않은_파일은_rate_limit_카운터를_소모하지_않는다() {
        // 검증을 먼저 통과해야만 귀중한 rate-limit 예산을 쓴다 — 잘못된 파일 스팸이 정상 사용자의
        // 전역 상한을 갉아먹지 않도록(validate() 는 매직바이트 앞부분만 읽어 저렴하다).
        MultipartFile invalid = new MockMultipartFile(
                "businessRegistrationFile", "a.txt", "text/plain", "hello".getBytes());
        int limit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        int dailyLimit = authProperties.getBusinessLicenseOcrRateLimit().getDailyLimit();
        for (int i = 0; i < limit + 5; i++) {
            assertThatThrownBy(() -> service.ocr(invalid)).isInstanceOf(BusinessException.class);
        }

        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:global", limit, java.time.Duration.ofMinutes(1)))
                .as("검증 실패 요청은 분당 카운터를 전혀 소모하지 않았어야 한다")
                .isTrue();
        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:daily", dailyLimit, java.time.Duration.ofDays(1)))
                .as("검증 실패 요청은 일일 캡 카운터도 전혀 소모하지 않았어야 한다")
                .isTrue();
    }

    // ---------- rate-limit(일일 절대 캡, security-reviewer P1 — #557 재보고) ----------

    @Test
    void ocr_일일캡_초과시_429_AUTH_TOO_MANY_REQUESTS() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        authProperties.getBusinessLicenseOcrRateLimit().setDailyLimit(3);
        for (int i = 0; i < 3; i++) {
            service.ocr(pngFile());
        }

        assertThatThrownBy(() -> service.ocr(pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void ocr_분당캡은_통과했지만_일일캡에서_막힌다() throws IOException {
        // 분당 상한만으로는 지속 반복 시 일일 LLM 호출/과금이 무제한이 되는 P1 재현 — 분당 캡(기본 20)은
        // 여유롭게 두고 일일 캡만 낮춰, "분당은 안 걸렸는데 일일에서 막히는" 케이스를 고정한다.
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        authProperties.getBusinessLicenseOcrRateLimit().setDailyLimit(2);
        int perMinuteLimit = authProperties.getBusinessLicenseOcrRateLimit().getGlobalLimit();
        assertThat(perMinuteLimit).as("분당 캡이 일일 캡보다 넉넉해야 이 케이스가 성립한다").isGreaterThan(2);

        service.ocr(pngFile());
        service.ocr(pngFile());

        assertThatThrownBy(() -> service.ocr(pngFile()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        // 지금까지 분당 카운터는 3건(20 미만)뿐이라 분당 캡에는 아직 걸리지 않았어야 한다 — 이 실패가
        // 진짜 "일일 캡"에서 난 것인지 직접 확인(분당 캡이 우연히 먼저 걸린 거짓양성 방지).
        assertThat(rateLimiter.tryAcquire(
                        "rate:business-license-ocr:global", perMinuteLimit, java.time.Duration.ofMinutes(1)))
                .as("분당 캡은 아직 여유가 있어야 한다")
                .isTrue();
    }

    @Test
    void ocr_창이_지나면_일일캡이_리셋된다() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.ok(new BusinessLicenseOcrResponse("1", "2", "3", "4")));
        authProperties.getBusinessLicenseOcrRateLimit().setDailyLimit(2);
        service.ocr(pngFile());
        service.ocr(pngFile());
        assertThatThrownBy(() -> service.ocr(pngFile())).isInstanceOf(BusinessException.class);

        now = now.plus(authProperties.getBusinessLicenseOcrRateLimit().getDailyWindow()).plusSeconds(1);

        assertThat(service.ocr(pngFile()).success()).isTrue();
    }
}
