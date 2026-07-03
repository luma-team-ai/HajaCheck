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

    // 도메인별 예시 — 각 담당이 추가
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "하자를 찾을 수 없습니다."),
    AI_JOB_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "AI 분석 요청이 시간 초과되었습니다.");

    private final HttpStatus status;
    private final String message;
}
