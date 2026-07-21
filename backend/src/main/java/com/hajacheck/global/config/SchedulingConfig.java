package com.hajacheck.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 배치 활성화(NOTI-01, #425) + 시각 기준 {@link Clock} 제공.
 *
 * <p>{@code @EnableScheduling}은 컨벤션상 {@code HajacheckApplication}이 아니라 이 관심사별 설정 클래스에 둔다
 * (AsyncConfig 가 {@code @EnableAsync}를 전담하는 것과 동일 원칙).
 *
 * <p>Clock 을 KST 로 고정해 스케줄러의 "오늘" 판정이 서버 타임존에 흔들리지 않게 하고,
 * 빈으로 주입 가능하게 둬 테스트가 특정 시점을 결정적으로 재현할 수 있게 한다
 * (BuiltYearValidator 의 Clock 주입 원칙과 동일).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
