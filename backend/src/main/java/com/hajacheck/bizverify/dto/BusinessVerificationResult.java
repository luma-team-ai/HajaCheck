package com.hajacheck.bizverify.dto;

/**
 * 사업자 진위확인 실시간 조회 API(#648)의 사용자 대면 결과 코드. 내부 판정
 * {@code NtsVerificationOutcome}과 1:1 매핑되지 않는다 — {@code SKIPPED}(fail-open, 내부 용어)는 이
 * API에서 {@link #UNAVAILABLE}(외부 대면 용어)로 번역된다. 이 API는 가입을 막지 않고 결과만 안내한다
 * (최종 차단은 회원가입 제출 시 서버 재검증이 담당).
 */
public enum BusinessVerificationResult {
    /** 사업자등록번호·대표자명·개업일자가 국세청 등록정보와 일치. */
    VERIFIED,
    /** 사업자등록번호가 국세청에 등록되어 있지 않음. */
    NOT_REGISTERED,
    /** 등록번호는 존재하나 대표자명 또는 개업일자가 불일치. */
    MISMATCH,
    /** 휴업 상태. */
    SUSPENDED,
    /** 폐업 상태. */
    CLOSED,
    /** 국세청 서비스 미설정 또는 일시 장애로 판정 불가(fail-open) — 가입 자체는 막지 않는다. */
    UNAVAILABLE
}
