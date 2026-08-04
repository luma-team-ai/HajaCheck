package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
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
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import com.hajacheck.core.media.support.PdfFirstPageRasterizer;

/**
 * BusinessLicenseCrossCheckService 단위테스트(#1469 / HAJA-650).
 *
 * <p>고정하려는 계약:
 * <ol>
 *   <li><b>분기 근거는 선언 Content-Type 이 아니라 매직바이트</b> — 파트 헤더를 {@code application/pdf} 로
 *       바꿔도 실제 JPEG 면 대조가 그대로 수행된다(2026-08-04 리뷰 P1의 100% 우회 경로).</li>
 *   <li><b>PDF 도 첫 페이지를 래스터화해 대조한다</b> — "PDF는 대조 불가"라는 예외 구멍을 없앤다.</li>
 *   <li><b>대조를 수행하지 못했으면 통과가 아니다</b>({@code RATE_LIMITED}) — 캡을 태우는 것만으로
 *       게이트가 꺼지면 안 된다.</li>
 *   <li><b>차단당한 시도가 전역 캡을 태우지 못한다</b> — 사업자번호 축이 전역 축보다 먼저 걸린다.</li>
 *   <li>그럼에도 <b>예외는 절대 위로 던지지 않는다</b>(판정은 반환값으로만).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
                aiProxyService, rateLimiter, authProperties, fileStorageProperties, new PdfFirstPageRasterizer());
    }

    private static MultipartFile pngFile() {
        return new MockMultipartFile(
                "businessRegistrationFile", "license.png", "image/png", PngTestFixtures.realPng());
    }

    private void stubOcrBrn(String recognizedBrn) {
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(any())).thenReturn(ApiResponse.ok(
                new BusinessLicenseOcrResponse(recognizedBrn, "(주)하자체크", "김민수", "2020-01-01")));
    }

    private AuthProperties.BusinessLicenseCrossCheckRateLimit limit() {
        return authProperties.getBusinessLicenseCrossCheckRateLimit();
    }

    // ---------- MATCH / MISMATCH ----------

    @Test
    void 대조_같은사업자번호_MATCH() {
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_OCR값에_하이픈이_섞여도_MATCH() {
        // 등록증 서식 그대로 읽히는 게 정상 — 하이픈을 지우지 않으면 정상 사용자가 전부 차단된다.
        stubOcrBrn("123-45-67890");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_OCR값에_공백_마침표가_섞여도_MATCH() {
        // 하이픈만 제거하는 normalizeBrn 을 그대로 재사용하면 여기서 오탐이 난다(하이픈 외 구분자 인식).
        stubOcrBrn(" 123 45.67890 ");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    @Test
    void 대조_다른사업자번호_MISMATCH() {
        // #1469 의 본체 — 남의 등록증 업로드 + 실재하는 제3자 정보 수기 입력.
        stubOcrBrn("999-88-77665");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    @Test
    void 대조_JPEG도_대조된다() {
        MultipartFile jpeg = new MockMultipartFile(
                "businessRegistrationFile", "license.jpg", "image/jpeg", PngTestFixtures.realJpeg());
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(jpeg, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    // ---------- 실제 타입 판별(선언 Content-Type 불신) — 2026-08-04 리뷰 P1 ----------

    @Test
    void 대조_ContentType을_pdf로_위조해도_실제JPEG면_대조된다() {
        // 리뷰 P1의 100% 우회 경로: 훔친 등록증 JPEG 를 그대로 두고 파트 헤더만 application/pdf 로
        // 바꾸면 이미지 전용 검사를 건너뛰어 대조가 통째로 생략됐다. 이제 분기는 매직바이트로만 한다.
        MultipartFile disguised = new MockMultipartFile(
                "businessRegistrationFile", "license.pdf", "application/pdf", PngTestFixtures.realJpeg());
        stubOcrBrn("999-88-77665");

        assertThat(service.crossCheck(disguised, INPUT_BRN))
                .as("선언 타입을 바꿔도 실제 바이트가 이미지면 대조를 수행해야 한다")
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    @Test
    void 대조_ContentType을_png로_위조해도_실제PDF면_래스터화해_대조한다() {
        MultipartFile disguised = new MockMultipartFile(
                "businessRegistrationFile", "license.png", "image/png", PngTestFixtures.realPdf());
        stubOcrBrn("999-88-77665");

        assertThat(service.crossCheck(disguised, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    // ---------- PDF 래스터화 ----------

    @Test
    void 대조_PDF업로드는_첫페이지를_래스터화해_대조한다() {
        MultipartFile pdf = new MockMultipartFile(
                "businessRegistrationFile", "license.pdf", "application/pdf", PngTestFixtures.realPdf());
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(pdf, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
        // AI 서버로 넘어간 것은 원본 PDF 가 아니라 렌더된 PNG 여야 한다.
        verify(aiProxyService).ocrBusinessLicenseForCrossCheck(argThat(base64 -> {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return decoded.length > 8 && decoded[0] == (byte) 0x89
                    && decoded[1] == 'P' && decoded[2] == 'N' && decoded[3] == 'G';
        }));
    }

    @Test
    void 대조_여러페이지_PDF도_첫페이지만_렌더해_대조한다() {
        MultipartFile pdf = new MockMultipartFile("businessRegistrationFile", "license.pdf",
                "application/pdf", PngTestFixtures.realPdf(5, PDRectangle.A4));
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(pdf, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
        verify(aiProxyService, times(1)).ocrBusinessLicenseForCrossCheck(any());
    }

    @Test
    void 대조_손상된PDF는_AI호출없이_SKIPPED() {
        // %PDF- 헤더만 있고 본문이 깨진 파일 — 래스터화 불가는 fail-open(가입은 계속).
        byte[] broken = "%PDF-1.4\nnot really a pdf".getBytes();
        MultipartFile pdf = new MockMultipartFile(
                "businessRegistrationFile", "broken.pdf", "application/pdf", broken);

        assertThat(service.crossCheck(pdf, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void 대조_렌더픽셀_상한초과_PDF는_AI호출없이_SKIPPED_다만_캡은_소모한다() {
        // 페이지 상자가 비정상적으로 큰 PDF(14400pt 는 PDF 사양상 합법) — 200DPI 로 렌더하면 렌더 픽셀
        // 상한(12MP)을 넘어 거부된다.
        // ⚠️ 이미지와 달리 PDF 는 캡을 <b>먼저</b> 획득한 뒤 파싱한다 — "페이지 수·상자 크기 확인"이
        // 이미 전체 객체그래프 파싱이라 싼 사전 검사가 아니기 때문이다(2차 리뷰 P1-3 ⑤). 그 대가로
        // 손상 PDF 도 시도 1회를 소모하는데, 이를 reset 으로 보상하지는 않는다(전역 축 자기소진 부활).
        MultipartFile huge = new MockMultipartFile("businessRegistrationFile", "huge.pdf",
                "application/pdf", PngTestFixtures.realPdf(1, new PDRectangle(14_400f, 14_400f)));

        assertThat(service.crossCheck(huge, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        verifyNoInteractions(aiProxyService);
        assertThat(remainingGlobal())
                .as("PDF 는 캡 획득 후 파싱하므로 1회 소모된다")
                .isEqualTo(limit().getGlobalLimit() - 1);
    }

    @Test
    void 대조_임베디드_이미지폭탄_PDF도_OOM없이_결론이_난다() {
        // 캔버스는 A4 로 작지만 그 안에 초대형 이미지를 넣은 PDF — 캔버스 픽셀 추정으로는 못 잡는 벡터라
        // 서브샘플링·시간/동시 상한이 없으면 요청 1건으로 힙이 날아간다(2차 리뷰 P1-3 ②③).
        MultipartFile bomb = new MockMultipartFile("businessRegistrationFile", "bomb.pdf",
                "application/pdf", PngTestFixtures.pdfWithEmbeddedImage(6_000, 6_000));
        stubOcrBrn("1234567890");

        assertThat(service.crossCheck(bomb, INPUT_BRN))
                .isIn(BusinessLicenseCrossCheckOutcome.MATCH,
                        BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
    }

    // ---------- OCR_NO_BRN (인식/기준값을 신뢰할 수 없음 → 통과) ----------

    @Test
    void 대조_OCR이_사업자번호를_주지않으면_OCR_NO_BRN() {
        stubOcrBrn(null);

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    @Test
    void 대조_OCR값이_공백문자열이면_OCR_NO_BRN() {
        stubOcrBrn("   ");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    @Test
    void 대조_OCR값_자릿수가_10이_아니면_OCR_NO_BRN() {
        // 부분 인식(9자리) — 실제로는 같은 회사일 수 있으므로 차단하지 않는다.
        stubOcrBrn("123456789");

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
    }

    @Test
    void 대조_입력값_자릿수가_10이_아니면_AI호출없이_OCR_NO_BRN() {
        // 리뷰 P3(입력 측 자릿수 비대칭) — 대조 기준값을 신뢰할 수 없으면 "다르다"고 단정할 근거도 없다.
        // 차단형 게이트가 DTO @Pattern 에 암묵 의존하지 않도록 자립적으로 막는다.
        assertThat(service.crossCheck(pngFile(), "12345"))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
        assertThat(service.crossCheck(pngFile(), null))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
        verifyNoInteractions(aiProxyService);
    }

    // ---------- OCR_FAILED (외부 장애 → 통과, 예외 미전파) ----------

    @Test
    void 대조_AI서버_예외는_삼키고_OCR_FAILED() {
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        // 프리필(BusinessLicenseOcrService)과 정반대 — 여기선 예외가 위로 새면 가입 자체가 막힌다.
        // read timeout 발화도 같은 경로(ResourceAccessException → BusinessException)로 흡수된다.
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    @Test
    void 대조_AI서버_실패ApiResponse도_OCR_FAILED() {
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(any()))
                .thenReturn(ApiResponse.fail("LLM_INVALID_OUTPUT", "사업자등록증 인식 중 오류가 발생했습니다"));

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    @Test
    void 대조_예상치못한_런타임예외도_삼키고_OCR_FAILED() {
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
    }

    // ---------- SKIPPED_UNSUPPORTED_TYPE (AI 호출 자체가 없음) ----------

    @Test
    void 대조_아는타입이_아니면_AI호출없이_SKIPPED() {
        // JPEG/PNG/PDF 어느 시그니처도 아닌 바이트 — 대조는 못 하지만 가입을 막지는 않는다
        // (파일 자체의 적부 판정은 뒤이어 LocalFileStorage 가 FILE_INVALID_TYPE 으로 내린다).
        MultipartFile fake = new MockMultipartFile(
                "businessRegistrationFile", "fake.png", "image/png", "NOT-A-REAL-IMAGE".getBytes());

        assertThat(service.crossCheck(fake, INPUT_BRN))
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
    void 대조_용량초과는_AI호출없이_SKIPPED() {
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

        assertThat(service.crossCheck(bomb, INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);

        verifyNoInteractions(aiProxyService);
        assertThat(remainingGlobal()).isEqualTo(limit().getGlobalLimit());
    }

    // ---------- 사업자번호 축(좁은 축) — 자기소진 봉쇄, 2026-08-04 리뷰 P1 ----------

    @Test
    void 대조_같은번호로_반복실패하면_시도한도초과로_AI호출없이_차단된다() {
        stubOcrBrn("999-88-77665"); // 계속 MISMATCH
        int brnLimit = limit().getBrnLimit();
        for (int i = 0; i < brnLimit; i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED);
        // 한도 초과분은 AI 를 호출하지 않는다(과금·CPU 방어).
        verify(aiProxyService, times(brnLimit)).ocrBusinessLicenseForCrossCheck(any());
    }

    @Test
    void 대조_차단당한_시도는_전역캡을_거의_태우지_못한다() {
        // 리뷰 P1의 핵심: 예전엔 차단당한 시도가 전역 캡을 그대로 태워 31번째에 게이트가 꺼졌다.
        // 이제 한 사업자번호는 brnLimit 회까지만 전역 캡을 소모할 수 있다.
        stubOcrBrn("999-88-77665");
        for (int i = 0; i < 20; i++) {
            service.crossCheck(pngFile(), INPUT_BRN);
        }

        assertThat(remainingGlobal())
                .as("한 사업자번호가 태울 수 있는 전역 예산은 brnLimit 회로 묶여야 한다")
                .isEqualTo(limit().getGlobalLimit() - limit().getBrnLimit());
    }

    @Test
    void 대조_다른_사업자번호는_서로의_시도한도에_영향을_주지_않는다() {
        stubOcrBrn("999-88-77665");
        for (int i = 0; i < limit().getBrnLimit(); i++) {
            service.crossCheck(pngFile(), INPUT_BRN);
        }
        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED);

        // 다른 번호는 자기 축을 그대로 갖는다(전역 축이 아니라 번호별 축임을 고정).
        assertThat(service.crossCheck(pngFile(), "2223334444"))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    @Test
    void 대조_MATCH는_카운터를_되돌리지_않는다() {
        // ⚠️ 계약 변경(2026-08-04 2차 리뷰): 예전엔 MATCH 시 reset 했으나 제거했다. 무인증 경로에서
        // 카운터를 지우면(RateLimiter#reset javadoc 경고) 공격자가 MATCH 를 내는 요청으로 좁은 축을
        // 계속 세탁하며 전역 캡만 태우는 루프가 성립한다. 가입에 성공하면 그 번호는 중복검사로 잠기므로
        // reset 자체가 불필요하다.
        stubOcrBrn("1234567890");
        for (int i = 0; i < limit().getBrnLimit(); i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN)).isEqualTo(BusinessLicenseCrossCheckOutcome.MATCH);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED);
    }

    @Test
    void 대조_우리쪽_장애_OCR_FAILED는_시도카운터를_되돌린다() {
        // AI 서버가 죽어 있는 동안 몇 번 시도했다는 이유로 그 회사의 가입이 막히면 안 된다(2차 리뷰 P2).
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        for (int i = 0; i < limit().getBrnLimit() + 3; i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                    .as("우리 쪽 장애가 사용자 시도 예산을 태우면 안 된다")
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_FAILED);
        }
    }

    @Test
    void 대조_OCR_NO_BRN은_되돌리지_않는다() {
        // 여기까지 보상하면 "인식 안 되는 이미지로 무한 시도"라는 세탁 경로가 열린다.
        stubOcrBrn(null);
        for (int i = 0; i < limit().getBrnLimit(); i++) {
            assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED);
    }

    // ---------- 전역 캡 — 이제 통과가 아니라 차단 ----------

    @Test
    void 대조_분당캡_소진시_AI호출없이_RATE_LIMITED() {
        // RATE_LIMITED 는 fail-open(가입 통과)이다 — 전역 축을 막으면 공격자가 캡을 태워 기업 가입을
        // 전면 정지시킬 수 있기 때문(2026-08-04 2차 리뷰에서 429 차단안을 되돌렸다).
        stubOcrBrn("1234567890");
        int globalLimit = limit().getGlobalLimit();
        // 사업자번호 축에 걸리지 않게 매번 다른 번호로 전역 캡만 소진시킨다.
        for (int i = 0; i < globalLimit; i++) {
            assertThat(service.crossCheck(pngFile(), distinctBrn(i)))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
        verify(aiProxyService, times(globalLimit)).ocrBusinessLicenseForCrossCheck(any());
    }

    @Test
    void 대조_일일캡_소진시_RATE_LIMITED() {
        // 분당 캡에 걸리지 않도록 분당 축을 크게 열고 일일 축만 소진시킨다.
        limit().setGlobalLimit(10_000);
        limit().setDailyLimit(3);
        stubOcrBrn("1234567890");
        for (int i = 0; i < 3; i++) {
            assertThat(service.crossCheck(pngFile(), distinctBrn(i)))
                    .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
        }

        assertThat(service.crossCheck(pngFile(), INPUT_BRN))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
    }

    @Test
    void 대조_분당캡에서_막히면_일일캡_예산을_소모하지_않는다() {
        limit().setGlobalLimit(1);
        stubOcrBrn("1234567890");

        service.crossCheck(pngFile(), distinctBrn(0));                  // 분당 1회 소진(일일도 1회 소진)
        assertThat(service.crossCheck(pngFile(), distinctBrn(1)))       // 분당에서 차단
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);
        assertThat(service.crossCheck(pngFile(), distinctBrn(2)))
                .isEqualTo(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);

        // 분당 축에서 막힌 2회가 일일 축 카운터를 건드렸다면 남은 예산이 그만큼 줄어든다.
        assertThat(remainingDaily())
                .as("분당 축에서 막힌 요청은 일일 축 예산을 소모하지 않아야 한다")
                .isEqualTo(limit().getDailyLimit() - 1);
    }

    // ---------- 공개 프리필 캡과의 분리(핵심 보안 요구사항) ----------

    @Test
    void 대조_공개프리필_캡이_소진돼도_대조는_영향받지_않는다() {
        // 공유 키였다면 비로그인 프리필 API 를 반복 호출하는 것만으로 이 게이트를 조작할 수 있다.
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
                .as("프리필 캡 소진이 대조 게이트를 조작하면 안 된다")
                .isEqualTo(BusinessLicenseCrossCheckOutcome.MISMATCH);
    }

    @Test
    void 대조_전용_rate_limit_키를_사용한다() {
        stubOcrBrn("1234567890");

        service.crossCheck(pngFile(), INPUT_BRN);

        // 대조 전용 키만 1회 소모되고 프리필 키는 그대로여야 한다.
        assertThat(remainingGlobal()).isEqualTo(limit().getGlobalLimit() - 1);
        AuthProperties.BusinessLicenseOcrRateLimit prefill = authProperties.getBusinessLicenseOcrRateLimit();
        assertThat(rateLimiter.tryAcquire("rate:business-license-ocr:global",
                prefill.getGlobalLimit(), prefill.getGlobalWindow()))
                .as("대조가 프리필 키를 소모하면 안 된다")
                .isTrue();
    }

    @Test
    void 대조_사업자번호_축_키에는_평문_사업자번호가_들어가지_않는다() {
        stubOcrBrn("1234567890");

        service.crossCheck(pngFile(), INPUT_BRN);

        // 평문 키였다면 아래 키가 이미 1회 소모돼 있었을 것이다.
        assertThat(rateLimiter.tryAcquire("rate:business-license-crosscheck:brn:" + INPUT_BRN, 1, Duration.ofHours(1)))
                .as("사업자번호 평문을 키에 쓰면 안 된다(해시 접두만 사용)")
                .isTrue();
    }

    // ---------- AI 위임 형태 ----------

    @Test
    void 대조_파일바이트를_base64로_인코딩해_대조전용_진입점에_위임한다() {
        byte[] bytes = PngTestFixtures.realPng();
        MultipartFile file = new MockMultipartFile("businessRegistrationFile", "license.png", "image/png", bytes);
        stubOcrBrn("1234567890");

        service.crossCheck(file, INPUT_BRN);

        // 공개 프리필용 진입점(150s RestClient)이 아니라 대조 전용(짧은 타임아웃) 진입점을 써야 한다 —
        // 무인증 가입 제출 경로의 워커 스레드 점유를 묶는 #889 P1 결정을 되돌리지 않기 위함.
        verify(aiProxyService).ocrBusinessLicenseForCrossCheck(Base64.getEncoder().encodeToString(bytes));
        verify(aiProxyService, never()).ocrBusinessLicense(any());
    }

    @Test
    void 대조_OCR이_읽은_뒤에도_같은_파일을_다시_읽을_수_있다() throws IOException {
        // 대조는 파일 저장(fileStorage.store)보다 먼저 실행된다 — 여기서 스트림을 소진해버리면
        // 이후 저장이 빈 파일을 쓰게 된다(무증상 회귀). 재읽기 가능성을 계약으로 고정한다.
        byte[] bytes = PngTestFixtures.realPng();
        MultipartFile file = new MockMultipartFile("businessRegistrationFile", "license.png", "image/png", bytes);
        stubOcrBrn("1234567890");

        service.crossCheck(file, INPUT_BRN);

        assertThat(file.getBytes()).isEqualTo(bytes);
        assertThat(file.getInputStream().readAllBytes()).isEqualTo(bytes);
    }

    /** 사업자번호 축에 걸리지 않게 매 호출 다른 10자리 번호를 만든다(전역 축만 검증하려는 테스트용). */
    private static String distinctBrn(int index) {
        return String.format("%010d", 1_000_000_000L + index);
    }

    /**
     * 대조 전용 카운터의 잔여 예산 — {@code tryAcquire} 는 호출 자체가 카운터를 늘리므로,
     * "몇 번 더 통과하는가"를 세는 방식으로 소모량을 역산한다(창은 고정 시계라 만료되지 않는다).
     */
    private int remainingGlobal() {
        return countRemaining("rate:business-license-crosscheck:global",
                limit().getGlobalLimit(), limit().getGlobalWindow());
    }

    private int remainingDaily() {
        return countRemaining("rate:business-license-crosscheck:daily",
                limit().getDailyLimit(), limit().getDailyWindow());
    }

    private int countRemaining(String key, int limit, Duration window) {
        int remaining = 0;
        while (rateLimiter.tryAcquire(key, limit, window)) {
            remaining++;
        }
        return remaining;
    }
}
