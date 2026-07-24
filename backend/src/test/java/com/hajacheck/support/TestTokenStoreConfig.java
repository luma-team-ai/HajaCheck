package com.hajacheck.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * test 프로파일에서 Redis 기반 빈들의 in-memory 대체재를 제공한다.
 * 실 구현(RedisTokenStore·RedisRateLimiter·RedisPasswordResetTokenStore·RedisErrorLogStore)은 모두 @Profile("!test") 라
 * test 에서 뜨지 않으므로(RedisAutoConfiguration 제외 → StringRedisTemplate 빈 부재), 여기서 채운다.
 *
 * <p>plain @Configuration + @Profile("test") 로 두어 @SpringBootTest 컴포넌트 스캔에 자동 포함되게 한다
 * (모든 통합 테스트가 별도 @Import 없이 이 fake 를 얻음). @DataJpaTest 슬라이스는 스캔에 걸리지 않는다.
 *
 * <p>반환 타입을 구현체로 둔 이유: 통합 테스트가 fake 의 검증용 메서드(reset 등)에 접근할 수 있게 하기 위함
 * (인터페이스 주입도 그대로 동작한다).
 */
@Configuration
@Profile("test")
public class TestTokenStoreConfig {

    @Bean
    public InMemoryTokenStore tokenStore() {
        return new InMemoryTokenStore();
    }

    @Bean
    public InMemoryPasswordResetTokenStore passwordResetTokenStore() {
        return new InMemoryPasswordResetTokenStore();
    }

    @Bean
    public InMemoryRateLimiter rateLimiter() {
        return new InMemoryRateLimiter();
    }

    @Bean
    public InMemoryErrorLogStore errorLogStore() {
        return new InMemoryErrorLogStore();
    }
}
