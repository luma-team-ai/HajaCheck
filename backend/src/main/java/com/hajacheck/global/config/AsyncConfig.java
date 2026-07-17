package com.hajacheck.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} 활성화 — 현재 용도는 비밀번호 재설정 메일 발송(#194)뿐이다.
 *
 * <p>@Async 가 꺼지면 메서드가 <b>동기로 조용히 실행</b>되어(에러 없음) 재설정 1단계에 응답시간 기반
 * 계정 열거가 생긴다. 그래서 이 설정은 기능이 아니라 <b>보안 전제</b>다
 * (PasswordResetMailDispatcher 참조 — 어노테이션 부착 여부를 테스트로 고정해 둠).
 *
 * <p>실행기는 Spring Boot 가 자동 구성하는 {@code applicationTaskExecutor}(ThreadPoolTaskExecutor)를 쓴다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
