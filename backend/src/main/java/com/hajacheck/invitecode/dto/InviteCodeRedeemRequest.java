package com.hajacheck.invitecode.dto;

import jakarta.validation.constraints.NotBlank;

/** 초대 코드 redeem 요청(#794) — WAITING 상태 사용자가 발급받은 코드를 입력한다. */
public record InviteCodeRedeemRequest(
        @NotBlank String code
) {
}
