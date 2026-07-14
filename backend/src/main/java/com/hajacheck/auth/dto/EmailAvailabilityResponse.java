package com.hajacheck.auth.dto;

/**
 * 이메일(아이디) 중복확인 응답 — available=true 면 사용 가능.
 */
public record EmailAvailabilityResponse(boolean available) {

    public static EmailAvailabilityResponse of(boolean available) {
        return new EmailAvailabilityResponse(available);
    }
}
