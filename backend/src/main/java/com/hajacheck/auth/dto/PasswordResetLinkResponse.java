package com.hajacheck.auth.dto;

/**
 * 비밀번호 재설정 1단계 응답 — 계정 존재 여부와 <b>무관하게 항상 동일한 바디</b>(계정 열거 방지).
 *
 * <p>⚠️ <b>필드를 추가하지 말 것.</b> 특히 resetToken 을 여기 담으면 최초 P1(준공개 정보만으로 계정 탈취)이
 * 그대로 재현된다. 토큰은 메일로만 전달한다. 존재 여부에 따라 값이 달라지는 필드도 열거 단서가 된다.
 */
public record PasswordResetLinkResponse(boolean requested) {

    /** 요청 접수 응답 — 계정이 있든 없든, 메일을 보냈든 아니든 <b>항상 이 값</b>이다. */
    public static PasswordResetLinkResponse accepted() {
        return new PasswordResetLinkResponse(true);
    }
}
