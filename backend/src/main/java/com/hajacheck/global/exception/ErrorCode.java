package com.hajacheck.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * ErrorCode 네이밍: {도메인}_{원인} — SpringBoot_코드_컨벤션.md §5
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증(auth)
    // 로그인 실패는 계정 열거 방지를 위해 id/pw/미존재/잠금 구분 없이 이 코드로 통일 응답.
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    // 정지 계정 명시 응답용(예약) — 로그인 경로는 위 통일 정책을 따른다.
    AUTH_ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정입니다."),

    // 도메인별 예시 — 각 담당이 추가
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "하자를 찾을 수 없습니다."),
    AI_JOB_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "AI 분석 요청이 시간 초과되었습니다.");

    private final HttpStatus status;
    private final String message;
}
