package com.hajacheck.auth.support;

/**
 * 비밀번호 재설정 링크 발송 추상화. 구현은 설정 유무로 <b>정확히 하나</b>만 뜬다:
 * SMTP 설정 시 {@link SmtpPasswordResetMailSender}(실발송), 미설정 시 {@link LoggingPasswordResetMailSender}
 * (로컬/dev 로그 폴백 — "키만 넣으면 실발송으로 전환").
 *
 * <p>⚠️ 구현체는 <b>제목·본문에 사용자 입력을 삽입하지 않는다</b>(메일 헤더 인젝션 표면 제거).
 * 수신자 주소만 DB 의 기존 계정 이메일에서 온다.
 */
public interface PasswordResetMailSender {

    /**
     * 재설정 링크를 발송한다. <b>호출자(=@Async 디스패처)가 예외를 처리</b>하므로 실패 시 예외를 던져도 된다.
     *
     * @param toEmail  수신자(존재하는 계정의 이메일)
     * @param resetLink 재설정 링크(토큰 포함) — 설정된 FRONTEND_BASE_URL 기준으로 조립된 값
     */
    void send(String toEmail, String resetLink);
}
