package com.hajacheck.auth.support;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.global.util.EmailMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 로컬/dev 폴백 — SMTP 미설정 시 발송 대신 재설정 링크를 <b>로그로 출력</b>해 메일 없이 개발할 수 있게 한다.
 * SMTP_* env 만 채우면 {@link SmtpPasswordResetMailSender} 로 자동 전환된다(코드 변경 불필요).
 *
 * <p>⚠️ <b>링크(=토큰 원문)를 로그에 남긴다</b> — 계약의 "토큰 평문 로깅 금지"에 대한 <b>의도된 예외</b>이며,
 * 개발자가 메일 없이 플로우를 완주하기 위한 이 폴백의 존재 이유 그 자체다. 운영에서 이 구현이 선택되는 일은
 * 없다: arm1 compose 가 {@code SMTP_HOST:?} 가드로 미설정 기동을 막는다. 수신자는 마스킹해 남긴다.
 */
@Slf4j
@Component
@Conditional(SmtpNotConfiguredCondition.class)
public class LoggingPasswordResetMailSender implements PasswordResetMailSender {

    private final AuthProperties authProperties;

    public LoggingPasswordResetMailSender(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void send(String toEmail, String resetLink) {
        log.info("""
                [DEV] SMTP 미설정 — 비밀번호 재설정 메일을 발송하지 않고 링크를 출력합니다.
                  수신자: {}
                  링크: {}
                  만료: {}분
                  (실발송하려면 SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD/MAIL_FROM env 를 설정하세요.)""",
                EmailMasker.mask(toEmail), resetLink, Math.max(1L, authProperties.getPasswordResetTtl().toMinutes()));
    }
}
