package com.hajacheck.auth.support;

import com.hajacheck.auth.config.AppMailProperties;
import com.hajacheck.auth.config.AuthProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 실제 SMTP 발송 — {@code spring.mail.host} 가 <b>비어있지 않게</b> 설정된 경우에만 뜬다
 * ({@link SmtpConfiguredCondition} — 빈 문자열 함정 주의).
 *
 * <p>운영 배포 경로 <b>양쪽</b>({@code docker-compose.arm1.yml} = DEPLOY_TARGET=arm1,
 * {@code docker-compose.prod.yml} = DEPLOY_TARGET=vm)이 compose {@code :?} 가드로 SMTP_* 미설정 시
 * 컨테이너 기동을 막으므로, 운영에서는 이 구현이 반드시 선택된다(운영이 조용히 로그 폴백으로 도는 사고를
 * 설정 레벨에서 차단). 한쪽 compose 만 가드하면 그 배포 타깃이 조용히 뚫리니 항상 함께 갱신할 것.
 */
@Component
@Conditional(SmtpConfiguredCondition.class)
public class SmtpPasswordResetMailSender implements PasswordResetMailSender {

    private final JavaMailSender mailSender;
    private final AppMailProperties mailProperties;
    private final AuthProperties authProperties;

    public SmtpPasswordResetMailSender(JavaMailSender mailSender,
                                       AppMailProperties mailProperties,
                                       AuthProperties authProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.authProperties = authProperties;
    }

    @Override
    public void send(String toEmail, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.getFrom());
        message.setTo(toEmail);
        message.setSubject(PasswordResetMailContent.SUBJECT);
        message.setText(PasswordResetMailContent.body(resetLink, authProperties.getPasswordResetTtl()));
        // 실패 시 예외는 그대로 던진다 — @Async 디스패처가 잡아서 로깅한다(응답에는 절대 반영하지 않음).
        mailSender.send(message);
    }
}
