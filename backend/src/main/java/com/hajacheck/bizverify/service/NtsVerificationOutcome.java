package com.hajacheck.bizverify.service;

/**
 * 국세청 사업자등록정보 진위확인 결과(#596, NOT_REGISTERED는 #648). 가입 판정은 {@code CompanySignupService}
 * 가 수행한다({@code NtsBusinessVerifyClient#validate} 경로만 사용 — 이 경로는 NOT_REGISTERED를 절대
 * 반환하지 않으므로 기존 가입 플로우엔 회귀 영향이 없다).
 *
 * <ul>
 *   <li>{@link #VERIFIED}: 진위 일치 + 계속사업자 → 가입 성공 + verification_status=VERIFIED</li>
 *   <li>{@link #MISMATCH}: 진위 불일치(valid=02, 상태 미상 포함) → 가입 차단</li>
 *   <li>{@link #NOT_REGISTERED}: 상태조회(status API) 결과 국세청 미등록 사업자번호 — {@code #648}
 *       실시간 진위확인 전용 API({@code NtsBusinessVerifyClient#verifyRealtime})에서만 반환된다. 기존
 *       {@code validate}는 상태조회를 호출하지 않으므로 이 값을 반환하지 않는다(미등록은 valid=02로
 *       뭉뚱그려 MISMATCH가 된다 — 도메인상 값은 다르지만 둘 다 "가입 차단"으로 이어져 회귀는 아니다).</li>
 *   <li>{@link #SUSPENDED}: 휴업(b_stt_cd=02) → 가입 차단(보수적 처리 — 재검토 여지)</li>
 *   <li>{@link #CLOSED}: 폐업(b_stt_cd=03) → 가입 차단</li>
 *   <li>{@link #SKIPPED}: fail-open — serviceKey 미설정 또는 국세청 API 장애/응답 파싱 실패.
 *       가입을 막지 않고 그대로 진행(verification_status=PENDING 유지). 실시간 진위확인 API에서는
 *       사용자 대면 결과 코드 UNAVAILABLE로 매핑된다.</li>
 * </ul>
 */
public enum NtsVerificationOutcome {
    VERIFIED,
    MISMATCH,
    NOT_REGISTERED,
    SUSPENDED,
    CLOSED,
    SKIPPED
}
