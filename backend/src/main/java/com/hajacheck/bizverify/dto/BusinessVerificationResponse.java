package com.hajacheck.bizverify.dto;

/**
 * 사업자 진위확인 실시간 조회 응답(#648). {@code message}는 사용자 대면 안내문이며 개인정보(사업자번호·
 * 대표자명·개업일자)를 포함하지 않는다 — {@code BusinessVerificationService}가 결과 코드별 고정 문구로
 * 생성한다.
 */
public record BusinessVerificationResponse(BusinessVerificationResult result, String message) {
}
