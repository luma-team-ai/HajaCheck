package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.media.support.DetectedFileType;
import com.hajacheck.core.media.support.ImageDimensionValidator;
import com.hajacheck.core.media.support.PdfFirstPageRasterizer;
import com.hajacheck.global.common.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 기업 가입 심사 게이트 — 업로드된 사업자등록증을 OCR로 읽어 <b>입력된 사업자등록번호와 대조</b>한다
 * (#1469 / HAJA-650).
 *
 * <p><b>왜 필요한가</b>: #1324로 기업 가입이 전면 자동승인이 되면서 사람 심사가 사라졌고, 남은 검증은
 * 국세청 진위확인(#596) 하나뿐이었다. 그런데 진위확인은 "입력한 3필드(사업자번호·대표자명·개업일자)가
 * 실재하고 서로 일치하는가"만 본다 — 즉 <b>남의 사업자등록증 이미지를 올리고 실재하는 제3의 회사 정보를
 * 수기로 입력하면 그대로 통과</b>했다. 업로드 파일은 저장만 되고 아무도 보지 않았기 때문이다.
 * 이 서비스가 그 "업로드 파일 ↔ 입력값" 연결고리를 검사한다.
 *
 * <p><b>왜 {@link BusinessLicenseOcrService}(공개 프리필)에 얹지 않았는가</b>: 두 소비처는 요구사항이
 * 정반대다. 프리필은 비로그인 보조기능이라 실패를 <b>그대로 표면화</b>(프론트가 수동입력 폴백)하고,
 * 여기는 심사 게이트라 판정 불가를 <b>삼키고 통과</b>(fail-open)시켜야 한다. 무엇보다 rate-limit 키를
 * 공유하면 <b>공격자가 비로그인 프리필 API를 반복 호출해 캡을 소진시키는 것만으로 이 게이트를 조작</b>할
 * 수 있다. 그래서 대조 전용 키·전용 캡·전용 RestClient(짧은 타임아웃)를 둔다.
 *
 * <p><b>위협 모델은 "의도적 위조자"다.</b> 그래서 아래 세 가지가 설계의 핵심이다(2026-08-04 리뷰 P1 반영):
 * <ol>
 *   <li><b>분기 근거는 선언 Content-Type 이 아니라 매직바이트</b> — 파트 헤더를 {@code application/pdf}
 *       로 바꾸는 것만으로 게이트를 건너뛸 수 있었다. PDF 도 첫 페이지를 래스터화해 같은 OCR 경로에 태워
 *       "형식으로 빠져나가는 구멍" 자체를 없앤다.</li>
 *   <li><b>대조를 수행하지 못했으면 통과시키지 않는다</b>({@code RATE_LIMITED} → 429) — 캡을 태우는 것만
 *       으로 게이트가 꺼지면 안 된다.</li>
 *   <li><b>차단당한 시도가 캡을 태우지 못하게</b> 사업자번호 축(좁은 축)을 전역 축보다 먼저 건다 —
 *       MISMATCH 차단은 행을 남기지 않아 같은 요청을 무한 반복할 수 있기 때문이다.</li>
 * </ol>
 *
 * <p><b>계약</b>: {@link #crossCheck}는 <b>어떤 경우에도 예외를 던지지 않는다</b>. 판정은 반환값
 * ({@link BusinessLicenseCrossCheckOutcome})으로만 전달하고, 차단 결정(예외 변환)은 호출부인
 * {@code CompanySignupService}가 한다. 삼킨 예외는 {@code log.warn}으로 남기되 개인정보(이메일·
 * 사업자등록번호·대표자명)는 <b>평문으로</b> 로그에 쓰지 않는다(상관 추적용 해시 접두만 남긴다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessLicenseCrossCheckService {

    /** 디컴프레션(픽셀) 폭탄 방어 상한 — 근거는 {@link BusinessLicenseOcrService} 의 동일 상수 참조. */
    private static final long OCR_MAX_PIXELS = 50_000_000L; // 50MP

    /**
     * ⚠️ <b>공개 프리필용 {@code rate:business-license-ocr:*} 과 반드시 다른 키다.</b> 공유하면 위
     * 클래스 주석의 "프리필 반복 호출로 게이트 조작" 공격이 성립한다 — 키 분리가 이 방어의 전부이므로
     * 리팩터링으로 상수를 합치지 말 것.
     */
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:business-license-crosscheck:global";
    private static final String RATE_LIMIT_DAILY_KEY = "rate:business-license-crosscheck:daily";
    /** 사업자번호 축(좁은 축) 키 접두 — 뒤에 사업자번호의 <b>해시</b> 접두가 붙는다(평문 금지). */
    private static final String RATE_LIMIT_BRN_KEY_PREFIX = "rate:business-license-crosscheck:brn:";

    /**
     * 키·로그에 쓰는 해시 접두 길이(hex 문자 수). 키는 16자(64bit)라 충돌이 사실상 없고, 로그는 8자로
     * 짧게 남겨 사람이 눈으로 상관 추적하기 좋게 한다.
     *
     * <p>⚠️ <b>정직한 한계</b>: 사업자등록번호의 값 공간은 10자리(10^10)에 불과해 이 해시는 역산이
     * 가능하다 — 익명화가 아니라 <b>"평문을 로그·Redis 키에 남기지 않기" 위한 조치</b>다. 그럼에도
     * 유용한 이유는 로그가 유출돼도 즉시 읽히는 식별자가 없고, 사후 조사에서는 조사자가 아는 번호를
     * 해싱해 대조하면 되기 때문이다.
     */
    private static final int BRN_KEY_HASH_LENGTH = 16;
    private static final int BRN_LOG_HASH_LENGTH = 8;

    /** 사업자등록번호 정규형 자릿수 — 숫자만 남겼을 때 이 길이가 아니면 대조 기준으로 신뢰하지 않는다. */
    private static final int BRN_DIGITS = 10;

    private final AiProxyService aiProxyService;
    private final RateLimiter rateLimiter;
    private final AuthProperties authProperties;
    private final FileStorageProperties fileStorageProperties;

    /**
     * 업로드 파일의 OCR 결과와 입력 사업자등록번호를 대조한다.
     *
     * @param file          가입 요청의 사업자등록증 파일(아직 저장 전 — 차단 시 고아 파일이 남지 않게 하려고
     *                      {@code fileStorage.store} 보다 먼저 호출된다)
     * @param normalizedBrn 입력값(하이픈 제거 정규형)
     * @return 판정. {@code MISMATCH}·{@code BRN_ATTEMPT_EXCEEDED}·{@code RATE_LIMITED} 가 차단이고
     *         나머지는 통과다.
     */
    public BusinessLicenseCrossCheckOutcome crossCheck(MultipartFile file, String normalizedBrn) {
        try {
            return doCrossCheck(file, normalizedBrn);
        } catch (RuntimeException e) {
            // 최후 안전망 — 예상 못 한 런타임 예외로 회원가입 전체가 500 이 되면 안 된다.
            // (판정은 반환값이라 이 catch 가 차단 판정을 삼킬 일은 없다.)
            log.warn("사업자등록증 대조 중 예상치 못한 실패 — fail-open 통과. exception={}",
                    e.getClass().getSimpleName());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }
    }

    private BusinessLicenseCrossCheckOutcome doCrossCheck(MultipartFile file, String normalizedBrn) {
        // ① 대조 기준값 검사 — 입력이 숫자 10자리가 아니면 "다르다"고 단정할 근거가 없다(OCR 측 자릿수
        //    검사와 대칭). 차단형 게이트가 DTO @Pattern 에 암묵 의존하지 않게 여기서 자립적으로 막는다.
        String inputDigits = digitsOnly(normalizedBrn);
        if (inputDigits.length() != BRN_DIGITS) {
            return BusinessLicenseCrossCheckOutcome.OCR_NO_BRN;
        }

        // ② OCR 적합성 사전 검사(싼 검사) — 부적합하면 AI 호출도, rate-limit 카운터 소모도 하지 않는다.
        //    PDF 는 여기서 페이지 수·예상 픽셀만 확인하고 실제 래스터화(비싼 작업)는 캡 획득 뒤로 미룬다.
        Optional<OcrCandidate> candidate = inspect(file);
        if (candidate.isEmpty()) {
            return BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE;
        }

        // ③ 좁은 축(사업자번호) → 넓은 축(전역) 순서로 캡을 건다.
        //    좁은 축이 먼저여야 "한 사업자번호의 반복 차단 시도"가 전역 예산을 갉아먹지 못한다.
        String brnHash = sha256Hex(inputDigits);
        String brnKey = RATE_LIMIT_BRN_KEY_PREFIX + brnHash.substring(0, BRN_KEY_HASH_LENGTH);
        if (!acquireBrnAttempt(brnKey, brnHash)) {
            return BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED;
        }
        if (!acquireGlobalRateLimit()) {
            return BusinessLicenseCrossCheckOutcome.RATE_LIMITED;
        }

        // ④ OCR 입력 이미지 확보(비싼 작업 — PDF 래스터화가 여기서 일어난다) → base64.
        byte[] imageBytes;
        try {
            imageBytes = candidate.get().toOcrImageBytes(file);
        } catch (IOException | RuntimeException e) {
            log.warn("사업자등록증 대조용 이미지 준비 실패 — fail-open 통과. type={}, exception={}",
                    candidate.get().type(), e.getClass().getSimpleName());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }

        // ⑤ AI 서버 호출(내부키 강제 경유 원칙 유지 — AiProxyService 위임, #228).
        //    ⚠️ 대조 전용 진입점을 쓴다 — LLM용 150s RestClient 를 타면 무인증 가입 제출 경로의 요청당
        //    대기가 약 161s 로 늘어 Tomcat 워커가 고갈된다(#889 P1 결정과 충돌).
        ApiResponse<BusinessLicenseOcrResponse> response;
        try {
            response = aiProxyService.ocrBusinessLicenseForCrossCheck(
                    Base64.getEncoder().encodeToString(imageBytes));
        } catch (RuntimeException e) {
            // AI 서버 장애·타임아웃·응답 형식 불량(BusinessException 등). 프리필과 달리 여기선 표면화하지
            // 않는다 — 외부 장애가 회원가입 전체를 막으면 안 된다(국세청 진위확인 fail-open 과 같은 기조).
            log.warn("사업자등록증 대조 OCR 호출 실패 — fail-open 통과. exception={}",
                    e.getClass().getSimpleName());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }
        if (response == null || !response.success() || response.data() == null) {
            // OCR/LLM 이 인식을 거부한 경우(ApiResponse.fail) — 에러 코드만 남긴다(원문·개인정보 금지).
            log.warn("사업자등록증 대조 OCR 인식 실패 — fail-open 통과. code={}",
                    response == null || response.error() == null ? "UNKNOWN" : response.error().code());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }

        // ⑥ 판정 — 사업자등록번호만 대조한다.
        return compare(response.data().businessRegistrationNumber(), inputDigits, brnKey, brnHash);
    }

    /**
     * OCR BRN ↔ 입력 BRN 대조.
     *
     * <p><b>상호명·대표자명은 대조하지 않는다</b>(사용자 결정) — 공백·법인격 표기({@code (주)}/{@code 주식회사})·
     * 서체 차이로 오탐이 너무 쉬워, 차단형 게이트의 근거로 쓰기엔 신뢰도가 부족하다.
     *
     * <p>양쪽 모두 <b>비숫자를 전부 제거</b>한 뒤 비교한다. 입력값은 이미 하이픈이 제거된 정규형이지만
     * OCR 값은 서식을 예측할 수 없어({@code 123-45-67890}, {@code 123 45 67890}, {@code 123.45.67890} 등)
     * 하이픈만 지우면 정상 등록증에서도 불일치가 나 정상 사용자를 차단하게 된다.
     */
    private BusinessLicenseCrossCheckOutcome compare(String ocrBrn, String inputDigits,
                                                     String brnKey, String brnHash) {
        String ocrDigits = digitsOnly(ocrBrn);
        if (ocrDigits.length() != BRN_DIGITS) {
            // null/공백뿐 아니라 "자릿수가 맞지 않는 인식 결과"도 신뢰하지 않는다 — 부분 인식(9자리)이나
            // 다른 숫자 블록(전화번호 등)을 사업자번호로 오인한 값으로 정상 가입을 막으면 안 된다.
            return BusinessLicenseCrossCheckOutcome.OCR_NO_BRN;
        }
        if (ocrDigits.equals(inputDigits)) {
            // 시도 한도 해제 — 업로드한 등록증이 실제로 그 번호의 것임이 증명된 순간이라, 실패 시도를
            // 세던 카운터를 계속 들고 있을 이유가 없다({@link RateLimiter#reset} javadoc 의 "자격증명
            // 확인 성공" 요건 충족, #1315 비밀번호 변경 성공 시 해제와 같은 패턴). 이 해제 덕분에 정상
            // 사용자가 잘못된 사진으로 몇 번 튕긴 뒤 성공해도 다음 시도가 막히지 않는다.
            rateLimiter.reset(brnKey);
            return BusinessLicenseCrossCheckOutcome.MATCH;
        }
        // ⚠️ 개인정보 금지 — 어느 쪽 번호도 평문으로 남기지 않는다. 다만 차단은 회사 행 자체가 생기지
        //    않아 DB에 흔적이 없으므로(사후 재구성 불가), 상관 추적용으로 입력 번호의 해시 접두만 남긴다.
        log.warn("사업자등록증 대조 불일치 — 가입 차단(#1469). brnHash={}", logHash(brnHash));
        return BusinessLicenseCrossCheckOutcome.MISMATCH;
    }

    private static String digitsOnly(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /**
     * OCR 을 시도할 가치가 있는 업로드인지 <b>실제 바이트로</b> 판정하고, 타입별 처리 방법을 돌려준다.
     *
     * <p>⚠️ <b>선언된 {@code Content-Type} 은 보지 않는다.</b> 멀티파트 파트 헤더는 클라이언트가 한 줄로
     * 바꿀 수 있어, 선언 타입으로 분기하면 훔친 등록증 JPEG 를 {@code application/pdf} 로 선언하는 것만
     * 으로 이 게이트를 100% 우회할 수 있었다(2026-08-04 리뷰 P1).
     *
     * <p>여기서 걸린다고 가입을 막지는 않는다({@code SKIPPED_UNSUPPORTED_TYPE} 통과). 파일 자체의 적부
     * 판정은 뒤이어 실행되는 {@code FileStorageService.store} 가 {@code FILE_*} 로 내리는 것이 기존
     * 계약이고, 그쪽도 이번 변경으로 매직바이트를 검증한다(선언만 믿고 저장하지 않는다).
     *
     * <p>검사 순서는 저비용 → 고비용이며 <b>rate-limit 획득보다 먼저</b> 수행된다 — 그래야 픽셀폭탄·
     * 위조 파일이 정상 가입자의 대조 예산을 갉아먹지 못한다. PDF 의 실제 래스터화(CPU)는 캡을 획득한
     * 뒤로 미루고, 여기서는 페이지 수와 렌더 예상 픽셀만 확인한다.
     */
    private Optional<OcrCandidate> inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Optional.empty();
        }
        if (file.getSize() > fileStorageProperties.getMaxSizeBytes()) {
            return Optional.empty();
        }
        Optional<DetectedFileType> detected = DetectedFileType.detect(file);
        if (detected.isEmpty()) {
            return Optional.empty();
        }
        try {
            return switch (detected.get()) {
                case JPEG, PNG -> {
                    // 헤더에 거대한 가로×세로를 선언한 픽셀폭탄은 디코딩 전에 거른다(AI 서버 보호).
                    ImageDimensionValidator.ensureWithinPixelLimit(file, OCR_MAX_PIXELS);
                    yield Optional.of(new OcrCandidate(detected.get(), null));
                }
                case PDF -> inspectPdf(file);
            };
        } catch (RuntimeException e) {
            // ImageDimensionValidator 는 FILE_* BusinessException 을 던진다 — 여기선 "대조 불가"로만 해석.
            log.warn("사업자등록증 대조 사전 검사 탈락 — OCR 생략(가입은 계속). exception={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * PDF 사전 검사 — 바이트를 읽어 페이지 수(≥1)와 첫 페이지 렌더 예상 픽셀 수만 확인한다.
     * 암호화·손상 PDF 는 {@link IOException} 으로 걸러져 fail-open(대조 생략)이 된다.
     */
    private Optional<OcrCandidate> inspectPdf(MultipartFile file) {
        try {
            byte[] pdfBytes = file.getBytes();
            PdfFirstPageRasterizer.Inspection inspection = PdfFirstPageRasterizer.inspect(pdfBytes);
            if (!inspection.hasFirstPage() || inspection.firstPageEstimatedPixels() > OCR_MAX_PIXELS) {
                log.warn("사업자등록증 PDF 대조 사전 검사 탈락 — OCR 생략(가입은 계속). pages={}",
                        inspection.pageCount());
                return Optional.empty();
            }
            return Optional.of(new OcrCandidate(DetectedFileType.PDF, pdfBytes));
        } catch (IOException e) {
            // 암호화(InvalidPasswordException)·손상 PDF 등 — 대조는 못 하지만 가입은 막지 않는다.
            log.warn("사업자등록증 PDF 열기 실패 — OCR 생략(가입은 계속). exception={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 사전 검사를 통과한 업로드 — 실제 타입과(PDF 인 경우) 이미 읽어둔 원본 바이트.
     * 실제 OCR 입력 이미지 생성은 rate-limit 획득 뒤에 {@link #toOcrImageBytes}로 미룬다.
     */
    private record OcrCandidate(DetectedFileType type, byte[] pdfBytes) {

        byte[] toOcrImageBytes(MultipartFile file) throws IOException {
            // PDF 는 첫 페이지만 PNG 로 래스터화해 이미지와 동일한 OCR 경로에 태운다.
            return type == DetectedFileType.PDF
                    ? PdfFirstPageRasterizer.renderFirstPageAsPng(pdfBytes)
                    : file.getBytes();
        }
    }

    /**
     * 사업자번호 축(좁은 축) 시도 한도 — 같은 번호로 대조를 반복 실패하는 흐름을 끊는다.
     *
     * <p>차단({@code MISMATCH})은 회사·유저 행을 만들지 않으므로 중복검사가 영원히 통과한다 = 같은
     * 요청을 무한 반복할 수 있다. 이 축이 없으면 그 반복이 전역 캡을 태워 가입 전체를 429 로 세운다
     * (2026-08-04 리뷰 P1). {@link #compare} 가 {@code MATCH} 시 카운터를 해제하므로, 실질적으로는
     * <b>연속 실패 횟수</b>를 센다.
     */
    private boolean acquireBrnAttempt(String brnKey, String brnHash) {
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        if (rateLimiter.tryAcquire(brnKey, limit.getBrnLimit(), limit.getBrnWindow())) {
            return true;
        }
        log.warn("사업자등록증 대조 시도 한도 초과(사업자번호 축) — AI 호출 없이 차단. brnHash={}, limit={}/{}",
                logHash(brnHash), limit.getBrnLimit(), limit.getBrnWindow());
        return false;
    }

    /**
     * 대조 전용 전역 rate-limit(분당 → 일일). 순서가 중요하다 — {@link RateLimiter#tryAcquire} 는
     * 허용 여부와 무관하게 카운터를 증가시키므로, 순서가 뒤집히면 분당 축에서 막힌 요청이 일일 축의
     * 예산까지 갉아먹는다({@code BusinessLicenseOcrService.enforceRateLimit()} 과 동일 원칙).
     *
     * @return 두 축 모두 통과하면 true
     */
    private boolean acquireGlobalRateLimit() {
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limit.getGlobalLimit(), limit.getGlobalWindow())) {
            log.warn("사업자등록증 대조 rate-limit 초과(분당 전역) — 가입 제출을 429로 반려. limit={}/{}",
                    limit.getGlobalLimit(), limit.getGlobalWindow());
            return false;
        }
        if (!rateLimiter.tryAcquire(RATE_LIMIT_DAILY_KEY, limit.getDailyLimit(), limit.getDailyWindow())) {
            // ⚠️ ERROR 로 승격한다 — 일일 캡 소진은 "그날 남은 시간 동안 기업 가입이 통째로 막힌다"는
            // 뜻이라 분당 캡의 순간적 스로틀과 심각도가 다르다. 이 로그가 유일한 경보 축이다.
            log.error("사업자등록증 대조 rate-limit 초과(일일 캡) — 기업 가입이 429로 막힌다. limit={}/{}",
                    limit.getDailyLimit(), limit.getDailyWindow());
            return false;
        }
        return true;
    }

    /** 사업자등록번호(숫자 10자리)의 SHA-256 hex — 평문을 키·로그에 남기지 않기 위한 단방향 변환. */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 필수 알고리즘이라 도달 불가.
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private static String logHash(String brnHash) {
        return brnHash.substring(0, BRN_LOG_HASH_LENGTH);
    }
}
