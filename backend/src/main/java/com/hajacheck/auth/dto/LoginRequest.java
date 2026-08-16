package com.hajacheck.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 자체(기업) 로그인 요청. loginId 는 email 로 사용된다.
 */
public record LoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
    /**
     * 비밀번호 마스킹(#1626 P3) — record 기본 toString 은 비밀번호 평문을 노출한다. 이 DTO 는
     * 데모 로그인({@code DemoLoginService.demoLoginRequest})에서 서버 보관 크레덴셜을 담아 흐르므로,
     * 실수로 로그·예외 메시지에 찍혀도 평문이 남지 않게 password 를 가린다(loginId 는 식별용으로 유지).
     */
    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + ", password=***]";
    }
}
