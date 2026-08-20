package com.hajacheck.platformadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회사 검증 무효화/복구 요청(#1367) — 무효화·복구 양쪽이 같은 모양이라 한 record 를 공유한다.
 *
 * <p>{@code reason} 은 <b>필수</b>다. 이 조치는 회사 전 구성원의 스코프를 여닫는 사람 판단이고, 그 판단의
 * 근거로 남는 것은 사유 문자열뿐이다(provenance 의 {@code adminRevokeReason}/{@code adminRestoreReason}).
 * 빈 사유를 허용하면 사후에 "왜 막혔는지 아무도 모르는 회사"가 생긴다.
 */
public record CompanyVerificationActionRequest(
        @NotBlank(message = "사유는 필수입니다.")
        @Size(max = 200, message = "사유는 200자 이하여야 합니다.")
        String reason) {
}
