package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.config.AppMailProperties;
import com.hajacheck.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 발송 구현체 선택 규칙 검증 — 설정 유무만으로 실발송/로그 폴백이 갈리고, <b>빈 문자열이 실발송으로
 * 오인되지 않는지</b>가 핵심이다(빈값 함정: 로컬에서 조용한 발송 실패 → 개발 불가).
 */
class PasswordResetMailSenderSelectionTest {

    @Configuration
    @EnableConfigurationProperties({AppMailProperties.class, AuthProperties.class})
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withBean(SmtpPasswordResetMailSender.class)
            .withBean(LoggingPasswordResetMailSender.class);

    @Test
    void SMTP_HOST_미설정이면_로그_폴백이_선택된다() {
        // 로컬/dev: 메일 설정 없이도 앱이 뜨고 링크가 로그로 나와야 개발이 가능하다.
        runner.run(context -> assertThat(context.getBean(PasswordResetMailSender.class))
                .isInstanceOf(LoggingPasswordResetMailSender.class));
    }

    @Test
    void SMTP_HOST가_빈_문자열이면_로그_폴백이_선택된다() {
        // ⚠️ 함정: ${SMTP_HOST:} 매핑은 미설정 시 "빈 문자열로 존재"한다. 키 존재만 보는 조건
        // (@ConditionalOnProperty 등)을 쓰면 실발송 구현체가 선택돼 로컬에서 조용히 발송 실패한다.
        runner.withPropertyValues("spring.mail.host=")
                .run(context -> assertThat(context.getBean(PasswordResetMailSender.class))
                        .isInstanceOf(LoggingPasswordResetMailSender.class));
    }

    @Test
    void SMTP_HOST가_공백뿐이어도_로그_폴백이_선택된다() {
        runner.withPropertyValues("spring.mail.host=   ")
                .run(context -> assertThat(context.getBean(PasswordResetMailSender.class))
                        .isInstanceOf(LoggingPasswordResetMailSender.class));
    }

    @Test
    void SMTP_HOST가_설정되면_실발송이_선택된다() {
        // 운영(arm1): compose :? 가드로 SMTP_HOST 가 반드시 존재 → 항상 이 분기.
        runner.withPropertyValues("spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context.getBean(PasswordResetMailSender.class))
                        .isInstanceOf(SmtpPasswordResetMailSender.class));
    }

    @Test
    void 발송_구현체는_항상_정확히_하나만_뜬다() {
        // 두 조건이 상호배타가 아니면 주입 지점에서 NoUniqueBeanDefinitionException 이 난다.
        runner.run(context -> assertThat(context.getBeansOfType(PasswordResetMailSender.class)).hasSize(1));
        runner.withPropertyValues("spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context.getBeansOfType(PasswordResetMailSender.class)).hasSize(1));
    }
}
