package com.hajacheck.core.ai.dto;

/**
 * 프론트에 반환하는 사업자등록증 OCR 응답(#557 / HAJA-169) — AI 서버 원본 응답(raw 신뢰도·stub 등)에서
 * 사업자번호·상호·대표자명 3필드만 화이트리스트로 옮겨 담는다.
 */
public record BusinessLicenseOcrResponse(
        String businessRegistrationNumber,
        String companyName,
        String representativeName) {

    public static BusinessLicenseOcrResponse from(BusinessLicenseOcrAiEnvelope.Data data) {
        return new BusinessLicenseOcrResponse(
                data.businessRegistrationNumber(),
                data.companyName(),
                data.representativeName());
    }
}
