package com.hajacheck.invitecode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 초대 코드 폐기 요청(#794, PR머신 리뷰 P3) — 코드를 URL 경로 대신 바디로 받는다. 1회용이지만
 * 배선 권한을 가진 크레덴셜 성격이라, 경로 변수로 실으면 nginx/서블릿 액세스 로그·브라우저 히스토리에
 * 평문으로 남을 수 있다(발급 응답·redeem 요청은 이미 바디로만 오간다 — 폐기만 예외였던 것을 통일).
 */
public record InviteCodeRevokeRequest(
        @NotBlank @Size(max = 16) String code
) {
}
