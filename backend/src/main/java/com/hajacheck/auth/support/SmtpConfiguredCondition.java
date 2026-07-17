package com.hajacheck.auth.support;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * SMTP 가 "실제로" 설정됐는지 — {@code spring.mail.host} 가 <b>존재하고 비어있지 않은지</b>.
 *
 * <p>⚠️ <b>왜 {@code @ConditionalOnProperty} 를 쓰지 않는가(빈 문자열 함정)</b>:
 * {@code spring.mail.host: ${SMTP_HOST:}} 매핑에서 SMTP_HOST 가 미설정이면 값이 <b>빈 문자열로 존재</b>한다.
 * {@code @ConditionalOnProperty} 와 스프링의 MailSenderAutoConfiguration 은 "키 존재"만 보므로 조건이 통과되고
 * {@link org.springframework.mail.javamail.JavaMailSender} 빈까지 생성된다 → 실발송 구현체가 선택되어
 * <b>로컬에서 조용한 발송 실패</b>가 난다(로그 폴백이 죽음). 그래서 blank 를 명시적으로 배제한다.
 * (같은 함정의 선례: OAuth2 client-id 가 빈 문자열로만 존재해도 기동 실패 — docker-compose.arm1.yml 주석.)
 */
public class SmtpConfiguredCondition implements Condition {

    static boolean isSmtpConfigured(Environment environment) {
        String host = environment.getProperty("spring.mail.host");
        return host != null && !host.isBlank();
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isSmtpConfigured(context.getEnvironment());
    }
}
