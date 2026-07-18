package com.hajacheck.global.exception;

/**
 * 클라이언트가 제공한 도메인 입력값이 유효하지 않을 때 사용하는 예외다.
 * 예상하지 못한 {@link IllegalArgumentException}과 구분해 전역 400 응답 범위를 제한한다.
 */
public class DomainValidationException extends IllegalArgumentException {

    public DomainValidationException(String message) {
        super(message);
    }
}
