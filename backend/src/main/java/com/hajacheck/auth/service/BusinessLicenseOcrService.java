package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.media.support.ImageSignatureValidator;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사업자등록증 OCR 공개 프록시(#557 / HAJA-169) — 기업 가입 전(비로그인) 화면에서 이미지를 올리면
 * 사업자번호·상호·대표자명을 미리 채워주는 보조 기능. AI 서버 실호출은 {@link AiProxyService}에 위임해
 * 내부키 강제 경유 원칙(#228)을 그대로 유지하고, 이 서비스는 ①파일 검증 ②rate-limit ③base64 인코딩만
 * 담당한다.
 *
 * <p>비로그인 + CPU 무거운 다운스트림(RapidOCR+LLM)이라 rate-limit이 필수다. 이 레포는 IP 축을 쓰지
 * 않는다는 기존 결정(AuthProperties.PasswordResetRateLimit 문서 — X-Forwarded-For가 host nginx
 * 통과값이라 위조 가능)을 그대로 따라, 전역 고정창 상한만 적용한다(알려진 한계는 AuthProperties 참고).
 * 축은 두 개다 — <b>분당 상한</b>(순간 CPU 방어)과 <b>일일 절대 캡</b>(security-reviewer P1, #557 —
 * 분당 상한만으로는 지속 반복 시 일일 LLM 호출/과금이 무제한이 된다). 둘 다 전역(요청자 축 없음).
 *
 * <p>Fail-safe: AI 서버 실패(BusinessException)·OCR/LLM 거부(ApiResponse.fail)는 예외를 삼키지 않고
 * 그대로 표면화한다 — 프론트가 이를 받아 수동 입력 폴백으로 전환할 수 있고, 이 실패가 회원가입 자체를
 * 막지는 않는다(OCR과 가입 제출은 서로 다른 API 호출이라 이 호출이 실패해도 가입 폼 제출은 별개로
 * 계속 가능하다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessLicenseOcrService {

    // ImageSignatureValidator/RapidOCR가 래스터 이미지만 지원 — FileStorageProperties 전체 허용목록
    // (JPG/PNG/PDF, 회원가입 파일 저장용)과 달리 OCR에는 이미지 2종만 허용한다. PDF는 매직바이트
    // 검증도, OCR 디코딩도 지원하지 않는다(ImageSignatureValidator 는 JPEG/PNG 시그니처만 대조).
    private static final Set<String> OCR_ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:business-license-ocr:global";
    private static final String RATE_LIMIT_DAILY_KEY = "rate:business-license-ocr:daily";

    private final AiProxyService aiProxyService;
    private final RateLimiter rateLimiter;
    private final AuthProperties authProperties;
    private final FileStorageProperties fileStorageProperties;

    /**
     * ①파일 검증(형식·신뢰 금지 원칙 — Content-Type 헤더는 조작 가능하므로 매직바이트로 재확인)
     * ②rate-limit(전역 고정창, 분당+일일 두 축) ③base64 인코딩 후 {@link AiProxyService}로 위임.
     */
    public ApiResponse<BusinessLicenseOcrResponse> ocr(MultipartFile file) {
        validate(file);
        enforceRateLimit();

        String imageBase64;
        try {
            imageBase64 = Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException e) {
            log.warn("사업자등록증 OCR 파일 읽기 실패", e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        return aiProxyService.ocrBusinessLicense(imageBase64);
    }

    /**
     * 요청 파라미터(파일)는 신뢰하지 않는다 — 존재/크기/선언된 타입/실제 매직바이트를 모두 검증한 뒤에만
     * 사용한다. FileStorageProperties.maxSizeBytes(10MB)는 회원가입 파일 저장 상한과 동일 값을 재사용한다
     * (OCR도 결국 같은 사업자등록증 파일을 다루므로 상한을 이원화할 이유가 없다).
     */
    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        String contentType = file.getContentType();
        if (contentType == null || !OCR_ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        if (file.getSize() > fileStorageProperties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        // 클라이언트가 보낸 Content-Type은 조작 가능 — 실제 파일 바이트 시그니처로 재검증.
        ImageSignatureValidator.validate(file);
    }

    /**
     * 비로그인 엔드포인트라 이메일/사용자 축이 없다 — 전역 고정창 상한 두 개(분당 → 일일)를 순서대로
     * 적용한다(AuthProperties.BusinessLicenseOcrRateLimit 참고, 알려진 한계 포함). 분당 축에서 먼저
     * 막히면 일일 카운터는 소모하지 않는다(PasswordResetService의 "먼저 막힌 축은 다음 축을 소모하지
     * 않는다" 원칙과 동일 — {@link RateLimiter#tryAcquire}는 허용 여부와 무관하게 호출 시 카운터를
     * 증가시키므로, 순서가 뒤집히면 한 축에서 막힌 요청이 다른 축의 예산을 갉아먹는다).
     */
    private void enforceRateLimit() {
        AuthProperties.BusinessLicenseOcrRateLimit limit = authProperties.getBusinessLicenseOcrRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limit.getGlobalLimit(), limit.getGlobalWindow())) {
            // ⚠️ 이 WARN 이 유일한 경보 축이다 — 전역 상한에 닿으면 정상 사용자도 429 를 맞는데
            // (IP 축 미사용), 로그가 없으면 아무도 모른 채 OCR 보조기능이 무증상으로 죽는다.
            log.warn("사업자등록증 OCR rate-limit 초과(분당 전역) — limit={}/{}",
                    limit.getGlobalLimit(), limit.getGlobalWindow());
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
        if (!rateLimiter.tryAcquire(RATE_LIMIT_DAILY_KEY, limit.getDailyLimit(), limit.getDailyWindow())) {
            // 일일 절대 캡(security-reviewer P1) — 분당 캡만으로는 지속 반복 시 일일 LLM 호출/과금이
            // 무제한이 된다(20/분 × 1440분 = 최대 28,800/일). 유료 LLM 다운스트림 총량 방어선.
            log.warn("사업자등록증 OCR rate-limit 초과(일일 캡) — limit={}/{}",
                    limit.getDailyLimit(), limit.getDailyWindow());
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }
}
