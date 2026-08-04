package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.media.support.ImageDimensionValidator;
import com.hajacheck.core.media.support.ImageSignatureValidator;
import com.hajacheck.global.common.ApiResponse;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;
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
 * 여기는 심사 게이트라 실패를 <b>삼키고 통과</b>(fail-open)시켜야 한다. 무엇보다 rate-limit 키를
 * 공유하면 <b>공격자가 비로그인 프리필 API를 반복 호출해 캡을 소진시키는 것만으로 이 게이트를 통째로
 * 무력화</b>할 수 있다(캡 소진 시 동작이 fail-open 통과이므로). 그래서 대조 전용 키·전용 캡을 둔다.
 *
 * <p><b>계약</b>: {@link #crossCheck}는 <b>어떤 경우에도 예외를 던지지 않는다</b>. 판정은 반환값
 * ({@link BusinessLicenseCrossCheckOutcome})으로만 전달하고, 차단 결정(예외 변환)은 호출부인
 * {@code CompanySignupService}가 한다. 삼킨 예외는 {@code log.warn}으로 남기되 개인정보(이메일·
 * 사업자등록번호·대표자명)는 절대 로그에 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessLicenseCrossCheckService {

    /**
     * OCR 가능한 형식 — {@link BusinessLicenseOcrService}와 동일하게 래스터 이미지 2종만이다
     * (ImageSignatureValidator/RapidOCR가 PDF를 다루지 못한다). 회원가입 파일 저장 허용목록
     * ({@code FileStorageProperties}, PDF 포함)보다 좁으므로, PDF 업로드는 대조 없이 통과한다.
     */
    private static final Set<String> OCR_ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    /** 디컴프레션(픽셀) 폭탄 방어 상한 — 근거는 {@link BusinessLicenseOcrService} 의 동일 상수 참조. */
    private static final long OCR_MAX_PIXELS = 50_000_000L; // 50MP

    /**
     * ⚠️ <b>공개 프리필용 {@code rate:business-license-ocr:*} 과 반드시 다른 키다.</b> 공유하면 위
     * 클래스 주석의 "프리필 반복 호출로 게이트 무력화" 공격이 성립한다 — 키 분리가 이 방어의 전부이므로
     * 리팩터링으로 상수를 합치지 말 것.
     */
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:business-license-crosscheck:global";
    private static final String RATE_LIMIT_DAILY_KEY = "rate:business-license-crosscheck:daily";

    /** 사업자등록번호 정규형 자릿수 — 숫자만 남겼을 때 이 길이가 아니면 인식 실패로 본다. */
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
     * @return 판정. {@link BusinessLicenseCrossCheckOutcome#MISMATCH} 만 차단 사유이고 나머지는 통과다.
     */
    public BusinessLicenseCrossCheckOutcome crossCheck(MultipartFile file, String normalizedBrn) {
        try {
            return doCrossCheck(file, normalizedBrn);
        } catch (RuntimeException e) {
            // 최후 안전망 — 이 서비스는 fail-open 이 계약이므로 예상 못 한 런타임 예외도 위로 흘리지 않는다.
            // (판정은 반환값이라 이 catch 가 MISMATCH 차단을 삼킬 일은 없다.)
            log.warn("사업자등록증 대조 중 예상치 못한 실패 — fail-open 통과. exception={}",
                    e.getClass().getSimpleName());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }
    }

    private BusinessLicenseCrossCheckOutcome doCrossCheck(MultipartFile file, String normalizedBrn) {
        // ① OCR 적합성 사전 검사 — 부적합하면 AI 호출도, rate-limit 카운터 소모도 하지 않는다
        //    (BusinessLicenseOcrService 의 "저비용 → 고비용 fail-fast" 순서 원칙과 동일).
        if (!isOcrEligible(file)) {
            return BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE;
        }

        // ② 대조 전용 rate-limit(분당 → 일일 순서).
        if (!acquireRateLimit()) {
            return BusinessLicenseCrossCheckOutcome.RATE_LIMITED;
        }

        // ③ base64 인코딩 → AI 서버 호출(내부키 강제 경유 원칙 유지 — AiProxyService 위임, #228).
        String imageBase64;
        try {
            imageBase64 = Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException e) {
            log.warn("사업자등록증 대조용 파일 읽기 실패 — fail-open 통과. exception={}",
                    e.getClass().getSimpleName());
            return BusinessLicenseCrossCheckOutcome.OCR_FAILED;
        }

        ApiResponse<BusinessLicenseOcrResponse> response;
        try {
            response = aiProxyService.ocrBusinessLicense(imageBase64);
        } catch (RuntimeException e) {
            // AI 서버 장애·응답 형식 불량(BusinessException 등). 프리필과 달리 여기선 표면화하지 않는다 —
            // 외부 장애가 회원가입 전체를 막으면 안 된다(국세청 진위확인 fail-open 과 같은 기조).
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

        // ④ 판정 — 사업자등록번호만 대조한다.
        return compare(response.data().businessRegistrationNumber(), normalizedBrn);
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
    private BusinessLicenseCrossCheckOutcome compare(String ocrBrn, String normalizedBrn) {
        String ocrDigits = digitsOnly(ocrBrn);
        if (ocrDigits.length() != BRN_DIGITS) {
            // null/공백뿐 아니라 "자릿수가 맞지 않는 인식 결과"도 신뢰하지 않는다 — 부분 인식(9자리)이나
            // 다른 숫자 블록(전화번호 등)을 사업자번호로 오인한 값으로 정상 가입을 막으면 안 된다.
            return BusinessLicenseCrossCheckOutcome.OCR_NO_BRN;
        }
        String inputDigits = digitsOnly(normalizedBrn);
        if (ocrDigits.equals(inputDigits)) {
            return BusinessLicenseCrossCheckOutcome.MATCH;
        }
        // ⚠️ 개인정보 금지 — 어느 쪽 번호도 로그에 남기지 않는다(차단 사실만 남긴다).
        log.warn("사업자등록증 대조 불일치 — 가입 차단(#1469). 업로드된 등록증의 사업자등록번호가 입력값과 다릅니다.");
        return BusinessLicenseCrossCheckOutcome.MISMATCH;
    }

    private static String digitsOnly(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /**
     * OCR 을 시도할 가치가 있는 파일인지 판정한다 — 형식/크기/실제 바이트 시그니처/선언 픽셀 수.
     *
     * <p>여기서 걸린다고 가입을 막지는 않는다({@code SKIPPED_UNSUPPORTED_TYPE} 통과). 파일 자체의
     * 적부 판정은 뒤이어 실행되는 {@code FileStorageService.store} 가 {@code FILE_*} 로 내리는 것이
     * 기존 계약이고, 그 판정을 이 게이트가 앞당겨 대신하면 회원가입 파일 정책(PDF 허용)이 바뀌어버린다.
     *
     * <p>검사 순서는 저비용 → 고비용이며, <b>rate-limit 획득보다 먼저</b> 수행된다 — 그래야 픽셀폭탄·
     * 위조 파일이 정상 가입자의 대조 예산을 갉아먹지 못한다.
     */
    private boolean isOcrEligible(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String contentType = file.getContentType();
        if (contentType == null || !OCR_ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return false;
        }
        if (file.getSize() > fileStorageProperties.getMaxSizeBytes()) {
            return false;
        }
        try {
            // 선언된 Content-Type 은 조작 가능 — 실제 매직바이트로 재검증하고, 헤더에 거대한 가로×세로를
            // 선언한 픽셀폭탄도 디코딩 전에 걸러 AI 서버를 지킨다.
            ImageSignatureValidator.validate(file);
            ImageDimensionValidator.ensureWithinPixelLimit(file, OCR_MAX_PIXELS);
            return true;
        } catch (RuntimeException e) {
            // 두 validator 는 FILE_* BusinessException 을 던진다 — 여기선 "대조 불가"로만 해석한다.
            log.warn("사업자등록증 대조 사전 검사 탈락 — OCR 생략(가입은 계속). exception={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 대조 전용 전역 rate-limit(분당 → 일일). 순서가 중요하다 — {@link RateLimiter#tryAcquire} 는
     * 허용 여부와 무관하게 카운터를 증가시키므로, 순서가 뒤집히면 분당 축에서 막힌 요청이 일일 축의
     * 예산까지 갉아먹는다({@code BusinessLicenseOcrService.enforceRateLimit()} 과 동일 원칙).
     *
     * @return 두 축 모두 통과하면 true
     */
    private boolean acquireRateLimit() {
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
}
