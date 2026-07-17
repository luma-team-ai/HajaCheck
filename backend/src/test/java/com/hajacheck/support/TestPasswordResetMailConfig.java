package com.hajacheck.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * test 프로파일에서 발송을 가로채는 스텁을 <b>@Primary</b> 로 올린다.
 *
 * <p>test 에는 SMTP 설정이 없으므로 실제로는 LoggingPasswordResetMailSender(로그 폴백)가 뜬다.
 * 그 빈을 지우지 않고 @Primary 스텁을 얹는 이유: "SMTP 미설정 시 로그 폴백이 선택된다"는 성질 자체를
 * 통합 테스트에서 그대로 확인할 수 있게 남겨두기 위함이다.
 */
@Configuration
@Profile("test")
public class TestPasswordResetMailConfig {

    @Bean
    @Primary
    public RecordingPasswordResetMailSender recordingPasswordResetMailSender() {
        return new RecordingPasswordResetMailSender();
    }
}
