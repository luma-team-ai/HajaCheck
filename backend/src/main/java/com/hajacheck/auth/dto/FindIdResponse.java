package com.hajacheck.auth.dto;

/**
 * 아이디 찾기 응답 — 마스킹된 이메일만 노출.
 */
public record FindIdResponse(String maskedEmail) {

    public static FindIdResponse of(String maskedEmail) {
        return new FindIdResponse(maskedEmail);
    }
}
