package com.hajacheck.global.exception;

/**
 * 현재 도메인 상태에서 허용되지 않는 상태 전이를 요청했을 때 사용하는 예외다.
 * 예상하지 못한 {@link IllegalStateException}과 구분해 전역 409 응답 범위를 제한한다.
 */
public class DomainStateTransitionException extends IllegalStateException {

    public DomainStateTransitionException(String message) {
        super(message);
    }
}
