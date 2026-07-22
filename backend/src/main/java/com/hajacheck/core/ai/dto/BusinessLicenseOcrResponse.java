package com.hajacheck.core.ai.dto;

/**
 * 프론트에 반환하는 사업자등록증 OCR 응답(#557 / HAJA-324) — AI 서버 원본 응답(raw 신뢰도·stub 등)에서
 * 사업자번호·상호·대표자명·개업연월일 4필드만 화이트리스트로 옮겨 담는다.
 *
 * <p>businessStartDate(개업연월일, #598)는 국세청 진위확인(#596) 요청에 필요해 FE가 별도 입력 없이
 * 자동채움한다. nullable — AI 서버가 인식/파싱에 실패하면 null 로 내려오고, 이 경우 FE는 수동 입력으로
 * 폴백한다(다른 3필드와 동일한 원칙).
 */
public record BusinessLicenseOcrResponse(
        String businessRegistrationNumber,
        String companyName,
        String representativeName,
        String businessStartDate) {

    public static BusinessLicenseOcrResponse from(BusinessLicenseOcrAiEnvelope.Data data) {
        return new BusinessLicenseOcrResponse(
                data.businessRegistrationNumber(),
                data.companyName(),
                data.representativeName(),
                data.businessStartDate());
    }
}
