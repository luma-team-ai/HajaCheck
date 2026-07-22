package com.hajacheck.bizverify.service;

/**
 * 국세청 사업자등록정보 진위확인 결과(#596). 가입 판정은 {@code CompanySignupService} 가 수행한다.
 *
 * <ul>
 *   <li>{@link #VERIFIED}: 진위 일치 + 계속사업자 → 가입 성공 + verification_status=VERIFIED</li>
 *   <li>{@link #MISMATCH}: 진위 불일치(valid=02, 미등록·상태 미상 포함) → 가입 차단</li>
 *   <li>{@link #SUSPENDED}: 휴업(b_stt_cd=02) → 가입 차단(보수적 처리 — 재검토 여지)</li>
 *   <li>{@link #CLOSED}: 폐업(b_stt_cd=03) → 가입 차단</li>
 *   <li>{@link #SKIPPED}: fail-open — serviceKey 미설정 또는 국세청 API 장애/응답 파싱 실패.
 *       가입을 막지 않고 그대로 진행(verification_status=PENDING 유지)</li>
 * </ul>
 */
public enum NtsVerificationOutcome {
    VERIFIED,
    MISMATCH,
    SUSPENDED,
    CLOSED,
    SKIPPED
}
