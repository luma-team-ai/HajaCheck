package com.hajacheck.invitecode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 초대 코드 redeem 요청(#794) — WAITING 상태 사용자가 발급받은 코드를 입력한다.
 * 발급 포맷은 6자(대시 포함 "XXX-XXX")로 고정이라, 16자면 대시·공백이 섞여도 넉넉하다(PR머신 리뷰 P3) —
 * 상한이 없으면 InviteCodeKeys.canonicalize의 정규식이 임의 길이 입력 전체를 훑게 된다.
 */
public record InviteCodeRedeemRequest(
        @NotBlank @Size(max = 16) String code
) {
}
