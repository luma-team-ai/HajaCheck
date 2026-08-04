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
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
 * 공유하면 <b>비로그인 프리필 API 반복 호출만으로 가입 경로의 대조 예산을 태울</b> 수 있다.
 * 그래서 대조 전용 키·전용 캡·전용 RestClient(짧은 타임아웃)를 둔다.
 *
 * <p><b>설계 원칙 4가지</b>(2026-08-04 1·2차 리뷰 반영):
 * <ol>
 *   <li><b>실제 바이트로만 분기한다</b> — 선언 {@code Content-Type} 은 파트 헤더 한 줄이라 조작 가능하다.
 *       PDF 도 첫 페이지를 래스터화해 같은 OCR 경로에 태워 "형식으로 빠져나가는 구멍"을 없앤다.
 *       (선언↔실제 <b>일치 검증</b>은 이 서비스가 아니라 호출부의 저장 전 검증이 담당한다 — 아래 참고.)</li>
 *   <li><b>fail-closed 는 좁은 축에만</b> — 전역 캡 소진은 fail-open 이다. 전역 축을 막으면 공격자가
 *       캡을 태우는 것만으로 기업 가입 전체를 정지시키는 DoS 킬스위치가 된다.</li>
 *   <li><b>차단당한 시도가 전역 예산을 태우지 못하게</b> 사업자번호 축을 전역 축보다 먼저 건다.</li>
 *   <li><b>무인증 경로이므로 카운터 되돌리기는 최소한만</b> — {@code RateLimiter#reset} javadoc 이
 *       경고하듯, 인증 이전 경로에서 카운터를 지우면 공격자가 무제한 시도할 수 있다. 되돌리기는
 *       <b>우리 쪽 장애({@code OCR_FAILED})</b>에만 적용한다.</li>
 * </ol>
 *
 * <p><b>계약</b>: {@link #crossCheck}는 <b>어떤 경우에도 예외를 던지지 않는다</b>. 판정은 반환값
 * ({@link BusinessLicenseCrossCheckOutcome})으로만 전달하고, 차단 결정(예외 변환)은 호출부인
 * {@code CompanySignupService}가 한다. 삼킨 예외는 {@code log.warn}으로 남기되 개인정보(이메일·
 * 사업자등록번호·대표자명)는 <b>평문으로</b> 로그에 쓰지 않는다(상관 추적용 해시 접두만 남긴다).
 */
@Slf4j
@Service
public class BusinessLicenseCrossCheckService {

    /** AI 서버로 보낼 이미지의 픽셀 상한 — 근거는 {@link BusinessLicenseOcrService} 의 동일 상수 참조. */
    private static final long OCR_MAX_PIXELS = 50_000_000L; // 50MP

    /**
     * ⚠️ <b>공개 프리필용 {@code rate:business-license-ocr:*} 과 반드시 다른 키다.</b> 공유하면 위
     * 클래스 주석의 "프리필 반복 호출로 대조 예산 소진" 공격이 성립한다 — 키 분리가 이 방어의 전부이므로
     * 리팩터링으로 상수를 합치지 말 것.
     */
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:business-license-crosscheck:global";
    private static final String RATE_LIMIT_DAILY_KEY = "rate:business-license-crosscheck:daily";
    /** 사업자번호 축(좁은 축) 키 접두 — 뒤에 사업자번호의 <b>해시</b> 접두가 붙는다(평문 금지). */
    private static final String RATE_LIMIT_BRN_KEY_PREFIX = "rate:business-license-crosscheck:brn:";
    /** 같은 번호가 반복적으로 시도 한도에 걸리는지(=타깃 봉쇄 시도) 감시하는 경보 키 접두. */
    private static final String BRN_ALERT_KEY_PREFIX = "rate:business-license-crosscheck:brn-alert:";

    /**
     * 키·로그에 쓰는 해시 접두 길이(hex 문자 수). 키는 16자(64bit)라 충돌이 사실상 없고, 로그는 8자로
     * 짧게 남겨 사람이 눈으로 상관 추적하기 좋게 한다.
     */
    private static final int BRN_KEY_HASH_LENGTH = 16;
    private static final int BRN_LOG_HASH_LENGTH = 8;

    /** 사업자등록번호 정규형 자릿수 — 숫자만 남겼을 때 이 길이가 아니면 대조 기준으로 신뢰하지 않는다. */
    private static final int BRN_DIGITS = 10;

    /**
     * PDF 전용 크기 상한(5MB). 일반 파일 상한(10MB)보다 <b>더 좁게</b> 잡는 이유는 PDF 만 파싱·래스터화라는
     * 비싼 처리를 요구하기 때문이다 — 사업자등록증 PDF 는 1페이지 문서라 5MB 면 충분히 넉넉하다.
     */
    private static final long PDF_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private final AiProxyService aiProxyService;
    private final RateLimiter rateLimiter;
    private final AuthProperties authProperties;
    private final FileStorageProperties fileStorageProperties;
    private final PdfFirstPageRasterizer pdfRasterizer;
    /**
     * 사업자번호 해시용 HMAC 키. 미설정이면 평문 SHA-256 으로 낙하한다 — 값이 없다고 가입을 막을 이유는
     * 없지만, 그 경우 해시는 <b>역산 가능</b>하다(사업자등록번호 값 공간이 10^10 이라 GPU 전수계산이
     * 분 단위). 운영에는 반드시 설정할 것.
     */
    private final byte[] brnHashKey;

    public BusinessLicenseCrossCheckService(AiProxyService aiProxyService,
                                            RateLimiter rateLimiter,
                                            AuthProperties authProperties,
                                            FileStorageProperties fileStorageProperties,
                                            PdfFirstPageRasterizer pdfRasterizer) {
        this.aiProxyService = aiProxyService;
        this.rateLimiter = rateLimiter;
        this.authProperties = authProperties;
        this.fileStorageProperties = fileStorageProperties;
        this.pdfRasterizer = pdfRasterizer;
        String secret = authProperties.getBusinessLicenseCrossCheckRateLimit().getBrnHashSecret();
        this.brnHashKey = StringUtils.hasText(secret) ? secret.getBytes(StandardCharsets.UTF_8) : null;
        if (this.brnHashKey == null) {
            log.warn("사업자등록번호 해시 비밀키 미설정 — 평문 SHA-256 으로 동작한다(역산 가능). "
                    + "운영에서는 app.auth.business-license-cross-check-rate-limit.brn-hash-secret 을 설정할 것.");
        }
    }

    /**
     * 업로드 파일의 OCR 결과와 입력 사업자등록번호를 대조한다.
     *
     * @param file          가입 요청의 사업자등록증 파일(아직 저장 전 — 차단 시 고아 파일이 남지 않게 하려고
     *                      {@code fileStorage.store} 보다 먼저 호출된다)
     * @param normalizedBrn 입력값(하이픈 제거 정규형)
     * @return 판정. {@code MISMATCH}·{@code BRN_ATTEMPT_EXCEEDED}·{@code RASTERIZE_BUSY} 가 차단이고
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

        // ② 실제 타입 판별(매직바이트) + 타입별 "캡 이전에 해도 되는" 검사만 수행.
        //    ⚠️ 이미지와 PDF 의 사전 검사 비용이 근본적으로 다르다:
        //      - 이미지: ImageReader 헤더만 읽음 = 진짜 싼 검사 → 캡 앞에 두어 폭탄이 예산을 못 갉게 한다.
        //      - PDF   : 페이지 수를 알려면 이미 전체 객체그래프를 파싱해야 한다 = 싼 검사가 아니다.
        //                그래서 PDF 는 크기 검사만 캡 앞에 두고, 파싱·렌더는 전부 캡 뒤로 미룬다.
        Optional<DetectedFileType> detected = DetectedFileType.detect(file);
        if (file == null || file.isEmpty() || detected.isEmpty()) {
            return BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE;
        }
        DetectedFileType type = detected.get();
        if (!isWithinSizeLimit(file, type)) {
            return BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE;
        }
        if (type != DetectedFileType.PDF && !isRenderableImage(file)) {
            return BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE;
        }

        // ③ 좁은 축(사업자번호) → 넓은 축(전역) 순서로 캡을 건다.
        //    좁은 축이 먼저여야 "한 사업자번호의 반복 차단 시도"가 전역 예산을 갉아먹지 못한다.
        String brnHash = brnHash(inputDigits);
        String brnKey = RATE_LIMIT_BRN_KEY_PREFIX + brnHash.substring(0, BRN_KEY_HASH_LENGTH);
        if (!acquireBrnAttempt(brnKey, brnHash)) {
            return BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED;
        }
        if (!acquireGlobalRateLimit()) {
            // ⚠️ fail-open — 전역 축을 막으면 공격자가 캡을 태워 기업 가입 전체를 세울 수 있다.
            return BusinessLicenseCrossCheckOutcome.RATE_LIMITED;
        }

        // ④ OCR 입력 이미지 확보(비싼 작업 — PDF 파싱·래스터화가 전부 여기서 일어난다).
        OcrImage ocrImage = toOcrImage(file, type);
        if (ocrImage.outcome() != null) {
            return ocrImage.outcome();
        }

        // ⑤ AI 서버 호출(내부키 강제 경유 원칙 유지 — AiProxyService 위임, #228).
        //    ⚠️ 대조 전용 진입점을 쓴다 — LLM용 150s RestClient 를 타면 무인증 가입 제출 경로의 요청당
        //    대기가 약 161s 로 늘어 Tomcat 워커가 고갈된다(#889 P1 결정과 충돌).
        ApiResponse<BusinessLicenseOcrResponse> response;
        try {
            response = aiProxyService.ocrBusinessLicenseForCrossCheck(
                    Base64.getEncoder().encodeToString(ocrImage.bytes()));
        } catch (RuntimeException e) {
            log.warn("사업자등록증 대조 OCR 호출 실패 — fail-open 통과. exception={}",
                    e.getClass().getSimpleName());
            return ocrFailed(brnKey);
        }
        if (response == null || !response.success() || response.data() == null) {
            log.warn("사업자등록증 대조 OCR 인식 실패 — fail-open 통과. code={}",
                    response == null || response.error() == null ? "UNKNOWN" : response.error().code());
            return ocrFailed(brnKey);
        }

        // ⑥ 판정 — 사업자등록번호만 대조한다.
        return compare(response.data().businessRegistrationNumber(), inputDigits, brnHash);
    }

    /**
     * <b>우리 쪽 장애</b>로 대조를 못 한 경우의 보상 — 사업자번호 축 카운터를 되돌린다.
     *
     * <p>AI 서버가 죽어 있는 동안 사용자가 몇 번 시도했다는 이유로 그 회사의 가입이 1시간 막히면 안 된다.
     * ⚠️ <b>여기까지만</b> 되돌린다 — {@code OCR_NO_BRN}(빈 이미지·흐린 스캔)까지 보상하면 "인식 안 되는
     * 이미지로 무한 시도"라는 세탁 경로가 열리고, {@code MATCH} 는 되돌릴 필요가 없다(성공하면 그 번호가
     * 중복검사로 잠긴다). {@code RateLimiter#reset} javadoc 의 "무인증 경로에서 함부로 지우지 말 것"
     * 경고를 지키는 선이다.
     */
    private BusinessLicenseCrossCheckOutcome ocrFailed(String brnKey) {
        rateLimiter.reset(brnKey);
        return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
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
    private BusinessLicenseCrossCheckOutcome compare(String ocrBrn, String inputDigits, String brnHash) {
        String ocrDigits = digitsOnly(ocrBrn);
        if (ocrDigits.length() != BRN_DIGITS) {
            // null/공백뿐 아니라 "자릿수가 맞지 않는 인식 결과"도 신뢰하지 않는다 — 부분 인식(9자리)이나
            // 다른 숫자 블록(전화번호 등)을 사업자번호로 오인한 값으로 정상 가입을 막으면 안 된다.
            return BusinessLicenseCrossCheckOutcome.OCR_NO_BRN;
        }
        if (ocrDigits.equals(inputDigits)) {
            // ⚠️ 이건 "입력 번호와 인식 번호가 일치한다"는 사실일 뿐, 등록증이 진짜라는 증명은 아니다 —
            // 번호만 인쇄한 백지 이미지로도 여기까지 온다. 이 게이트의 목적은 "확정적으로 다른 조합"을
            // 걸러내는 것이지 문서 진위 판정이 아니다(진위는 국세청 확인이 담당).
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

    /** OCR 입력 이미지 — 실패하면 {@code outcome} 이 채워지고 {@code bytes} 는 null 이다. */
    private record OcrImage(byte[] bytes, BusinessLicenseCrossCheckOutcome outcome) {

        static OcrImage of(byte[] bytes) {
            return new OcrImage(bytes, null);
        }

        static OcrImage failed(BusinessLicenseCrossCheckOutcome outcome) {
            return new OcrImage(null, outcome);
        }
    }

    /** 이미지는 원본 바이트를 그대로, PDF 는 첫 페이지를 래스터화해 OCR 입력으로 만든다. */
    private OcrImage toOcrImage(MultipartFile file, DetectedFileType type) {
        try {
            byte[] bytes = file.getBytes();
            if (type != DetectedFileType.PDF) {
                return OcrImage.of(bytes);
            }
            PdfFirstPageRasterizer.Result result = pdfRasterizer.renderFirstPageAsPng(bytes);
            return switch (result.status()) {
                case OK -> OcrImage.of(result.pngBytes());
                // 용량 문제(이 파일의 잘못이 아님) — 통과시키면 렌더 슬롯을 점유해 위조 PDF 를 통과시키는
                // 직접적 우회가 되므로 막는다. 피해는 PDF 경로·초 단위로 국소적이다.
                case BUSY -> OcrImage.failed(BusinessLicenseCrossCheckOutcome.RASTERIZE_BUSY);
                // 이 파일을 처리할 수 없는 경우 — 알 수 없는 형식·손상 파일과 같은 취급(fail-open).
                case TIMED_OUT, UNRENDERABLE ->
                        OcrImage.failed(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
            };
        } catch (IOException | RuntimeException e) {
            log.warn("사업자등록증 대조용 이미지 준비 실패 — fail-open 통과. type={}, exception={}",
                    type, e.getClass().getSimpleName());
            return OcrImage.failed(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);
        }
    }

    /** 타입별 크기 상한 — PDF 만 더 좁다(파싱·렌더 비용 때문). */
    private boolean isWithinSizeLimit(MultipartFile file, DetectedFileType type) {
        long limit = type == DetectedFileType.PDF
                ? Math.min(PDF_MAX_SIZE_BYTES, fileStorageProperties.getMaxSizeBytes())
                : fileStorageProperties.getMaxSizeBytes();
        return file.getSize() <= limit;
    }

    /**
     * 이미지가 실제로 디코딩 가능한지 + 픽셀폭탄이 아닌지 확인한다(헤더만 읽는 싼 검사).
     * rate-limit 획득보다 <b>먼저</b> 수행돼야 폭탄 파일이 정상 가입자의 대조 예산을 갉아먹지 않는다.
     */
    private boolean isRenderableImage(MultipartFile file) {
        try {
            ImageDimensionValidator.ensureWithinPixelLimit(file, OCR_MAX_PIXELS);
            return true;
        } catch (RuntimeException e) {
            log.warn("사업자등록증 대조 사전 검사 탈락 — OCR 생략(가입은 계속). exception={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 사업자번호 축(좁은 축) 시도 한도 — 같은 번호로 대조를 반복 실패하는 흐름을 끊는다.
     *
     * <p>차단({@code MISMATCH})은 회사·유저 행을 만들지 않으므로 중복검사가 영원히 통과한다 = 같은
     * 요청을 무한 반복할 수 있다. 이 축이 없으면 그 반복이 전역 캡을 태운다(2026-08-04 리뷰 P1).
     *
     * <p>⚠️ <b>알려진 한계 — 타깃 봉쇄가 성립한다.</b> 이 축의 키는 <b>공격자가 고르는 입력값</b>으로
     * 만들어지므로, 특정 회사의 사업자번호로 아무 이미지나 {@code brnLimit} 번 제출하면 그 창 동안
     * <b>그 회사의 진짜 담당자</b>도 대조 단계에서 막힌다(429). 자기소진 방어와 정면 충돌하는 지점이라
     * 완전 제거는 불가능하고, 대신 피해를 관리한다:
     * <ol>
     *   <li>창을 짧게(기본 15분) 잡아 봉쇄 지속 시간을 줄인다.</li>
     *   <li>응답은 <b>중립적인 429</b>라 피해자가 "내 등록증이 잘못됐다"고 오해하지 않는다.</li>
     *   <li>같은 번호가 반복해서 한도에 걸리면 {@code log.error} 로 승격해 탐지 가능하게 한다.</li>
     *   <li><b>운영 복구 절차</b>: 피해 신고 시 해당 Redis 키를 지우면 즉시 해제된다 —
     *       {@code DEL rate:business-license-crosscheck:brn:{해시접두16}}. 해시 접두는 이 클래스가 남기는
     *       {@code brnHash=} 로그(앞 8자)로 찾고, 필요하면 같은 알고리즘으로 번호를 해싱해 대조한다.</li>
     * </ol>
     */
    private boolean acquireBrnAttempt(String brnKey, String brnHash) {
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        if (rateLimiter.tryAcquire(brnKey, limit.getBrnLimit(), limit.getBrnWindow())) {
            return true;
        }
        String alertKey = BRN_ALERT_KEY_PREFIX + brnHash.substring(0, BRN_KEY_HASH_LENGTH);
        if (rateLimiter.tryAcquire(alertKey, limit.getBrnAlertThreshold(), limit.getBrnAlertWindow())) {
            log.warn("사업자등록증 대조 시도 한도 초과(사업자번호 축) — AI 호출 없이 차단. brnHash={}, limit={}/{}",
                    logHash(brnHash), limit.getBrnLimit(), limit.getBrnWindow());
        } else {
            // 같은 번호가 창을 넘나들며 반복해서 한도에 걸린다 = 단순 재시도가 아니라 타깃 봉쇄 의심.
            log.error("사업자등록증 대조 시도 한도 반복 초과 — 특정 사업자번호 봉쇄 시도 의심. "
                            + "brnHash={} (해제: DEL {}{})",
                    logHash(brnHash), RATE_LIMIT_BRN_KEY_PREFIX, brnHash.substring(0, BRN_KEY_HASH_LENGTH));
        }
        return false;
    }

    /**
     * 대조 전용 전역 rate-limit(분당 → 일일). 순서가 중요하다 — {@link RateLimiter#tryAcquire} 는
     * 허용 여부와 무관하게 카운터를 증가시키므로, 순서가 뒤집히면 분당 축에서 막힌 요청이 일일 축의
     * 예산까지 갉아먹는다({@code BusinessLicenseOcrService.enforceRateLimit()} 과 동일 원칙).
     *
     * @return 두 축 모두 통과하면 true. false 여도 <b>가입은 통과</b>한다(fail-open) — 이 경보는 "그 시간
     *         동안 게이트가 꺼져 있었다"를 사후에 알기 위한 것이다.
     */
    private boolean acquireGlobalRateLimit() {
        AuthProperties.BusinessLicenseCrossCheckRateLimit limit =
                authProperties.getBusinessLicenseCrossCheckRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limit.getGlobalLimit(), limit.getGlobalWindow())) {
            log.warn("사업자등록증 대조 rate-limit 초과(분당 전역) — 대조 생략, 가입은 통과(fail-open). limit={}/{}",
                    limit.getGlobalLimit(), limit.getGlobalWindow());
            return false;
        }
        if (!rateLimiter.tryAcquire(RATE_LIMIT_DAILY_KEY, limit.getDailyLimit(), limit.getDailyWindow())) {
            // ⚠️ ERROR 로 승격한다 — 일일 캡 소진은 "그날 남은 시간 동안 가입 심사 게이트가 통째로
            // 꺼진다"는 뜻이라 분당 캡의 순간적 스로틀과 심각도가 다르다. 이 로그가 유일한 경보 축이다.
            log.error("사업자등록증 대조 rate-limit 초과(일일 캡) — 가입 심사 게이트가 fail-open 으로 무력화됨. limit={}/{}",
                    limit.getDailyLimit(), limit.getDailyWindow());
            return false;
        }
        return true;
    }

    /**
     * 사업자등록번호(숫자 10자리)의 단방향 해시 — 평문을 Redis 키·로그에 남기지 않기 위한 변환.
     *
     * <p>비밀키가 설정돼 있으면 <b>HMAC-SHA256</b> 을 쓴다. 순수 SHA-256 은 값 공간이 10^10 뿐이라
     * GPU 전수계산으로 분 단위에 역산되며, 그러면 "로그·Redis 접근 권한 = 사업자번호 목록 획득"이 된다.
     * 키가 있으면 그 경로가 닫힌다. 키 미설정 시에는 SHA-256 으로 낙하한다(기동은 막지 않되 경고).
     */
    private String brnHash(String digits) {
        byte[] input = digits.getBytes(StandardCharsets.UTF_8);
        try {
            if (brnHashKey == null) {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(brnHashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // SHA-256/HmacSHA256 은 JDK 표준 필수 알고리즘이라 도달 불가.
            throw new IllegalStateException("사업자번호 해시 알고리즘 초기화 실패", e);
        }
    }

    private static String logHash(String brnHash) {
        return brnHash.substring(0, BRN_LOG_HASH_LENGTH);
    }
}
