package com.hajacheck.auth.entity;

/**
 * 플랫폼 관리자 검증 복구({@link Company#restoreBusinessVerificationByAdmin})가 <b>어느 경로로</b>
 * 복원했는지(#1367). 서비스가 로그·응답·가드 적용 여부를 이 값으로 구분한다.
 *
 * <p>DB 에 저장되지 않는 반환 전용 타입이다(컬럼 매핑 없음).
 */
public enum AdminRestoreMode {

    /**
     * <b>관리자 자기 조치의 순수 취소</b> — 무효화 직전이 국세청 인정 상태(VERIFIED/LEGACY_VERIFIED)였던
     * 회사를 그 상태로 되돌린다. 국세청 판정을 덮는 것이 아니라 관리자가 자신의 오조작을 되무르는 것이므로
     * "관리자는 국세청 판정을 대신하지 않는다"는 원칙과 충돌하지 않는다.
     *
     * <p>PENDING 을 거치지 않으므로 <b>다음 재검증 배치를 기다릴 필요가 없다</b> — 오조작 revoke 로 정상
     * 회사가 최대 하루 가까이 서비스 중단되는 것을 막는 것이 이 경로의 존재 이유다.
     */
    RESTORED_TO_VERIFIED,

    /**
     * <b>재검증 대상 복귀</b> — 배치가 강등한 FAILED 등, 국세청 인정 이력을 증명할 수 없는 회사를 PENDING
     * 으로 되돌린다. 스코프는 아직 닫힌 채이고 다음 재검증 배치가 국세청에 다시 물어 판정한다.
     *
     * <p>배치에 의존하므로 "배치가 실제로 집을 수 있는 회사인가"(개업일자·데모·반려 여부) 가드가
     * 이 경로에만 적용된다.
     */
    RESTORED_TO_PENDING
}
