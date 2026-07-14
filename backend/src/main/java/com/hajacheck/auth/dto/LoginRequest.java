package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 자체(기업) 로그인 요청. loginId 는 email 로 사용된다.
 */
public record LoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}
