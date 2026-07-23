package com.hajacheck.bizverify.service;

import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.dto.BusinessVerificationRequest;
import com.hajacheck.bizverify.dto.BusinessVerificationResponse;
import com.hajacheck.bizverify.dto.BusinessVerificationResult;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사업자 진위확인 실시간 조회 API(#648) — 회원가입 폼의 [진위확인] 버튼 전용. 제출 없이 결과만 안내하며
 * 가입 자체를 막지 않는다(최종 차단은 {@code CompanySignupService}의 제출 시 재검증이 담당).
 *
 * <p>비로그인 공개 API가 국세청 외부 호출을 대신 트리거하는 셈이라 rate-limit 이 필수다
 * (BusinessLicenseOcrService와 동일 기조 — 전역 상한 두 축, IP 축 미사용 근거는
 * {@link BizVerifyProperties.RateLimit} javadoc 참고).
 *
 * <p>개인정보(사업자번호·대표자명·개업일자)는 로그에 남기지 않는다 — 결과 코드만 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessVerificationService {

    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:business-verification:global";
    private static final String RATE_LIMIT_DAILY_KEY = "rate:business-verification:daily";

    private final NtsBusinessVerifyClient ntsBusinessVerifyClient;
    private final RateLimiter rateLimiter;
    private final BizVerifyProperties bizVerifyProperties;

    public BusinessVerificationResponse verify(BusinessVerificationRequest request) {
        enforceRateLimit();

        String normalizedBrn = normalizeBrn(request.businessRegistrationNumber());
        NtsVerificationOutcome outcome = ntsBusinessVerifyClient.verifyRealtime(
                normalizedBrn, request.representativeName(), request.businessStartDate());

        // 개인정보 미기록 — 결과 코드만 남긴다(NtsBusinessVerifyClient 컨벤션과 동일).
        log.info("사업자 진위확인(실시간) 완료 — outcome={}", outcome);

        return toResponse(outcome);
    }

    /**
     * 분당 축 → 일일 축 순으로 검사한다(BusinessLicenseOcrService와 동일 원칙) — 먼저 막힌 축이 다음 축
     * 카운터를 소모하지 않게 하기 위함이다({@link RateLimiter#tryAcquire}는 허용 여부와 무관하게 호출
     * 시 카운터를 증가시킨다).
     */
    private void enforceRateLimit() {
        BizVerifyProperties.RateLimit limit = bizVerifyProperties.getRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limit.getGlobalLimit(), limit.getGlobalWindow())) {
            log.warn("사업자 진위확인(실시간) rate-limit 초과(분당 전역) — limit={}/{}",
                    limit.getGlobalLimit(), limit.getGlobalWindow());
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
        if (!rateLimiter.tryAcquire(RATE_LIMIT_DAILY_KEY, limit.getDailyLimit(), limit.getDailyWindow())) {
            // 일일 절대 캡 — 회원가입 플로우와 공유하는 국세청 서비스키 쿼터 소진 방지(BizVerifyProperties 참고).
            log.warn("사업자 진위확인(실시간) rate-limit 초과(일일 캡) — limit={}/{}",
                    limit.getDailyLimit(), limit.getDailyWindow());
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }

    /**
     * 사업자등록번호 정규화 — 하이픈 제거(숫자 10자리 정규형). {@code CompanySignupService.normalizeBrn}
     * 와 동일 로직이나, 이 서비스는 다른 패키지(bizverify)라 package-private 헬퍼를 공유할 수 없어
     * 별도로 둔다(단순 한 줄 로직이라 중복 비용이 낮다).
     */
    private static String normalizeBrn(String raw) {
        return raw == null ? null : raw.replaceAll("-", "").trim();
    }

    private static BusinessVerificationResponse toResponse(NtsVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> new BusinessVerificationResponse(
                    BusinessVerificationResult.VERIFIED, "사업자 정보가 국세청 등록정보와 일치합니다.");
            case NOT_REGISTERED -> new BusinessVerificationResponse(
                    BusinessVerificationResult.NOT_REGISTERED, "국세청에 등록되지 않은 사업자등록번호입니다.");
            case MISMATCH -> new BusinessVerificationResponse(
                    BusinessVerificationResult.MISMATCH, "사업자등록번호는 존재하나 대표자명 또는 개업일자가 일치하지 않습니다.");
            case SUSPENDED -> new BusinessVerificationResponse(
                    BusinessVerificationResult.SUSPENDED, "현재 휴업 상태인 사업자입니다.");
            case CLOSED -> new BusinessVerificationResponse(
                    BusinessVerificationResult.CLOSED, "폐업 처리된 사업자입니다.");
            case SKIPPED -> new BusinessVerificationResponse(
                    BusinessVerificationResult.UNAVAILABLE, "일시적인 오류로 진위확인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        };
    }
}
