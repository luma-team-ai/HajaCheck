package com.hajacheck.global.common;

import com.hajacheck.global.exception.ErrorCode;

/**
 * 응답 공통 envelope — SpringBoot_코드_컨벤션.md §4
 * 성공: { "success": true, "data": { ... } }
 * 실패: { "success": false, "error": { "code": "...", "message": "..." } }
 */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, null, new ErrorBody(errorCode.name(), errorCode.getMessage()));
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message));
    }
}
