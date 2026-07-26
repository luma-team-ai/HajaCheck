package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 기업 가입 반려 요청(#363) — 사유는 신청자에게 그대로 노출되므로 필수값으로 강제한다. */
public record CompanyRejectRequest(
        @NotBlank(message = "반려 사유는 필수입니다.")
        @Size(max = 500, message = "반려 사유는 500자 이하여야 합니다.")
        String reason) {
}
