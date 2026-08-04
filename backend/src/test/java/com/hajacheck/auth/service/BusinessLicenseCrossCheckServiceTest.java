package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
import java.time.Duration;
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
 * BusinessLicenseCrossCheckService 단위테스트(#1469 / HAJA-650).
 *
 * <p>고정하려는 계약은 두 가지다: ①<b>MISMATCH 만 차단</b>이고 나머지 5종은 통과(fail-open),
 * ②<b>이 서비스는 어떤 경우에도 예외를 위로 던지지 않는다</b>(가입 자체를 막으면 안 된다).
 * rate-limit 키가 공개 프리필({@code rate:business-license-ocr:*})과 분리돼 있는지도 함께 고정한다 —
 * 이 분리가 깨지면 비로그인 프리필 반복 호출만으로 가입 심사 게이트를 끌 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class BusinessLicenseCrossCheckServiceTest {

    private static final String INPUT_BRN = "1234567890";

    private InMemoryRateLimiter rateLimiter;
    private AuthProperties authProperties;
    private FileStorageProperties fileStorageProperties;

    @Mock
    private AiProxyService aiProxyService;

    private BusinessLicenseCrossCheckService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        rateLimiter = new InMemoryRateLimiter(() -> now);
        authProperties = new AuthProperties();
        fileStorageProperties = new FileStorageProperties();

        service = new BusinessLicenseCrossCheckService(
                aiProxyService, rateLimiter, authProperties, fileStorageProperties);
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

    private void stubOcrBrn(String recognizedBrn) {
        when(aiProxyService.ocrBusinessLicense(any())).thenReturn(ApiResponse.ok(
                new BusinessLicenseOcrResponse(recognizedBrn, "(주)하자체크", "김민수", "2020-01-01")));
    }

    // ---------- MATCH / MISMATCH ----------

    @Test
    void 대조_같은사업자번호_MATCH() throws IOException {
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_OCR값에_하이픈이_섞여도_MATCH() throws IOException {
        // 등록증 서식 그대로 읽히는 게 정상 — 하이픈을 지우지 않으면 정상 사용자가 전부 차단된다.
        stubOcrBrn("123-45-67890");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_OCR값에_공백_마침표가_섞여도_MATCH() throws IOException {
        // 하이픈만 제거하는 normalizeBrn 을 그대로 재사용하면 여기서 오탐이 난다(하이픈 외 구분자 인식).
        stubOcrBrn(" 123 45.67890 ");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_다른사업자번호_MISMATCH() throws IOException {
        // #1469 의 본체 — 남의 등록증 업로드 + 실재하는 제3자 정보 수기 입력.
        stubOcrBrn("999-88-77665");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    // ---------- OCR_NO_BRN (인식 결과를 신뢰할 수 없음 → 통과) ----------

    @Test
    void 대조_OCR이_사업자번호를_주지않으면_OCR_NO_BRN() throws IOException {
        stubOcrBrn(null);

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    @Test
    void 대조_OCR값이_공백문자열이면_OCR_NO_BRN() throws IOException {
        stubOcrBrn("   ");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    @Test
    void 대조_OCR값_자릿수가_10이_아니면_OCR_NO_BRN() throws IOException {
        // 부분 인식(9자리) — 실제로는 같은 회사일 수 있으므로 차단하지 않는다.
        stubOcrBrn("123456789");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    // ---------- OCR_FAILED (외부 장애 → 통과, 예외 미전파) ----------

    @Test
    void 대조_AI서버_예외는_삼키고_OCR_FAILED() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        // 프리필(BusinessLicenseOcrService)과 정반대 — 여기선 예외가 위로 새면 가입 자체가 막힌다.
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    @Test
    void 대조_AI서버_실패ApiResponse도_OCR_FAILED() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any()))
                .thenReturn(ApiResponse.fail("LLM_INVALID_OUTPUT", "사업자등록증 인식 중 오류가 발생했습니다"));

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    @Test
    void 대조_예상치못한_런타임예외도_삼키고_OCR_FAILED() throws IOException {
        when(aiProxyService.ocrBusinessLicense(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    // ---------- SKIPPED_UNSUPPORTED_TYPE (AI 호출 자체가 없음) ----------

    @Test
    void 대조_PDF업로드는_AI호출없이_SKIPPED_UNSUPPORTED_TYPE() {
        // 회원가입 파일 허용목록엔 PDF 가 있지만 OCR 은 래스터 이미지만 다룬다 — 대조 없이 통과시킨다
        // (여기서 FILE_INVALID_TYPE 을 던지면 이 게이트가 가입 파일 정책을 임의로 좁히게 된다).
        MultipartFile pdf = new MockMultipartFile(
                "businessRegistrationFile", "license.pdf", "application/pdf", "PDFDATA".getBytes());

        assertThat(service.crossCheck(pdf, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void 대조_매직바이트위조_PNG는_AI호출없이_SKIPPED() {
        MultipartFile fakePng = new MockMultipartFile(
                "businessRegistrationFile", "fake.png", "image/png", "NOT-A-REAL-PNG".getBytes());

        assertThat(service.crossCheck(fakePng, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void 대조_파일없음_빈파일은_AI호출없이_SKIPPED() {
        MultipartFile empty = new MockMultipartFile(
                "businessRegistrationFile", "a.png", "image/png", new byte[0]);

        assertThat(service.crossCheck(null, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        assertThat(service.crossCheck(empty, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void 대조_용량초과는_AI호출없이_SKIPPED() throws IOException {
        fileStorageProperties.setMaxSizeBytes(10); // 실제 PNG 바이트보다 작게 강제

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void 대조_픽셀폭탄은_AI호출없이_SKIPPED_카운터도_미소모() {
        // 사전 검사가 rate-limit 획득보다 먼저라, 폭탄 파일이 정상 가입자의 대조 예산을 갉아먹지 않는다.
        byte[] bombPng = PngTestFixtures.craftPngWithDeclaredDimensions(60_000, 60_000);
        MultipartFile bomb = new MockMultipartFile("businessRegistrationFile", "bomb.png", "image/png", bombPng);
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();

        assertThat(service.crossCheck(bomb, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);

        verifyNoInteractions(aiProxyService);
        assertThat(remainingGlobal(limit)).isEqualTo(limit.getGlobalLimit());
    }

    // ---------- RATE_LIMITED ----------

    @Test
    void 대조_분당캡_소진시_AI호출없이_RATE_LIMITED() throws IOException {
        stubOcrBrn("1234567890");
        int globalLimit = authProperties.getBusinessLicenseCrossCheckRateLimit().getGlobalLimit();
        for (int i = 0; i < globalLimit; i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
        }

        // 캡을 넘겨도 예외가 아니라 통과 판정이다(fail-open) — 여기서 429 를 던지면 가입이 막힌다.
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
        verify(aiProxyService, times(globalLimit)).ocrBusinessLicense(any());
    }

    @Test
    void 대조_일일캡_소진시_RATE_LIMITED() throws IOException {
        // 분당 캡에 걸리지 않도록 분당 축을 크게 열고 일일 축만 소진시킨다.
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        limit.setGlobalLimit(10_000);
        limit.setDailyLimit(3);
        stubOcrBrn("1234567890");
        for (int i = 0; i < 3; i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
    }

    @Test
    void 대조_분당캡에서_막히면_일일캡_예산을_소모하지_않는다() throws IOException {
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        limit.setGlobalLimit(1);
        stubOcrBrn("1234567890");

        service.crossCheck(pngFile(), INPUT_BRN);                       // 분당 1회 소진(일일도 1회 소진)
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))            // 분당에서 차단
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);

        // 분당 축에서 막힌 2회가 일일 축 카운터를 건드렸다면 남은 예산이 그만큼 줄어든다.
        assertThat(remainingDaily(limit))
                .as("분당 축에서 막힌 요청은 일일 축 예산을 소모하지 않아야 한다")
                .isEqualTo(limit.getDailyLimit() - 1);
    }

    // ---------- 공개 프리필 캡과의 분리(핵심 보안 요구사항) ----------

    @Test
    void 대조_공개프리필_캡이_소진돼도_대조는_영향받지_않는다() throws IOException {
        // 공유 키였다면 비로그인 프리필 API 를 반복 호출하는 것만으로 이 게이트를 끌 수 있다.
        AuthProperties.BusinessLicenseOcrRateLimit prefill = authProperties.getBusinessLicenseOcrRateLimit();
        for (int i = 0; i < prefill.getGlobalLimit(); i++) {
            rateLimiter.tryAcquire("rate:business-license-ocr:global",
                    prefill.getGlobalLimit(), prefill.getGlobalWindow());
        }
        for (int i = 0; i < prefill.getDailyLimit(); i++) {
            rateLimiter.tryAcquire("rate:business-license-ocr:daily",
                    prefill.getDailyLimit(), prefill.getDailyWindow());
        }
        stubOcrBrn("999-88-77665");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .as("프리필 캡 소진이 대조 게이트를 무력화하면 안 된다")
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    @Test
    void 대조_전용_rate_limit_키를_사용한다() throws IOException {
        stubOcrBrn("1234567890");
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();

        service.crossCheck(pngFile(), INPUT_BRN);

        // 대조 전용 키만 1회 소모되고 프리필 키는 그대로여야 한다.
        assertThat(remainingGlobal(limit)).isEqualTo(limit.getGlobalLimit() - 1);
        AuthProperties.BusinessLicenseOcrRateLimit prefill = authProperties.getBusinessLicenseOcrRateLimit();
        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:global",
                prefill.getGlobalLimit(), prefill.getGlobalWindow()))
                .as("대조가 프리필 키를 소모하면 안 된다")
                .isTrue();
    }

    // ---------- AI 위임 형태 ----------

    @Test
    void 대조_파일바이트를_base64로_인코딩해_AiProxyService에_위임한다() throws IOException {
        byte[] bytes = realPngBytes();
        MultipartFile file = new MockMultipartFile("businessRegistrationFile", "license.png", "image/png", bytes);
        stubOcrBrn("1234567890");

        service.crossCheck(file, INPUT_BRN);

        verify(aiProxyService).ocrBusinessLicense(Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * 대조 전용 분당 카운터의 잔여 예산 — {@code tryAcquire} 는 호출 자체가 카운터를 늘리므로,
     * "몇 번 더 통과하는가"를 세는 방식으로 소모량을 역산한다(창은 고정 시계라 만료되지 않는다).
     */
    private int remainingGlobal(AuthProperties.BusinessLicenseCrossCheckRateLimit limit) {
        return countRemaining("rate:business-license-crosscheck:global",
                limit.getGlobalLimit(), limit.getGlobalWindow());
    }

    private int remainingDaily(AuthProperties.BusinessLicenseCrossCheckRateLimit limit) {
        return countRemaining("rate:business-license-crosscheck:daily",
                limit.getDailyLimit(), limit.getDailyWindow());
    }

    private int countRemaining(String key, int limit, Duration window) {
        int remaining = 0;
        while (rateLimiter.tryAcquire(key, limit, window)) {
            remaining++;
        }
        return remaining;
    }
}
