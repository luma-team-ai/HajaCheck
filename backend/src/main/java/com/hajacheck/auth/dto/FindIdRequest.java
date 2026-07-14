package com.hajacheck.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * 아이디(이메일) 찾기 요청 — 사업자등록번호 + (대표자명 또는 상호명 최소 1개).
 */
public record FindIdRequest(

        @NotBlank(message = "사업자등록번호는 필수입니다.")
        String businessRegistrationNumber,

        String representativeName,

        String companyName
) {
    /**
     * 대표자명·상호명 중 최소 1개는 있어야 한다(계약 규약). 실패 시 BindException → INVALID_INPUT(400).
     */
    @AssertTrue(message = "대표자명 또는 상호명 중 하나는 필수입니다.")
    public boolean isRepresentativeNameOrCompanyNamePresent() {
        return isNotBlank(representativeName) || isNotBlank(companyName);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
