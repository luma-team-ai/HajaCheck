package com.hajacheck.bizverify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 사업자 진위확인 실시간 조회 요청(#648, application/json). 회원가입 전 [진위확인] 버튼 전용 —
 * {@code CompanySignupRequest}(multipart)와 검증 규칙을 동일하게 맞춘다(정책 불일치로 인한 혼선 방지).
 */
public record BusinessVerificationRequest(

        @NotBlank(message = "사업자등록번호는 필수입니다.")
        @Pattern(regexp = "\\d{3}-?\\d{2}-?\\d{5}", message = "사업자등록번호 형식이 올바르지 않습니다.")
        String businessRegistrationNumber,

        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = 100, message = "대표자명은 100자 이하여야 합니다.")
        String representativeName,

        @NotNull(message = "개업일자는 필수입니다.")
        LocalDate businessStartDate) {
}
